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

    // ---- 崩溃探针 ----
    // 为什么要有它：这条链路上的闪退此前取证不到 —— 日志回调在 abort 前会丢，
    // JVM 的 UncaughtExceptionHandler 覆盖不到 native abort，客户端只看到连接被重置。
    // 探针把「谁进去了、走到哪一步、崩在哪」写进 native 自己持有的 fd，进程被 abort
    // 杀死时内核缓冲里的数据仍在。
    private const val PREFS_PROBE = "probe_prefs"
    private const val PREF_PROBE_ON = "probe_on"
    /** 探针是否由用户打开；打开后所有日志一律落 native 探针文件（含全量原生日志）。 */
    @Volatile var probeEnabled: Boolean = false
        private set
    @Volatile var probeFile: java.io.File? = null
        private set
    @Volatile private var probeAttached = false

    fun loadProbeSetting(ctx: android.content.Context) {
        probeEnabled = ctx.applicationContext
            .getSharedPreferences(PREFS_PROBE, android.content.Context.MODE_PRIVATE)
            .getBoolean(PREF_PROBE_ON, false)
    }

    fun setProbeEnabled(ctx: android.content.Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS_PROBE, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_PROBE_ON, on).apply()
        probeEnabled = on
    }

    /**
     * 挂载 native 探针。**必须在 loadNative() 之后**调用，且必须在 [backendInit] 之前。
     *
     * 为什么必须在 loadNative 之后：`nativeProbeInit` 定义在 llama_jni.cpp、
     * 编进 `libllmjni_<tag>.so`，**不在** libcpufeat.so 里（后者只导出 cpuCaps）。
     * 库没 load 就调它，JVM 只会给一个 UnsatisfiedLinkError。
     *
     * 但顺序对了不等于挂得上，所以**不要把 UnsatisfiedLinkError 当成单一病因**：
     * 覆盖安装（Android 不替换已存在的 lib/arm64-v8a 下的 .so）、CMake 判 up-to-date 跳过
     * native 重编、变体名与实际产物对不上，都会给出一模一样的报错。三种成因的区分办法：
     *   · 顺序/变体名问题 → `[引擎] native 变体=...` 那行在不在、变体名对不对；
     *   · .so 没换新       → 搜 logcat `JNI_OnLoad 已进入`（每次 load 到这个库都会打一行）；
     *   · 符号确实不在库里 → 上面两行都在，仍报 UnsatisfiedLinkError。
     * 另外 native 侧现在会自行自举探针（JNI_OnLoad），所以**即使这一跳失败**，
     * probe-native.log 里也应该有 `[boot]` 开头的内容 —— 一条都没有，才说明 native 完全没跑起来。
     *
     * 为什么必须在 backendInit 之前：backendInit 里的 `llama_log_set` 只选一次 sink，
     * 且探针要能接住 backendInit 自身与后续模型加载、渲染、解析各阶段的崩溃。
     *
     * 代价说清楚：探针接不住 `dlopen` 阶段（.so 静态初始化）的崩溃 —— 那需要先 load 才能挂，
     * 循环依赖。但那个阶段崩了 App 根本起不来，是另一个现象；而真正出问题的
     * 模型加载 / prompt 渲染 / 解析 / 生成四个阶段全在覆盖范围内。
     *
     * @return null=挂载成功；否则为失败原因（用于 UI 直接提示，不静默失败）
     */
    fun startProbe(ctx: android.content.Context): String? {
        if (!probeEnabled) return null
        return try {
            val dir = java.io.File(ctx.applicationContext.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            probeFile = java.io.File(dir, "probe-native.log")
            // nativeProbeInit 在 libllmjni_<tag>.so 里（不在 libcpufeat.so），
            // 库没加载就调只会拿到 UnsatisfiedLinkError。这里先挡一道，把原因说清楚。
            //
            // 注意这一跳失败**不代表取证失败**：native 侧在 JNI_OnLoad 里会自行自举探针，
            // 所以日志里「Kotlin 挂载失败」但 probe-native.log 有 `[boot]` 内容是正常组合
            // —— 那说明崩点就在 Kotlin → native 之间，而不是 native 内部。
            if (nativeTag == null) return "native 库尚未加载（loadNative 未成功），探针无处挂载"
            probeAttached = nativeProbeInit(dir.absolutePath, true)
            if (!probeAttached) return "nativeProbeInit 返回 false（文件打不开或无写权限）"
            probeMark("Kotlin 侧已挂载探针，${
                java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())} abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
            null
        } catch (t: Throwable) {
            probeAttached = false
            "探针挂载异常：${t.javaClass.simpleName}: ${t.message}"
        }
    }

    val probeAttachedNow: Boolean get() = probeAttached

    /** 探针打点：写 native fd，同时镜像进 Kotlin 日志文件（进程没崩时便于一次导出看完）。 */
    fun probeMark(msg: String) {
        if (!probeEnabled) return
        try { nativeProbeMark(msg) } catch (_: Throwable) {}
        LogFileStore.append("[probe] $msg")
    }

    /** 探针原始文本（native 直写的那份，含信号现场）。 */
    fun probeText(): String = runCatching {
        probeFile?.let { if (it.exists()) it.readText() else "" } ?: ""
    }.getOrDefault("")

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

    /** 探针模式下 ring 放宽：原始 token 流不再折叠，300 行窗口一眨眼就满 */
    private const val PROBE_RING = 2000

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
        // 探针开启时**不过滤噪音、不折叠重复**：崩溃前最后那几条恰恰经常是
        // llama 逐 token 打的 "."，折叠会把它们挤掉。全量落 native 探针文件。
        if (probeEnabled) {
            lastProgressAt = System.currentTimeMillis()
            try { nativeProbeMark(t) } catch (_: Throwable) {}
            // 同时也进内存 ring，UI 日志页能看到（不再落 Kotlin 会话文件，避免双份）
            synchronized(logRing) {
                logRing.addLast(t)
                while (logRing.size > PROBE_RING) logRing.removeFirst()
            }
            HtpProbe.observe(t)
            try { logSink?.invoke(t) } catch (_: Exception) {}
            return
        }
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
            // 探针只在 loadNative **成功之后**才挂得上（nativeProbeInit 在 llmjni_<tag> 里），
            // 又必须赶在 backendInit 之前（backendInit 只选一次 log sink）。
            // 这两个约束把挂载点唯一钉死在这里；见 startProbe 的注释。
            startProbe(context)?.let { probeMark("!! 探针挂载失败：$it") }
            probeMark("即将 backendInit")
            backendInit(logBridge)
            probeMark("backendInit 成功")
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
        probeMark("loadNative 开始（forceHtp=$forceHtp）")
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
                probeMark("即将 loadLibrary llmjni_$tag")
                System.loadLibrary("llmjni_$tag")
                probeMark("loadLibrary llmjni_$tag 成功")
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

    /**
     * 串行化所有「动模型状态」的操作（加载 / 卸载 / 换模型）。
     *
     * 为什么非要它：0.9.70 那次「初次安装后乱码」的日志里，同一个进程里
     * `>> loadModel` 出现了 **两次**，分别来自 tid=8596 与 tid=8695，间隔 4 秒、
     * 各自都跑满了约 24 秒，两条 `<< loadModel` 才前后脚返回。
     * 之后那一轮生成 300 token 全是词表碎片（` patribora@akar站©站站()||]>=...`），
     * 且**正好卡在 max_tokens 上限**〔n=300＝max_tokens〕——典型的长出垃圾然后撞上限。
     * 卸载再重新加载（只剩一条 loadModel）后，同一个模型、同一个请求立刻正常。
     *
     * 结论：这不是模型/解码器坏了，是**两个加载同时动同一份 native 状态**。
     * 加载耗时 20 秒以上，而 UI 早把「加载模型」按钮重新置灰前的窗口、服务启动时的
     * 自动挂载、切后台回来后的恢复，都可能在同一时刻各提一次加载。
     *
     * 互斥只保护「状态迁移」，不防抖（不合并请求）：后到的请求排队执行，
     * 语义仍是"最后调用的那次生效"。这样既不会并发，也不会把用户的加载请求悄悄吞掉——
     * 吞掉请求会让界面显示"已加载"而 native 里其实还是旧模型，比并发更难查。
     */
    private val loadLock = Any()

    /** 正在加载中：用于 UI 与日志判据（日志里必须能一眼看出"已有一次加载在跑"）。 */
    @Volatile var loadInFlight: Boolean = false
        private set

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
    ): String? = synchronized(loadLock) {
        if (loadInFlight) uiLog("[加载] 已有一次加载在进行中，本次排队等待其结束（并发加载会把 native 状态弄脏，见 loadLock 注释）")
        loadInFlight = true
        try {
            loadModelLocked(path, nGpuLayers, nCtx, nThreads, flashAttn, useMmap,
                cacheK, cacheV, parallelN, batchSize, ubatchSize)
        } finally {
            loadInFlight = false
        }
    }

    /** [loadModel] 的实体；调用方已持有 [loadLock]，这里不再加锁（避免重入与锁序问题）。 */
    private fun loadModelLocked(
        path: String,
        nGpuLayers: Int,
        nCtx: Int,
        nThreads: Int,
        flashAttn: Boolean,
        useMmap: Boolean,
        cacheK: Int,
        cacheV: Int,
        parallelN: Int,
        batchSize: Int,
        ubatchSize: Int
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

    /**
     * 带工具定义渲染 prompt。tools 为空数组、无模型或渲染失败时返回 null，调用方回落 [applyChatTemplate]。
     *
     * 必须走这条路径才能支持工具调用：`llama_chat_apply_template` 只吃 role/content，
     * 工具清单进不了 prompt（工具定义是写给模型看的，不入 prompt 模型就不知道自己有什么函数可用），
     * 于是永远吐不出 tool_calls。
     *
     * @param toolsJson OpenAI 格式的 tools 数组（`[{"type":"function","function":{...}}]`）
     * @param toolChoice "auto" / "required" / "none"。
     *   注意：**不要传 null**。JNI 只能把 null 变成空串 `""`，而 vendor 库对空串是
     *   throw `std::invalid_argument`（不是返回 AUTO），异常穿 JNI 即 SIGABRT。
     *   请求侧 [ToolCalls.parseToolChoice] 已保证非 null；此处的 null 兜底仍然保留，
     *   native 侧也另有一道同源兜底（两道防线都要有）。
     */
    fun applyChatTemplateWithTools(
        messages: List<Pair<String, String>>,
        toolsJson: String,
        toolChoice: String? = null,
        parallelToolCalls: Boolean = true,
        addAss: Boolean = true
    ): String? {
        if (toolsJson.isBlank() || toolsJson == "[]" || toolsJson == "null") return null
        val roles = Array(messages.size) { messages[it].first }
        val contents = Array(messages.size) { messages[it].second }
        val tmpl = chatTemplate()
        probeMark("[工具] 即将渲染带 tools 的 prompt：msgs=${messages.size} tools_len=${toolsJson.length} " +
                "chat_template_len=${tmpl.length}（=0 表示模型没带模板）choice=${toolChoice ?: "auto"}")
        return try {
            val r = nativeApplyChatTemplateTools(tmpl, roles, contents, toolsJson, toolChoice, parallelToolCalls, addAss)
            probeMark("[工具] 带 tools 渲染返回：${if (r == null) "null（回落无工具路径）" else "len=${r.length}"}")
            r
        } catch (t: Throwable) {
            uiLog("[工具] 模板渲染失败，回落无工具路径：${t.message}")
            null
        }
    }

    /**
     * 从模型输出解析 tool_calls。
     *
     * 返回 Pair(content, toolCallsJson)：toolCallsJson 为 null 表示没有工具调用。
     * 语法随模板变化（Qwen 的 `<tool_call>`、Llama 的 `[TOOL_CALLS]` 等），
     * 由 native 侧 `common_chat_parse` 按模板归一，Kotlin 侧不做正则猜测。
     */
    fun parseToolCalls(text: String, toolsJson: String, addAss: Boolean = true): Pair<String, String?>? {
        if (text.isEmpty()) return null
        val tmpl = chatTemplate()
        probeMark("[工具] 即将调用 nativeParseToolCalls：text_len=${text.length} " +
                "tools_len=${toolsJson.length} tmpl_len=${tmpl.length} add_ass=${addAss} " +
                "text_head=${text.take(200).replace("\n", "\\n")}")
        // addAss 必须与**渲染时**同一个值：解析器是按模板推导的 PEG，而 cp.generation_prompt
        // （会被 common_chat_parse 前拼到输入上、进而决定 PEG 根节点能否匹配）随
        // add_generation_prompt 变化。两边不一致 = 根节点前缀对不上 = tool_calls 恒为 0。
        val raw = try { nativeParseToolCalls(text, toolsJson, tmpl, addAss) } catch (t: Throwable) {
            probeMark("[工具] nativeParseToolCalls 抛出异常：${t.javaClass.name}: ${t.message}")
            uiLog("[工具] 解析失败：${t.message}")
            null
        } ?: run { probeMark("[工具] nativeParseToolCalls 返回 null"); return null }
        probeMark("[工具] nativeParseToolCalls 返回 len=${raw.length} head=${raw.take(200)}")
        if (raw.isEmpty() || raw == "null") return null
        return try {
            val o = org.json.JSONObject(raw)
            val calls = o.optJSONArray("toolCalls")
            if (calls == null || calls.length() == 0) null
            else o.optString("content", "") to calls.toString()
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 配置采样链，顺序与 llama.cpp 官方示例一致：
     * `penalties -> top_k -> top_p -> min_p -> temp -> dist`。
     * temp<=0 视为贪心（其余采样参数被忽略）。topK<=0 关闭；topP/minP<=0 或 >=1 关闭。
     *
     * 入参由 [SamplingParams.check] 校验过，native 侧不再重复兜底：
     * NaN/Inf、越界值都会让 llama.cpp 的采样器行为未定义。
     */
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

    /** 释放采样器/上下文/模型。与 [loadModel] 共用同一把锁：卸载与加载交错同样会污染 native 状态。 */
    fun unload() = synchronized(loadLock) {
        if (!loaded) return@synchronized
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
    /**
     * native 崩溃探针开关。on=true 时 native 侧自己开文件直写（不经 JVM、不经 llama log 系统），
     * 并把 SIGSEGV/SIGABRT/... 的现场写进同一文件；开启期间所有 JVM 回调都被旁路，
     * 以免探针自身成为观测者效应。返回是否挂上。
     */
    @JvmStatic private external fun nativeProbeInit(dir: String, on: Boolean): Boolean

    /** Kotlin 侧埋点：把「要开始做 X」写进 native 探针文件；探针关闭时是空操作。 */
    @JvmStatic private external fun nativeProbeMark(msg: String)
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
    /** 带 tools 的模板渲染：工具定义进 prompt（旧接口不认 tools，工具调用永远不触发）。 */
    @JvmStatic private external fun nativeApplyChatTemplateTools(
        tmpl: String, roles: Array<String>, contents: Array<String>,
        toolsJson: String?, toolChoice: String?, parallelToolCalls: Boolean, addAss: Boolean): String?
    /**
     * 从输出文本解析 tool_calls，返回 JSON；无工具调用或解析失败返回 "null"。
     *
     * 必须传入**渲染时的同一个模板**：解析器是按模板推导出的 PEG，两边不一致会解析错。
     */
    @JvmStatic private external fun nativeParseToolCalls(
        text: String, toolsJson: String?, tmpl: String, addAss: Boolean): String
    @JvmStatic private external fun nativeNewSampler(temp: Float, topP: Float, minP: Float, topK: Int, repPenalty: Float, penaltyN: Int, freqPenalty: Float, presencePenalty: Float, seed: Long): Boolean
    @JvmStatic private external fun nativeStartCompletion(prompt: String, maxTokens: Int): Boolean
    @JvmStatic private external fun nativeStep(): String?
    @JvmStatic private external fun nativeAbort()
    @JvmStatic private external fun nativeFreeSampler()
    @JvmStatic private external fun nativeUnloadModel()
}
