package com.xiaowan.localinference

import android.os.Build
import android.util.Log

/**
 * 进程内 llama.cpp 推理引擎。按机型 CPU 能力选择上游预编译变体：
 * - hexagon：CPU + OpenCL GPU + Hexagon HTP NPU（仅高通机型，依赖系统 libcdsprpc.so）
 * - i8mm / dotprod / base：纯 CPU（按 ARM 扩展等级从高到低回退）
 *
 * 职责：
 * - 加载 APK 内置 librnllama（dlopen 后直接 JNI，无需 exec）
 * - 从 assets 释放 Hexagon HTP 段库（libggml-htp-vXX.so）并设置 ADSP_LIBRARY_PATH
 * - 暴露 loadModel / chat 流式生成 / abort / unload
 */
object LlmEngine {
    private const val TAG = "LlmEngine"

    // 上游 rnllama 嵌入的 ggml hexagon host 段库（按 SoC Hexagon 版本 dlopen）。
    private val HTP_LIBS = arrayOf(
        "libggml-htp-v69.so", "libggml-htp-v73.so", "libggml-htp-v75.so",
        "libggml-htp-v79.so", "libggml-htp-v81.so"
    )

    @Volatile private var loaded = false
    @Volatile private var currentPath: String? = null

    // ---- HTP（Hexagon NPU）开关 ----
    // 与模型参数同一个 SharedPreferences 库，UI 写、引擎读，收敛在一处，
    // 避免 EngineActivity / InferenceService / ensureReady 三个入口各传各的 forceHtp 造成不一致。
    private const val PREFS = "model_store"
    private const val PREF_HTP = "htp_experimental"

    /** 用户是否要求启用 HTP（默认 false = 只走 OpenCL/CPU）。 */
    fun isHtpEnabled(ctx: android.content.Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(PREF_HTP, false)

    fun setHtpEnabled(ctx: android.content.Context, enabled: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_HTP, enabled).apply()
    }

    // ---- 「NPU 配额」（mp.tensor_split）----
    // 默认份额约为 NPU 2/3、GPU 1/3，且与模型体积无关。
    // 本档位只改这个比例，**不删任何设备**：被 NPU 拒收的层仍可回落到 GPU/CPU。
    // 设备一旦被摘掉，回落就没有去处，那些层会整段落到 CPU 上跑。
    const val NPU_QUOTA_AUTO = -1
    private const val PREF_NPU_QUOTA = "npu_quota_pct"

    /** -1 = 自动（不传 tensor_split，行为与本功能引入前完全一致）；appCtx 未就绪时同样回落到自动。 */
    fun npuQuotaPct(ctx: android.content.Context? = appCtx): Int =
        ctx?.applicationContext?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            ?.getInt(PREF_NPU_QUOTA, NPU_QUOTA_AUTO) ?: NPU_QUOTA_AUTO

    fun setNpuQuotaPct(ctx: android.content.Context, pct: Int) {
        ctx.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putInt(PREF_NPU_QUOTA, pct).apply()
    }

    /** 本次进程首次 init 时实际采用的 HTP 取值（native 变体选定后不再改变）。 */
    @Volatile var htpRequested = false
        private set

    /** native 初始化失败原因（部分机型/鸿蒙 ABI 或 dlopen 不兼容时用于诊断）。 */
    @Volatile var lastInitError: String? = null
        private set
    @Volatile private var appCtx: android.content.Context? = null

    /** 生成互斥锁：UI 测试页与本地 HTTP 服务共用，保证同一时刻只有一路生成。 */
    val genLock = Any()

    /** 是否已加载模型（区别于 isLoaded：后者仅表示 native 库初始化完成）。 */
    val hasModel: Boolean get() = loaded && currentPath != null

    /** UI 侧日志钩子：EngineActivity 设置后，原生日志同时转发到界面与 ring buffer。 */
    @Volatile var logSink: ((String) -> Unit)? = null
    private val logRing = ArrayDeque<String>()
    fun recentLogs(): List<String> = synchronized(logRing) { logRing.toList() }
    fun clearLogs() = synchronized(logRing) {
        logRing.clear(); ringBase = null; ringRep = 0
    }

    private var ringBase: String? = null
    private var ringRep = 0

    /**
     * 进 ring buffer 的同时**实时落盘**（filesDir/logs/session-*.log）。
     * 原来日志只在内存里，native SIGABRT 瞬间进程就没了，用户根本来不及点导出——
     * 而这正是最需要日志的时刻。落盘只 flush 不 fsync，不引入可感知延迟。
     */
    /**
     * 卡死看门狗的计时基准：最近一次"确实有进展"的时刻（一条非噪音日志，
     * 或一步解码）。native 线程卡在 DSP 等回包时，Kotlin 侧的 stopRequested / abort 都
     * 够不着它，只能靠外部计时把「卡死」变成可判定的事实。
     */
    @Volatile var lastProgressAt: Long = 0L

    private fun pushLog(t: String) {
        // 纯探测噪音不进 ring、不进文件，只累计一条计数说明（见 LogFileStore.isNoise）
        if (LogFileStore.isNoise(t)) {
            LogFileStore.noteNoise(t)
            return
        }
        lastProgressAt = System.currentTimeMillis()
        LogFileStore.flushNoise()
        // 连续重复行折叠成 "xxx ×N"。生成期 llama 会逐 token 往 stderr 打 "."，
        // 一次回答就是上百行，既占满 300 行的 ring（把关键行挤出窗口），
        // 也让日志页全是噪声。折叠只作用于内存视图与文件，原始 token 流在聊天页仍有。
        synchronized(logRing) {
            if (t == ringBase) {
                ringRep++
                logRing.removeLast()
                logRing.addLast("$t ×$ringRep")
            } else {
                ringBase = t
                ringRep = 1
                logRing.addLast(t)
                while (logRing.size > 300) logRing.removeFirst()
            }
        }
        HtpProbe.observe(t)
        LogFileStore.append(t)
    }

    /** 原生日志回调对象：onNativeLog(level: Int, text: String) */
    private val logBridge = object : Any() {
        @Suppress("unused")
        fun onNativeLog(level: Int, text: String) {
            val t = text.trimEnd('\n')
            when (level) {
                3 -> Log.w(TAG, t)
                4 -> Log.e(TAG, t)
                else -> Log.i(TAG, t)
            }
            if (t.isBlank()) return
            pushLog(t)
            try { logSink?.invoke(t) } catch (_: Exception) {}
        }
    }

    val isLoaded: Boolean get() = loaded

    /** 业务侧状态日志，与原生日志同管线（ring buffer + 落盘 + logSink + logcat）。 */
    fun uiLog(t: String) {
        Log.i(TAG, t)
        pushLog(t)
        try { logSink?.invoke(t) } catch (_: Exception) {}
    }

    /**
     * 加载 native 库 + backend init。
     * - HTP 关闭（默认）：**不**释放 HTP 段库、不设 ADSP_LIBRARY_PATH，
     * Hexagon 设备不会注册进 ggml，调度器只有 CPU + OpenCL
     * （gpu=99 直接走 OpenCL/Adreno，不再被 NPU 抢走后 abort）。
     * - HTP 开启：释放段库 + ADSP_LIBRARY_PATH 让 NPU 参与调度（能力边界见 htpSupportedQuants）。
     *
     * @param forceHtp null（默认）= 读 SharedPreferences 里的「HTP」开关；
     * 显式传值仅用于诊断路径覆盖。native 变体一旦选定，本进程内不会再换，改开关需重启 App。
     * 返回是否成功。
     */
    @Synchronized
    fun init(context: android.content.Context, forceHtp: Boolean? = null): Boolean {
        if (loaded) return true
        appCtx = context.applicationContext
        val useHtp = forceHtp ?: isHtpEnabled(context)
        htpRequested = useHtp
        return try {
            // 先建实时日志文件并接管 stderr，才能接住后端初始化阶段的报错
            // （HTP 的 ggml_abort 就是在这之后不久发生的）
            LogFileStore.init(context)
            StderrPump.start { uiLog("[stderr] $it") }
            uiLog("[日志] 本次会话实时写入 filesDir/logs/${LogFileStore.currentFile?.name}")
            if (LogFileStore.previousUnclean) {
                val n = LogFileStore.previousMarkCount
                val note = if (n > 0) "（期间写过 $n 次切后台标记、末尾却不是＝真中断）" else ""
                uiLog("[日志] ⚠ 上次会话 ${LogFileStore.previousSessionId} 末尾无正常结束标记$note，可能在加载中/生成中崩溃或被系统杀死；日志页点「导出上次会话」可回溯死前输出。")
            }
            if (useHtp) prepareHtpLibs(context)
            // HTP 调试旋钮面板不开放给用户：
            // LM_GGML_HEXAGON_* 这些变量高通无公开文档，个别取值会把崩溃变成永久卡死，
            // 界面上摆出来无收益只有风险。
            // 此处只保留 prepareHtpLibs 这一条必需路径。
            loadNative(useHtp)
            backendInit(logBridge)
            loaded = true
            lastInitError = null
            Log.i(TAG, "llmjni[$nativeTag] loaded & backend init ok (htp=$useHtp)")
            if (useHtp && nativeTag != "hexagon")
                uiLog("[引擎] 已请求 HTP，但 hexagon 变体不可用，实际加载 $nativeTag（纯 CPU，HTP 未生效）")
            uiLog("[引擎] 后端：${backendDesc}")
            true
        } catch (t: Throwable) {
            lastInitError = "${t.javaClass.name}: ${t.message}"
            Log.e(TAG, "init failed", t)
            uiLog("[引擎] native 初始化失败：$lastInitError")
            // 非高通机型（天玑 / 麒麟 / Exynos）诊断，便于按错误文案直接定位
            uiLog("[引擎] 设备环境：$deviceProfile")
            diagnoseInitFailure(t.message)
            false
        }
    }

    /** → 设备环境快照（变体选择与 native 加载失败 / SIGILL 诊断用）。 */
    val deviceProfile: String
        get() {
            val mfr = runCatching { Build.SOC_MANUFACTURER }.getOrNull().orEmpty()
            val model = runCatching { Build.SOC_MODEL }.getOrNull().orEmpty()
            val name = if (model.isNotBlank() && !model.equals("unknown", true)) "$mfr $model".trim()
                       else runCatching { Build.HARDWARE }.getOrDefault("?")
            val c = cpuCapsCache
            val caps = if (c == null) "探测=失败"
                       else "dotprod=${c[0] == 1L} i8mm=${c[1] == 1L}" +
                           " hwcap=0x${c[2].toULong().toString(16)} hwcap2=0x${c[3].toULong().toString(16)}"
            return "SoC=$name ABI=${Build.SUPPORTED_ABIS.joinToString("/")} 变体=${nativeTag ?: "未加载"} $caps"
        }

    /**
     * 按 CPU 扩展等级挑一个上游预编译变体加载（从最优到最保守依次尝试，失败自动降级）。
     *
     * 只有 hexagon 变体带 OpenCL GPU + Hexagon NPU，也只有它 DT_NEEDED 高通专有 libcdsprpc.so，
     * 因此"高通 SoC"时才优先选它（主力高通机的 NPU/GPU 路径保持不变）；非高通机型
     * （天玑/麒麟鸿蒙机）直接选纯 CPU 变体，从根上绕开 libcdsprpc 缺失与 smmla SIGILL，
     * 无需任何改二进制的 hack。
     */
    private fun loadNative(forceHtp: Boolean) {
        System.loadLibrary("cpufeat")
        cpuCapsCache = runCatching { cpuCaps() }.getOrNull()
        val c = cpuCapsCache
        val dotprod = c != null && c[0] == 1L
        val i8mm = c != null && c[1] == 1L
        val qcom = isQualcommSoC()
        val order = ArrayList<String>(4)
        if (forceHtp || (qcom && i8mm)) order += "hexagon"
        if (i8mm) order += "i8mm"
        if (dotprod) order += "dotprod"
        order += "base"
        var last: Throwable? = null
        for (tag in order) {
            try {
                System.loadLibrary("llmjni_$tag")
                nativeTag = tag
                uiLog("[引擎] native 变体=$tag（dotprod=$dotprod i8mm=$i8mm 高通=$qcom）")
                return
            } catch (t: Throwable) {
                last = t
                Log.w(TAG, "native variant $tag unavailable: ${t.message}")
            }
        }
        throw last ?: UnsatisfiedLinkError("no llmjni_* variant available")
    }

    /** 本机 CPU 能力 [dotprod, i8mm, hwcap, hwcap2]；libcpufeat 未加载时为 null。 */
    @Volatile private var cpuCapsCache: LongArray? = null

    /** 实际加载的 native 变体：hexagon / i8mm / dotprod / base。 */
    @Volatile var nativeTag: String? = null
        private set

    /** 当前变体是否带 GPU/NPU 后端（只有 hexagon 变体编进了 OpenCL + Hexagon）。 */
    val gpuCapable: Boolean get() = nativeTag == "hexagon"

    /** HTP 是否真正生效——既请求了 HTP，又确实加载到了 hexagon 变体。 */
    val htpActive: Boolean get() = htpRequested && gpuCapable

    /**
     * HTP 后端受理 mul_mat 的量化类型白名单。
     * 对齐 ggml-hexagon.cpp `supported_mul_mat()`（v1.12.2）：只有这些类型的矩阵乘会真正上 NPU，
     * 其余（Q4_K_M / Q5_K_M / Q6_K 等 K-quant 全家）走 default → 被 HTP 拒收、落回 OpenCL/CPU。
     * 此时 HTP 仍会认领 rms_norm/softmax/rope 等旁路 op，于是计算图被切成三块、每层付跨设备同步，
     * 结果可能反而比纯 OpenCL 更慢，故不以「能开 HTP」作为性能推荐依据。
     */
    val htpSupportedQuants: List<String> = listOf("Q4_0", "Q4_1", "Q8_0", "IQ4_NL", "MXFP4", "F16", "F32")

    /** 从 GGUF 文件名提取量化标识（与 HttpApi 同源正则）；取不到=null。 */
    fun quantOf(fileName: String): String? =
        Regex("""(?i)(iq\d+[a-z0-9_]*|q\d+[a-z0-9_]*|f16|bf16|f32)""")
            .find(fileName)?.value?.uppercase()

    /**
     * GGUF header 预读结果。不加载权重（`no_alloc=true`，毫秒级），用于在**第一次加载之前**
     * 就拿到真实量化和「HTP 能受理多少比例的矩阵乘权重」，避免按文件名猜错后被迫重载一遍大模型。
     */
    data class GgufProbe(val realQuant: String, val coverage: Int, val strictUsable: Boolean, val detail: String,
        /** true=来自上次预读的持久缓存（本次未重新读 header）；UI 需如实标注来源。 */
        val cached: Boolean = false)

    @Volatile private var probePath: String? = null
    @Volatile private var probeVal: GgufProbe? = null

    /** 最近一次预读结果；null=读不出（非 GGUF / header 损坏 / 该库无此符号）。 */
    val ggufProbe: GgufProbe? get() = probeVal

    /** 只对「当前已加载模型」成立的预读结果；path 不匹配（换了文件没重载）时返回 null，UI 应忽略。 */
    val htpProbeForLoaded: GgufProbe?
        get() = if (probePath != null && probePath == currentPath) probeVal else null

    /** 文件名标称与实际类型不符（只看当前已加载模型）。true 时 UI 亮色提示；判定已按实际类型走，不需重载。 */
    val quantMismatch: Boolean?
        get() = htpProbeForLoaded?.let { !labelsMatch(loadedQuant, it.realQuant) }

    fun probeGguf(path: String): GgufProbe? {
        if (probePath == path) return probeVal
        val a = try {
            nativeProbeGguf(path)
        } catch (t: Throwable) {
            uiLog("[HTP判定] 预读不可用（${t.javaClass.simpleName}），回退按文件名判断")
            null
        }
        val p = if (a != null && a.size >= 4 && a[0].isNotEmpty() && a[1] != "-1")
            GgufProbe(a[0], a[1].toIntOrNull() ?: -1, a[2] == "1", a[3]) else null
        probePath = path
        probeVal = p
        return p
    }

    private fun probePersistKey(path: String) =
        "ggufprobe:" + java.lang.Integer.toHexString(path.hashCode()) + ":" + path.length

    /**
     * 给 UI 用的「不加载也能拿到量化信息」入口。四级来源，从快到慢：
     * 1. 本进程内存（之前预读过的任意文件，不限已加载模型）
     * 2. 持久缓存（key=path，再用 size+mtime 双校验，文件被替换或续传完成自动失效）
     * 3. native 现读 header（毫秒级；前提是 lib 已加载，onCreate 的 init 正常就满足）
     * 4. null（native 初始化失败的机型且无缓存）
     * 与 [probeGguf] 的区别：本函数会落盘缓存、命中缓存时不打 native，且绝不触发 init。
     */
    fun peekGguf(ctx: android.content.Context, f: java.io.File): GgufProbe? {
        val path = f.absolutePath
        if (probePath == path && probeVal != null) return probeVal
        val sp = ctx.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        sp.getString(probePersistKey(path), null)?.let { tag ->
            val p = tag.split('|', limit = 6)
            if (p.size >= 6 && p[0] == f.length().toString() && p[1] == f.lastModified().toString())
                return GgufProbe(p[2], p[3].toIntOrNull() ?: -1, p[4] == "1", p[5], cached = true)
        }
        if (nativeTag == null) return null   // 不为「看一眼」付 init 的代价（释放段库 + backendInit）
        val fresh = probeGguf(path) ?: return null
        val v = f.length().toString() + "|" + f.lastModified() + "|" + fresh.realQuant + "|" +
            fresh.coverage + "|" + (if (fresh.strictUsable) "1" else "0") + "|" + fresh.detail
        ctx.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(probePersistKey(path), v).apply()
        return fresh
    }

    /** 删除/替换模型文件时清掉它的预读缓存，避免残留脏判定。 */
    fun forgetProbe(ctx: android.content.Context, path: String) {
        ctx.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().remove(probePersistKey(path)).apply()
        if (probePath == path) { probePath = null; probeVal = null }
    }

    /**
     * 「这个文件能不能吃到 HTP」的纯判定，不依赖引擎当前加载了谁。
     * 以 GGUF header 里的实际类型为准，文件名只在读不出时兜底；两者都缺 = null（UI 不下结论）。
     */
    fun quantOkOf(probe: GgufProbe?, fileQuant: String?): Boolean? {
        probe?.let { if (it.coverage >= 0) return it.strictUsable }
        val q = fileQuant ?: return null
        return htpSupportedQuants.any { q.startsWith(it) }
    }

    /**
     * 文件名标签与 ggml 实际类型是否同一回事。K-quant 的 _S/_M/_L 只是混合比例，
     * 实际主类型是 q4_k/q5_k/q6_k，所以 Q4_K_M ↔ Q4_K 算一致，不该报命名错误。
     */
    fun labelsMatch(fileQuant: String?, realQuant: String?): Boolean {
        if (fileQuant == null || realQuant == null) return true   // 信息不全就不报错
        if (fileQuant == realQuant) return true
        return realQuant.endsWith("_K") && fileQuant.startsWith(realQuant)
    }

    /** 当前真正驻留在内存里的模型路径；未加载=null。区别于「用户在列表里选中的文件」。 */
    val loadedPath: String? get() = if (loaded) currentPath else null

    /** 当前已加载模型的量化标识；未加载=null。 */
    val loadedQuant: String? get() = quantOfFile(currentPath)

    /** 从任意 gguf 文件路径（或文件名）取量化标识；取不到=null。 */
    fun quantOfFile(path: String?): String? =
        path?.substringAfterLast('/')?.let { quantOf(it) }

    /**
     * 当前模型能否被 HTP 加速。
     * null=还没加载模型或文件名里读不出量化类型（此时 UI 不应下判断）；
     * false=不在白名单，开 HTP 只会更慢，UI 需要明确提示。
     */
    val htpQuantOk: Boolean?
        get() {
            htpProbeForLoaded?.let { if (it.coverage >= 0) return it.strictUsable }   // 实际类型优先
            val q = loadedQuant ?: return null
            return htpSupportedQuants.any { q.startsWith(it) }                // 文件名兜底
        }

    /** 给 UI 与日志用的一句话后端描述，只报已确认生效的路径。 */
    val backendDesc: String
        get() = when {
            nativeTag == null -> "未加载"
            htpActive -> "HTP + OpenCL + CPU"
            gpuCapable -> "OpenCL + CPU"
            else -> "CPU（变体 $nativeTag）"
        }

    private fun isQualcommSoC(): Boolean {
        val mfr = runCatching { Build.SOC_MANUFACTURER }.getOrNull().orEmpty()
        val hard = runCatching { Build.HARDWARE }.getOrNull().orEmpty()
        if (mfr.contains("qualcomm", true) || hard.startsWith("qcom", true)) return true
        // API<31 没有 Build.SOC_*（NoSuchFieldError）/ 部分 ROM 填 unknown：退回 /proc/cpuinfo
        return runCatching { java.io.File("/proc/cpuinfo").readText() }
            .getOrDefault("").contains("Qualcomm", true)
    }

    /** 把 dlopen 报错翻译成人话，并给出下一步动作。 */
    private fun diagnoseInitFailure(msg: String?) {
        val m = msg ?: return
        when {
            m.contains("libcdsprpc") ->
                uiLog("[提示] 加载到了高通专用变体但系统没有 libcdsprpc.so：非高通机型本应选 dotprod/i8mm 变体，请把本条日志连同「native 变体」行一起反馈开发者")
            m.contains("libOpenCL") ->
                uiLog("[提示] OpenCL 库缺失：GPU/HTP 变体不可用（纯 CPU 变体不依赖此库），请连同「native 变体」行反馈")
            m.contains("cannot locate symbol") ->
                uiLog("[提示] 系统 GPU 驱动缺少所需 OpenCL 符号，请把本条日志反馈开发者")
        }
    }

    /** 确保 native 引擎就绪；onCreate 的 init 失败时用缓存 Context 自动重试。 */
    @Synchronized
    fun ensureReady(): Boolean {
        if (loaded) return true
        val c = appCtx ?: return false
        return init(c) // 不显式传值，统一按 HTP 开关（SharedPreferences）决策
    }

    /** 把 assets/ggml-hexagon 下的段库释放到 filesDir/htp 并设置 ADSP_LIBRARY_PATH。 */
    private fun prepareHtpLibs(context: android.content.Context) {
        val ctx = context.applicationContext
        val dir = java.io.File(ctx.filesDir, "htp")
        if (!dir.exists()) dir.mkdirs()
        // 关键修正：段库指纹不一致时，先删旧文件再重新提取。
        // 原逻辑只在「文件不存在或长度为 0」时提取，等于把第一代 skel 永久钉死在设备上——
        // 换过 assets 也白换，覆盖安装后 DSP 仍加载老段库。
        // 老 skel 缺 htp_iface_hwinfo（典型报错：HTP0 failed to query hwinfo (0x80000414)），
        // host 只能按默认 vtcm / 线程数去切 op-batch，DSP 执行首个 batch 即失联 →
        // ggml-hexagon.cpp:1583 dspqueue_read failed 0x0000002e → abort。HTP 崩溃根因即此。
        val stamp = java.io.File(dir, ".asset-stamp")
        val want = "htp-v1.12.2-hwinfo-1" // 更换 assets/ggml-hexagon 下任一 skel 时必须同步改这里
        if (runCatching { stamp.readText().trim() }.getOrDefault("") != want) {
            for (name in HTP_LIBS) runCatching { java.io.File(dir, name).delete() }
            Log.i(TAG, "htp skel 指纹变更为 $want，已清理旧段库待重新提取")
        }
        for (name in HTP_LIBS) {
            val out = java.io.File(dir, name)
            if (!out.exists() || out.length() == 0L) {
                try {
                    ctx.assets.open("ggml-hexagon/$name").use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                    android.system.Os.chmod(out.absolutePath, 0b111101101) // 0755
                } catch (e: Exception) {
                    Log.w(TAG, "skip $name: ${e.message}") // 某些构建可能只带部分版本
                }
            }
        }
        // 指纹落盘：无论个别版本是否缺失都写，避免每次启动重复删除重提。
        runCatching { stamp.writeText(want) }
        try {
            android.system.Os.setenv("ADSP_LIBRARY_PATH", dir.absolutePath, true)
            // 不在 native 侧硬编码以下两项，回到上游默认：
            //   · NDEV：曾用 4（依据是 ndev 过大时报 failed to reserve new session），
            //     但上游默认本就是 1，且多出来的 HTP1..N 若不接算子只占资源。
            //   · VERBOSE：常开会把日志撑到几十 MB 量级并干扰速度观测，故不默认打开；
            //     排查时通过 LM_GGML_HEXAGON_VERBOSE=1 临时开启。
            Log.i(TAG, "ADSP_LIBRARY_PATH=${dir.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "setenv failed: ${e.message}")
        }
    }

    /** 加载 GGUF 模型并建上下文。返回错误信息，null=成功。 */
    fun loadModel(
        path: String,
        nGpuLayers: Int = 99,
        nCtx: Int = 4096,
        nThreads: Int = 4,
        flashAttn: Boolean = false,
        useMmap: Boolean = true,
        cacheK: Int = 0,
        cacheV: Int = 0,
        parallelN: Int = 0,
        batchSize: Int = 0,
        ubatchSize: Int = 0
    ): String? {
        // 未初始化不再抛 IllegalStateException（后台线程未捕获 → 整机闪退），
        // 改为自动重试 init，仍失败则把原因作为错误字符串返回给 UI 显示。
        if (!ensureReady()) {
            return "引擎初始化失败：${lastInitError ?: "native 库尚未加载（无可用 Context）"}"
        }
        // 模型库支持切换：加载新路径前先卸载旧模型，避免泄漏旧上下文
        if (hasModel) {
            nativeFreeSampler()
            nativeUnloadModel()
            currentPath = null
        }
        // i8mm/dotprod/base 是纯 CPU 变体，里面根本没有 OpenCL/Hexagon 后端；
        // 硬传 n_gpu_layers>0 只会让 native 报"unknown backend device"，这里直接归零并说明原因。
        var gpuLayers = nGpuLayers
        if (!gpuCapable && gpuLayers > 0) {
            uiLog("[提示] native 变体 ${nativeTag ?: "未知"} 无 GPU/NPU 后端（只有 hexagon 变体带 OpenCL+HTP），gpu layers 归零，按纯 CPU 运行")
            gpuLayers = 0
        }
        // 大 ctx + OpenCL 提前告警（compute buffer 随 ctx 平方级膨胀）
        if (gpuLayers > 0 && nCtx > 32768) {
            uiLog("提示：ctx=$nCtx > 32768，OpenCL compute buffer 需要超大显存，可能分配失败（失败将自动回退纯 CPU）")
        }
        // 判定源改为**先读 GGUF header 拿实际类型**（不加载权重，毫秒级），文件名只做兜底。
        // 这样即使文件名标错（或压根没写量化），首遍加载就能判对，不用「加载→发现没生效→重载」。
        val quant = quantOf(path.substringAfterLast('/'))
        val probe = probeGguf(path)
        val mismatch = probe != null && !labelsMatch(quant, probe.realQuant)
        if (probe != null) {
            uiLog("[HTP判定] 实际类型 ${probe.realQuant}：${probe.detail} → ${if (probe.strictUsable) "矩阵乘可上 NPU" else "矩阵乘上不了 NPU"}")
            if (mismatch)
                uiLog("⚠ 文件名写的是 $quant，实际是 ${probe.realQuant}；已按实际类型判定，不必为此重新加载")
        }
        // 加载前先清计数，保证下面的 [HTP参与] 摘要只描述这一次加载
        HtpProbe.reset()
        val quota = npuQuotaPct()
        var err = if (nativeLoadModel(path, gpuLayers, nCtx, nThreads, flashAttn, useMmap, cacheK, cacheV, parallelN, batchSize, ubatchSize, quota)) null
        else "加载失败（GPU 路径，详见上方日志）"
        // GPU/OpenCL 分配失败自动回退纯 CPU 重试（native 失败路径已清理干净，可安全重试）
        if (err != null && gpuLayers > 0) {
            uiLog("GPU(n_gpu_layers=$gpuLayers) 加载失败，自动回退纯 CPU 重试…")
            HtpProbe.reset()   // 回退后权重重分，摘要要以最终这次为准
            err = if (nativeLoadModel(path, 0, nCtx, nThreads, flashAttn, useMmap, cacheK, cacheV, parallelN, batchSize, ubatchSize, NPU_QUOTA_AUTO)) {
                uiLog("纯 CPU 加载成功（如需 GPU：减小 ctx 或降低 GPU 层数后再加载）")
                null
            } else "模型加载失败（GPU 与 CPU 均失败：检查 GGUF 完整性 / ctx 是否超出可用内存）"
        }
        // 一行判定本轮 HTP 到底有没有参与——"没崩"本身不是结论，
        // HTP 被踢出计算图后的"没崩"更不是。
        uiLog(HtpProbe.summary())
        HtpProbe.measured().takeIf { it.isNotEmpty() }?.let { uiLog("${LogFileStore.TAG_HTP_MEASURED} $it") }
        // 份额与设备池单独一行：只报实际观测到的值，不写预期。
        val sharePct = HtpProbe.npuSharePct()
        val devPool = HtpProbe.poolDesc()
        if (sharePct >= 0 || devPool.isNotEmpty())
            uiLog("[HTP配额] NPU层份额=" + (if (sharePct < 0) "待实测" else "$sharePct%") +
                    " ｜ HTP在池下标=#" + HtpProbe.htpPoolIndex() +
                    (if (devPool.isEmpty()) "" else " ｜ $devPool"))
        if (err == null) currentPath = path
        return err
    }

    fun modelDesc(): String = nativeModelDesc()
    fun contextSize(): Int = nativeCtxSize()
    fun contextUsed(): Int = nativeCtxUsed()

    /** 取模型内置 chat 模板（Jinja 原文），空串=未知。 */
    fun chatTemplate(): String = nativeChatTemplate()

    /** 用模型模板渲染多轮对话。roles/contents 等长；失败回落到 ChatML。 */
    fun applyChatTemplate(messages: List<Pair<String, String>>, addAss: Boolean = true): String {
        val roles = Array(messages.size) { messages[it].first }
        val contents = Array(messages.size) { messages[it].second }
        val rendered = nativeApplyChatTemplate(chatTemplate(), roles, contents, addAss)
        if (rendered != null) return rendered
        // 回落：ChatML
        val sb = StringBuilder()
        for ((r, c) in messages) sb.append("<|im_start|>").append(r).append('\n').append(c).append("<|im_end|>\n")
        if (addAss) sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    /** 配置采样链。temp<=0 视为贪心。topK<=0 关闭；repPenalty>1 启用重复惩罚（近 penaltyN 个 token）。 */
    fun newSampler(temp: Float, topP: Float, minP: Float, seed: Long = 0,
                   topK: Int = 0, repPenalty: Float = 1f, penaltyN: Int = 0,
                   freqPenalty: Float = 0f, presencePenalty: Float = 0f): Boolean =
        nativeNewSampler(temp, topP, minP, topK, repPenalty, penaltyN, freqPenalty, presencePenalty, seed)

    /**
     * 开始一轮生成：清 KV -> tokenize(含 BOS) -> 分块 prefill。
     * 返回错误信息，null=成功。
     */
    // ---- 生成计时 ----
    // 此前 App 完全不记 tokens/s：任何 A/B（HTP / OpenCL / CPU、不同 ngl）只能比"崩没崩"，
    // 比不了"快不快"，实验结论缺半边。不用改 native——nativeStep 每次恰好解码 1 个 token
    // （llama_jni.cpp: llama_batch_get_one(&tok,1) + llama_decode），按调用次数计数即 token 数；
    // prefill 耗时＝nativeStartCompletion 这一次调用的墙上时间。
    private var genPromptTok = 0
    private var genPrefillMs = 0L
    private var genTok = 0
    private var genT0 = 0L
    private var genActive = false

    fun startCompletion(prompt: String, maxTokens: Int = 256): String? {
        val t0 = android.os.SystemClock.elapsedRealtime()
        if (!nativeStartCompletion(prompt, maxTokens)) {
            genActive = false
            return "prefill 失败（prompt 过长或 decode 出错）"
        }
        val t1 = android.os.SystemClock.elapsedRealtime()
        genPromptTok = contextUsed().coerceAtLeast(0)
        genPrefillMs = (t1 - t0).coerceAtLeast(1L)
        genTok = 0; genT0 = t1; genActive = true
        uiLog("${LogFileStore.TAG_TIME} prefill %d tok / %dms = %.1f tok/s".format(
                genPromptTok, genPrefillMs, genPromptTok * 1000.0 / genPrefillMs))
        return null
    }

    /** 采样一步并返回一个完整 UTF-8 片段；null=结束。 */
    fun step(): String? {
        val s = nativeStep()
        if (s != null) lastProgressAt = System.currentTimeMillis()
        if (genActive) { if (s != null) genTok++ else finishGeneration() }
        return s
    }

    /** 一轮生成收尾：decode 速度连同引擎/HTP 状态一起落日志，便于跨配置对比。 */
    private fun finishGeneration() {
        genActive = false
        val ms = (android.os.SystemClock.elapsedRealtime() - genT0).coerceAtLeast(1L)
        uiLog("${LogFileStore.TAG_TIME} decode %d tok / %dms = %.1f tok/s ｜ 引擎=%s HTP=%s".format(
                genTok, ms, genTok * 1000.0 / ms, nativeTag ?: "?", htpRequested))
    }

    /** 中断当前生成（下一次 step 返回 null）。 */
    fun abort() = nativeAbort()

    /** 释放采样器/上下文/模型。 */
    fun unload() {
        if (!loaded) return
        genActive = false   // 换模型/卸载时复位，避免旧计数串进下一轮
        nativeFreeSampler()
        nativeUnloadModel()
        currentPath = null
        // 卸载要连预读状态一起作废，否则 UI 会继续显示上一个模型的信息（误导）
        probePath = null
        probeVal = null
    }

    // ---- native ----
    /** libcpufeat.so 提供，返回 [dotprod, i8mm, hwcap, hwcap2]。 */
    @JvmStatic private external fun cpuCaps(): LongArray
    @JvmStatic private external fun backendInit(logBridge: Any)
    /** 只读 GGUF header 预探真实量化，返回 {主类型, HTP受理%, HTP可算"1"/"0", 说明}。 */
    @JvmStatic private external fun nativeProbeGguf(path: String): Array<String>

    @JvmStatic private external fun nativeLoadModel(
        path: String, nGpuLayers: Int, nCtx: Int, nThreads: Int,
        flashAttn: Boolean, useMmap: Boolean, cacheK: Int, cacheV: Int,
        parallelN: Int, batchSize: Int, ubatchSize: Int, npuQuotaPct: Int): Boolean
    @JvmStatic private external fun nativeModelDesc(): String
    @JvmStatic private external fun nativeCtxSize(): Int
    @JvmStatic private external fun nativeCtxUsed(): Int
    @JvmStatic private external fun nativeChatTemplate(): String
    @JvmStatic private external fun nativeApplyChatTemplate(
        tmpl: String, roles: Array<String>, contents: Array<String>, addAss: Boolean): String?
    @JvmStatic private external fun nativeNewSampler(temp: Float, topP: Float, minP: Float, topK: Int, repPenalty: Float, penaltyN: Int, freqPenalty: Float, presencePenalty: Float, seed: Long): Boolean
    @JvmStatic private external fun nativeStartCompletion(prompt: String, maxTokens: Int): Boolean
    @JvmStatic private external fun nativeStep(): String?
    @JvmStatic private external fun nativeAbort()
    @JvmStatic private external fun nativeFreeSampler()
    @JvmStatic private external fun nativeUnloadModel()
}
