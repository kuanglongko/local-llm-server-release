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

    /**
     * 生成循环内**除了 `LlmEngine.step()` 之外**唯一该做的收尾判定：本轮是否已被取消。
     *
     * 为什么收在引擎里而不是让每个调用方各自查标志位：
     * 「谁在跑」与「取消打给谁」必须对同一个对象生效（见 [RequestCancel] 文件头，
     * 那里记着「迟到的取消停掉别人的轮次」这个真实事故）。把归属状态放在引擎内，
     * 任何一条新入口（HTTP / UI / 以后可能有的别的）都不会漏登记、也不会登记错。
     *
     * 生成期**不逐毫秒轮询**取消标志：`nativeStep()` 一步就是一个 token，粒度天然
     * 是一步一判；本轮已经结束（[step] 返回 null）之后再取消也不会有人读它。
     */
    val currentCancel: RequestCancel.Token? get() = RequestCancel.currentToken()

    /**
     * 引擎此刻是否在跑一轮生成（含 HTTP 生成与 App 内聊天）。
     *
     * 与 [RequestCancel.active] 取同一个真值：取消归属就是「这一轮正在跑」的登记。
     * 两者各算各的，迟早会出现「`/v1/abort` 说停了、忙检查还说在跑」这种对不上的状态。
     *
     * **覆盖范围是「prefill 成功 → 收尾」**（登记与摘除各在一端）。更早的
     * 「请求已受理、还在渲染 prompt」那段不在其中 —— 那一段由 HttpApi 的 `busy`
     * 覆盖，忙检查必须两半都看（见 `HttpApi.isGenerating` 的注释）。
     */
    val isGenerating: Boolean get() = RequestCancel.active

    /**
     * 生成开始：登记取消归属并返回本轮 token，交给调用方在循环里逐 step 判定。
     *
     * 必须在持 [genLock] 时调用（[startCompletion] 之后立刻）。**不**放进
     * [startCompletion] 里做，是因为 prefill 失败时那一轮根本没跑起来，
     * 此时登记了就得有人负责摘除，多一条容易漏的路径。
     */
    fun beginCancelable(): RequestCancel.Token {
        val t = RequestCancel.enter()
        // 把本轮的 native 编号绑进 token —— 取消打回 native 时要靠它核对归属。
        // 取值时机正确性：调用方在 `startCompletion` **成功后**、仍在 `genLock` 内
        // 调本函数，而 native 的编号在 startCompletion 入口自增。单实例引擎 +
        // 生成循环持 `genLock`，所以此刻 `nativeCurrentEpoch()` 就是本轮编号，
        // 不存在"取到别人编号"的窗口（拿不到 ctx/smpl 编不出的场景见 native 侧注释）。
        t.bindNativeEpoch(runCatching { nativeCurrentEpoch() }.getOrDefault(0L))
        return t
    }

    /** 生成结束（正常/异常/取消任一）：摘除取消归属。重复调用安全。 */
    fun endCancelable(t: RequestCancel.Token) = RequestCancel.leave(t)

    /**
     * 请求取消**当前正在生成的那一轮**，并把归属一路送到 native。
     *
     * 这是**唯一**的取消入口（`/v1/abort`、心跳/断连探测、App 停止按钮都走它）：
     *   · 没有轮次在跑 -> 返回 null，什么也不做（"取消了不存在的请求"必须可判定）；
     *   · 有轮次在跑   -> 标记 token + 带编号调 `nativeAbort`。
     *
     * 为什么必须收在一处：Kotlin 侧的 `cancel.requested` 只覆盖"循环自己查标志"
     * 这一条路径（`kotlin` 循环在 `step()` 之前查），**管不到 prefill** ——
     * prefill 是一次阻塞的 native 调用，唯一能打断它的是 native 的 `S.abort`。
     * 此前没有任何路径把取消送到 native，于是"取消"在 prefill 阶段完全无效，
     * 表现为「点了停止，还要等这一大段 prompt 算完」。
     */
    fun requestAbort(): RequestCancel.Token? {
        val t = RequestCancel.cancelCurrent() ?: return null
        abortRound(t)
        return t
    }

    /**
     * 已持有本轮 token 的调用方（心跳 / 断连探测：它们按"这一次探测判定离开"自己
     * `cancel.request()` 过）在这里补上"送到 native"那一跳。
     *
     * 与 [requestAbort] 分开是因为归属已经确定：重复 `cancelCurrent()` 会踩到
     * "取消一次不成、再取消一次成功"的错觉（第二次会打到**下一个**轮次上）。
     */
    fun abortRound(t: RequestCancel.Token) {
        runCatching { nativeAbort(t.nativeEpoch) }
    }

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
     * （前提：用户确实开了探针。自举**只记不写**，文件只由本函数这次调用建立；
     *  用户没开时自举事件只进 logcat，不建文件、不落盘 —— 别把它读成"native 没跑"。）
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
        return try {
            val dir = java.io.File(ctx.applicationContext.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            probeFile = java.io.File(dir, "probe-native.log")
            // nativeProbeInit 在 libllmjni_<tag>.so 里（不在 libcpufeat.so），
            // 库没加载就调只会拿到 UnsatisfiedLinkError。这里先挡一道，把原因说清楚。
            //
            // 注意这一跳失败**不代表取证失败**：native 侧在 JNI_OnLoad 里会自行跑一次
            // 自举（**只记不写**），所以日志里「Kotlin 挂载失败」但 probe-native.log 有
            // `[boot]` 内容是正常组合 —— 那说明崩点就在 Kotlin → native 之间，而不是 native 内部。
            if (nativeTag == null) return if (probeEnabled) "native 库尚未加载（loadNative 未成功），探针无处挂载" else null
            // ⚠ 「关」也必须**显式**告知 native。
            // 此前 !probeEnabled 时直接 return，native 侧永远收不到那次 on=false 的调用，
            // 于是它只能靠自举（无条件打开）猜 —— 用户在设置页关掉探针，native 照样
            // 全量落盘并把日志 sink 换成空操作。现在开关在这里有**唯一**的事实来源。
            probeAttached = nativeProbeInit(dir.absolutePath, probeEnabled)
            if (!probeEnabled) return null
            if (!probeAttached) {
                return "nativeProbeInit 返回 false（可能被自举开关属性强关，或文件打不开/无写权限；" +
                    "logcat 搜 `probe:` 与 `probe boot:` 看原因）"
            }
            probeMark("Kotlin 侧已挂载探针，${
                java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())} abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
            null
        } catch (t: Throwable) {
            probeAttached = false
            if (probeEnabled) "探针挂载异常：${t.javaClass.simpleName}: ${t.message}" else null
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

    /**
     * 文件身份 = 路径 + 大小 + mtime，**预读缓存唯一的命中判据**。
     *
     * 只按路径命中的写法会静默用错：模型文件可以被**就地替换**（外部路径重新下载完成、
     * 续传落盘、adb push 覆盖），路径一个字没变而内容全换。此时旧判定会被继续当成
     * 「这个文件的真实量化」，一路写进 [probeGguf] 的返回值、loadModel 的 HTP 判定
     * 与 `[HTP判定]` 日志 —— 表现是"文件名与实际类型不符"的提示指向一个已经不存在的文件。
     *
     * `peekGguf` 的持久缓存**本来**就是 size+mtime 双校验（"文件被替换或续传完成自动失效"），
     * 而内存缓存这一层此前只有路径 —— 两处口径分叉，正是判据漂移的起点。
     * 现在两侧共用本判据，不再各写一份近似。
     */
    private class ProbeIdentity(val path: String, val length: Long, val mtime: Long) {
        override fun equals(other: Any?): Boolean =
            other is ProbeIdentity && other.path == path && other.length == length && other.mtime == mtime
        override fun hashCode(): Int = path.hashCode() * 31 * 31 + length.hashCode() * 31 + mtime.hashCode()
    }

    private fun probeIdentityOf(path: String): ProbeIdentity {
        val f = java.io.File(path)
        return ProbeIdentity(path, f.length(), f.lastModified())
    }

    @Volatile private var probeId: ProbeIdentity? = null
    @Volatile private var probeVal: GgufProbe? = null

    /** 最近一次预读结果；null=读不出（非 GGUF / header 损坏 / 该库无此符号）。 */
    val ggufProbe: GgufProbe? get() = probeVal

    /** 只对「当前已加载模型」成立的预读结果；path 不匹配（换了文件没重载）时返回 null，UI 应忽略。 */
    val htpProbeForLoaded: GgufProbe?
        get() = if (probeId?.path != null && probeId!!.path == currentPath) probeVal else null

    /** 文件名标称与实际类型不符（只看当前已加载模型）。true 时 UI 亮色提示；判定已按实际类型走，不需重载。 */
    val quantMismatch: Boolean?
        get() = htpProbeForLoaded?.let { !labelsMatch(loadedQuant, it.realQuant) }

    /**
     * 预读 GGUF header（不加载权重，毫秒级）。命中内存缓存时**不再打 native**，
     * 命中判据是 [ProbeIdentity]：路径、大小、mtime 三者全同才算同一个文件。
     *
     * 为什么不能只看路径：[probeIdentityOf] 的注释里写了那条静默故障的完整链路 ——
     * 就地替换后的旧判定会一直被当成"这个文件的真实量化"用下去。
     */
    fun probeGguf(path: String): GgufProbe? {
        val id = probeIdentityOf(path)
        if (probeId == id) return probeVal
        val a = try {
            nativeProbeGguf(path)
        } catch (t: Throwable) {
            uiLog("[HTP判定] 预读不可用（${t.javaClass.simpleName}），回退按文件名判断")
            null
        }
        val p = if (a != null && a.size >= 4 && a[0].isNotEmpty() && a[1] != "-1")
            GgufProbe(a[0], a[1].toIntOrNull() ?: -1, a[2] == "1", a[3]) else null
        probeId = id
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
        val id = ProbeIdentity(path, f.length(), f.lastModified())
        if (probeId == id && probeVal != null) return probeVal
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
        if (probeId?.path == path) { probeId = null; probeVal = null }
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
            unloadLocked()
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
        // 加载前先把 repack 档位交给 native（**显式**下发，未设过传 -1 而非 0）：
        // 与 `load_mode` / `devices` 同一课 —— 开关必须落在仓库自己的判定上，
        // 且"没设过"要能与"显式设为 0"区分开，否则用户关掉之后下一轮会静默回到库默认。
        // appCtx 未就绪时传 -1（未设过）：与 npuQuotaPct 的兜底同一个口径，
        // 不读 prefs 比读错 prefs 安全。
        setRepackMode(appCtx?.let { ModelStore.extraBufts(it) } ?: -1)
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

    /**
     * KV 前缀复用的可观测面：`last_reuse`（上一轮复用了多少 token）与
     * `lastPrefillTokens`（上一轮**新算**了多少 token）。
     *
     * 为什么必须暴露到 Kotlin：复用是**纯加速**，它不改变任何接口状态 ——
     * 没有这两个数，"缓存到底有没有生效"就只能去读 native 日志，
     * 而 native 日志默认是关的（探针要人先开）。收到 /health 里之后，
     * 任何一次真实请求都能顺带把缓存命中率读出来。
     */
    val lastReuseTokens: Int get() = runCatching { nativeKvCacheStats()[0] }.getOrDefault(0)
    val lastPrefillTokens: Int get() = runCatching { nativeKvCacheStats()[1] }.getOrDefault(0)

    /**
     * 已经跑完 prefill 的轮次数。与 [kvCacheValid] **必须成对读**：
     * `kvRounds == 0` = 从未跑过任何请求（冷启动）；
     * `kvRounds > 0 && !kvCacheValid` = 跑过、但账本已被作废（abort / 换模型 /
     * prompt 超长）。只看 [kvCacheValid] 的话这两种处境完全同形 ——
     * 一个是正常的冷启动，一个是取消或故障，读 `/health` 的人无从分辨。
     */
    val kvRounds: Int get() = runCatching { nativeKvCacheStats()[2] }.getOrDefault(0)

    /**
     * 本轮已进 KV 账本的生成 token 数（见 [nativeRoundLedgerTokens]）。
     * 供生成循环在取消收尾时记一行"账本里有 X、下发了 Y"，不要用它做判定。
     */
    val roundLedgerTokens: Int get() = runCatching { nativeRoundLedgerTokens() }.getOrDefault(0)

    /**
     * KV 账本是否有效。false = **下一轮必然全量 prefill**（换模型 / 卸载 /
     * 上一轮解码失败 / prompt 超长之后都是这个状态）。
     */
    val kvCacheValid: Boolean get() = runCatching { nativeKvCacheValid() }.getOrDefault(false)

    /**
     * 主动丢弃 prompt 缓存。存在的理由：**显式开关**比"等它自己失效"可控。
     * 用户换了一整套 system prompt / 把模板改了之后，旧前缀本来就匹配不上
     * （复用判据是逐 token 比前缀，不匹配自然不复用），但那时候 KV 里还留着一大段
     * 用不上的数据白占显存 —— 在手机上这直接等于少了几 MB 可用 KV 空间。
     */
    fun resetKvCache() = runCatching { nativeKvCacheReset() }


    /** 取模型内置 chat 模板（Jinja 原文），空串=未知。 */
    fun chatTemplate(): String = nativeChatTemplate()

    /**
     * 用模型模板渲染多轮对话。roles/contents 等长；失败回落到 ChatML。
     *
     * [thinkingOn] 是这一轮**最终**的思考开关（请求覆盖 > 全局默认，见 `ThinkingControl.resolve`），
     * 传给库的 `common_chat_templates_inputs::enable_thinking`。
     *
     * **必须传**：C++ 侧这个字段默认是 `true`，而 MiniCPM5 的模板按它决定生成后缀吐不吐
     * `<think>\n` —— 不传就等于"思考永远开着"，设置页「默认关闭思考」在这类模型上恒不生效
     * （2026-09-19 真机报障的第一个成因）。模板里没有 `enable_thinking` 变量时传下去不改变
     * 渲染结果（LFM2.5），那类由 `ThinkingControl` 的软开关兜底，所以两条路径共用同一个取值。
     */
    fun applyChatTemplate(
        messages: List<Pair<String, String>>,
        addAss: Boolean = true,
        thinkingOn: Boolean = true
    ): RenderedPrompt {
        val roles = Array(messages.size) { messages[it].first }
        val contents = Array(messages.size) { messages[it].second }
        val rendered = nativeApplyChatTemplate(chatTemplate(), roles, contents, addAss, thinkingOn)
        if (rendered != null) return RenderedPrompt.parse(rendered)
        // 回落：ChatML。**回落路径不得声明"思考段已开"** ——
        // ChatML 拼出来的后缀里没有 <think>，模型输出里的 `</think>` 是它自己吐的，
        // 必须由状态机去解析配对；这里若报 openAtStart=true，整段回答会被当思考段吞掉。
        val sb = StringBuilder()
        for ((r, c) in messages) sb.append("<|im_start|>").append(r).append('\n').append(c).append("<|im_end|>\n")
        if (addAss) sb.append("<|im_start|>assistant\n")
        // 回落路径的生成后缀是**宿主自己拼的**那段（见上一行）—— 这里必须显式带上，
        // 否则结构化输出的 grammar 又会退回"库自算的那个尾巴"，与真 prompt 分叉。
        // 值取自 RequestContext.CHATML_GEN_SUFFIX（唯一来源，与上一行逐字一致，
        // 由守卫断言两处字面量相同）；addAss=false 时**必须**是空串而不是 null：
        // 空串 = "确认没有生成后缀"，null = "不知道" —— 后者会让 native 按
        // "有生成后缀"推导 grammar（见 RequestContext.genPromptArg 的注释）。
        return RenderedPrompt(
            sb.toString(), openAtStart = false,
            generationSuffix = if (addAss) RequestContext.CHATML_GEN_SUFFIX else "")
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
        addAss: Boolean = true,
        thinkingOn: Boolean = true
    ): RenderedPrompt? {
        if (toolsJson.isBlank() || toolsJson == "[]" || toolsJson == "null") return null
        val roles = Array(messages.size) { messages[it].first }
        val contents = Array(messages.size) { messages[it].second }
        val tmpl = chatTemplate()
        probeMark("[工具] 即将渲染带 tools 的 prompt：msgs=${messages.size} tools_len=${toolsJson.length} " +
                "chat_template_len=${tmpl.length}（=0 表示模型没带模板）choice=${toolChoice ?: "auto"}")
        return try {
            // thinkingOn 也要传：库的 enable_thinking 与上面那段同理，且它会影响
            // generation_prompt（进而影响工具解析的前缀对齐），渲染侧与解析侧必须同源。
            val r = nativeApplyChatTemplateTools(
                tmpl, roles, contents, toolsJson, toolChoice, parallelToolCalls, addAss, thinkingOn)
            probeMark("[工具] 带 tools 渲染返回：${if (r == null) "null（回落无工具路径）" else "len=${r.length}"}")
            r?.let { RenderedPrompt.parse(it) }
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
    fun parseToolCalls(
        text: String,
        toolsJson: String,
        addAss: Boolean = true,
        thinkingOn: Boolean = true
    ): Pair<String, String?>? {
        if (text.isEmpty()) return null
        val tmpl = chatTemplate()
        probeMark("[工具] 即将调用 nativeParseToolCalls：text_len=${text.length} " +
                "tools_len=${toolsJson.length} tmpl_len=${tmpl.length} add_ass=${addAss} " +
                "text_head=${text.take(200).replace("\n", "\\n")}")
        // addAss 必须与**渲染时**同一个值：解析器是按模板推导的 PEG，而 cp.generation_prompt
        // （会被 common_chat_parse 前拼到输入上、进而决定 PEG 根节点能否匹配）随
        // add_generation_prompt 变化。两边不一致 = 根节点前缀对不上 = tool_calls 恒为 0。
        // thinkingOn 同理必须与**渲染时**同一个值：它决定 cp.generation_prompt 的形状
        // （MiniCPM5 在 enable_thinking=true 下多一段 "<think>\n"），而那个量会被
        // common_chat_parse 前拼到输入上。两边不一致 = PEG 根节点前缀对不上 = tool_calls 恒为 0。
        val raw = try { nativeParseToolCalls(text, toolsJson, tmpl, addAss, thinkingOn) } catch (t: Throwable) {
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
     *
     * [generationPrompt] 是这一轮**实际渲染出来的**生成后缀（prompt 里模型要接着续写的那段尾巴）。
     * 它只在带 `response_format` 时有意义，用途是让 GBNF 约束与真实 prompt 对齐 ——
     * 详见 [ResponseFormat.GenerationPrompt] 的注释（不对齐的后果是「输出前面多一段
     * 模型自己吐的生成标记」）。
     */
    fun newSampler(temp: Float, topP: Float, minP: Float, seed: Long = 0,
                   topK: Int = 0, repPenalty: Float = 1f, penaltyN: Int = 0,
                   freqPenalty: Float = 0f, presencePenalty: Float = 0f,
                   stops: List<String> = emptyList(),
                   responseFormat: ResponseFormat = ResponseFormat.None,
                   generationPrompt: String? = null,
                   chatTemplateOverride: String? = null,
                   thinkingOn: Boolean = true): Boolean =
        nativeNewSampler(temp, topP, minP, topK, repPenalty, penaltyN, freqPenalty, presencePenalty, seed,
            // 空列表传 null（而不是空数组）：native 侧据此判断"要不要挂 stop 采样器"，
            // 挂一个什么都不匹配的采样器只是白搭一次链上调用。
            if (stops.isEmpty()) null else stops.toTypedArray(),
            // 三态映射（None -> null / JsonObject -> "" / JsonSchema -> 原文）的唯一来源，
            // 不在这里另判一次（两处各判一次必然漂移）。
            JsonSchemaFormat.schemaArg(responseFormat),
            // 生成后缀：null 表示"无 / 未知"，native 侧退回 `add_generation_prompt=true` 的旧口径。
            generationPrompt,
            // 模板：必须与**渲染侧同一份**。传 null/空串 = 让库按模型自选 ——
            // 那是引入本特性之前的行为，但库内自选的那份与运行时模板可能不是同一份，
            // grammar 会按别的模板算（或干脆产不出来），且不报错。
            chatTemplateOverride,
            // 思考开关：**必须与渲染侧同一个值**，理由见 nativeNewSampler 的说明与
            // gbnf_from_json_schema 的判据 ④。回落 ChatML 那条路也把它带下去。
            thinkingOn)

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
        // 这里报的是**本轮整段 prompt 的 token 数**（= n_used，含被复用的那截），
        // 不是"本次新算了多少" —— 后者由下面那行单列。
        //
        // 为什么必须分成两行：复用生效时 genPromptTok 会**变大**（prompt 更长），
        // 而 prefillMs 会变小，于是 tok/s 看起来"变快了很多"，但那是分母变了，
        // 不是模型变快了。`prefill X tok / Yms` 与 native 的 `新算 N / 复用 M`
        // 两条一起看才判得准（第一轮 N==X、M==0，之后 M 应显著大于 0）。
        uiLog("${LogFileStore.TAG_TIME} prefill %d tok / %dms = %.1f tok/s".format(
                genPromptTok, genPrefillMs, genPromptTok * 1000.0 / genPrefillMs))
        val newTok = lastPrefillTokens
        val reused = lastReuseTokens
        if (reused > 0) {
            uiLog("${LogFileStore.TAG_TIME} KV 前缀复用：复用 %d tok / 新算 %d tok（共 %d）".format(
                    reused, newTok, genPromptTok))
        } else {
            uiLog("${LogFileStore.TAG_TIME} KV 前缀复用：未命中，全量 prefill %d tok".format(newTok))
        }
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
        // 内存这一栏必须有一个**推理态**的数：此前唯一的 RSS 探针打在 loadModel 里，
        // 于是"加载完成态"被当成"跑起来的占用"，优化时瞄错了时刻（见 native 那段注释）。
        val peak = runCatching { nativeRssPeakMb() }.getOrDefault(-1)
        if (peak > 0) uiLog("${LogFileStore.TAG_MEM} 推理期 RSS 峰值 %d MB（加载态读数见 [mmap释放]，两者成对读）".format(peak))
    }

    /**
     * 中断当前生成（下一次 step 返回 null）。
     *
     * 保留这个公开名（UI 侧历史调用点），但**必须**带上归属：裸的无编号取消会把
     * 一个迟到的调用打到下一轮上（见 `RequestCancel` 文件头与本文件 [requestAbort]）。
     * 没有轮次在跑时它演化为 no-op —— 这正是我们要的语义：取消不存在的轮次不该
     * 产生任何副作用，也不该"预支"给下一次。
     */
    fun abort(): RequestCancel.Token? = requestAbort()

    /** 释放采样器/上下文/模型。与 [loadModel] 共用同一把锁：卸载与加载交错同样会污染 native 状态。 */
    fun unload() = synchronized(loadLock) { unloadLocked() }

    /**
     * [unload] 的实体；调用方已持有 [loadLock]（与 `loadModelLocked` 同一约定，
     * 避免重入与锁序问题）。换模型那条路径也走它 —— 卸载的**副作用**（释放后
     * 报读、归还未释放的匿名页）只有一处，不会出现"这条路做了、那条路漏了"。
     */
    private fun unloadLocked() {
        if (!loaded) return
        genActive = false   // 换模型/卸载时复位，避免旧计数串进下一轮
        nativeFreeSampler()
        // 释放这一幕必须**当场报读**：`[内存] 卸载 RSS …` 由 native 在
        // `llama_model_free` 前后各量一次 VmRSS 得到。此前卸载这条路径上
        // 一行读数都没有，用户只能去任务管理器看"占用降不下来"，
        // 日志静默（0.9.130 现场）。
        nativeUnloadModel()
        currentPath = null
        // 卸载要连预读状态一起作废，否则 UI 会继续显示上一个模型的信息（误导）
        probeId = null
        probeVal = null
        // 释放之后再把"还没还给内核的页"补一脚（见 [reclaimReleasedHeap]）：
        // 卸载完还留在 RSS 里的只剩下这一类，这一脚是**净收益**，且只跑一次。
        reclaimReleasedHeap()
    }

    /**
     * 把"已 free、但还没归还内核的匿名页"补一次 `madvise(MADV_DONTNEED)`，并报前后 RSS。
     *
     * 为什么必须有这一脚：**CPU 侧的默认分配器会缓存刚释放的堆段**（glibc 的
     * `M_TRIM_THRESHOLD`/`M_MMAP_THRESHOLD`、scudo 的 size class 缓存，Android 上
     * 是后者）。它的直接后果是 —— repack 的匿名拷贝、KV、compute buffer 虽然都
     * `free` 了，**VmRSS 照样不降**。于是应用列表里显示的占用一直很高，
     * 看起来像"repack 根本没省"，而实际省下来的只是"还没归还"。
     * 只有 `malloc_trim(0)`（把空闲堆段还给内核）或整进程重启能把这段要回来。
     *
     * 判据（与 `[mmap释放]` 同一套写法，**自证**）：前后各读一次 VmRSS 打进日志。
     *
     * 为什么只在**卸载后空闲**这一刻做，不在加载后做：`malloc_trim` 会把页还掉，
     * 下一次分配又要重新缺页（代价是加载/首轮推理变慢）；而 `madvise(MADV_DONTNEED)`
     * 对**已释放**的块没有副作用（它们本来就不该被读）。空闲时调用是净收益。
     *
     * 为什么**必须**在 native 里做而不是 Kotlin：判据是"运行期拿到的 `mallinfo`/RSS
     * 真实变化"，而且是 native 自己 malloc 出来的匿名页；Kotlin 那侧的 System/API
     * 看不见这些块，也唤不动 native 的分配器。**不 fork 任何子进程** ——
     * 本仓库此前在 native probe 里用 `fork`+`execl` 读系统属性，在国产 ROM 上被
     * 域策略整族拦掉（见 `probe_getprop` 那段），同一种病不再犯一次。
     *
     * 失败**不影响任何结果**：最坏就是"没多还这一份"，与不做之前完全一致。
     */
    private fun reclaimReleasedHeap() {
        val log = runCatching { nativeReclaimReleasedHeap() }.getOrNull() ?: return
        if (log.isNotEmpty()) uiLog(log)
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
    /**
     * KV 前缀复用统计，返回 int[3] = {上一轮复用的 token 数, 上一轮新算的 token 数,
     * 已跑完 prefill 的轮次数}。与 [lastReuseTokens] / [lastPrefillTokens] /
     * [kvRounds] 成对，见那边的说明。
     */
    @JvmStatic private external fun nativeKvCacheStats(): IntArray
    /** KV 账本是否有效（下一轮能否复用）。 */
    @JvmStatic private external fun nativeKvCacheValid(): Boolean
    /** 丢弃 prompt 缓存（清 KV 并作废账本）。 */
    @JvmStatic private external fun nativeKvCacheReset()
    @JvmStatic private external fun nativeChatTemplate(): String
    @JvmStatic private external fun nativeApplyChatTemplate(
        tmpl: String, roles: Array<String>, contents: Array<String>, addAss: Boolean,
        thinkingOn: Boolean): String?
    /** 带 tools 的模板渲染：工具定义进 prompt（旧接口不认 tools，工具调用永远不触发）。 */
    @JvmStatic private external fun nativeApplyChatTemplateTools(
        tmpl: String, roles: Array<String>, contents: Array<String>,
        toolsJson: String?, toolChoice: String?, parallelToolCalls: Boolean, addAss: Boolean,
        thinkingOn: Boolean): String?
    /**
     * 从输出文本解析 tool_calls，返回 JSON；无工具调用或解析失败返回 "null"。
     *
     * 必须传入**渲染时的同一个模板**：解析器是按模板推导出的 PEG，两边不一致会解析错。
     */
    @JvmStatic private external fun nativeParseToolCalls(
        text: String, toolsJson: String?, tmpl: String, addAss: Boolean, thinkingOn: Boolean): String
    /**
     * [stops] 为 null / 空表示不带 stop 序列；[schema] 为 null 表示不约束输出格式（见 [ResponseFormat]）。
     * [genPrompt] 为这一轮实际渲染出的生成后缀；null 表示"无 / 未知"，native 侧退回旧口径
     * （见 [ResponseFormat.GenerationPrompt]）。
     * [tmpl] 为这一轮的 chat 模板原文（与渲染侧同源）；空串表示"按模型自选"。
     */
    @JvmStatic private external fun nativeNewSampler(temp: Float, topP: Float, minP: Float, topK: Int, repPenalty: Float, penaltyN: Int, freqPenalty: Float, presencePenalty: Float, seed: Long, stops: Array<String>?, schema: String?, genPrompt: String?, tmpl: String?, thinkingOn: Boolean): Boolean
    @JvmStatic private external fun nativeStartCompletion(prompt: String, maxTokens: Int): Boolean
    @JvmStatic private external fun nativeStep(): String?
    /**
     * 取消编号为 [roundEpoch] 的那一轮生成。编号由 [nativeCurrentEpoch] 在轮次开始时取得。
     * 编号与当前轮次不一致（或为 0）时**一律不生效** —— 宁漏停，不错停；见 native 侧注释。
     */
    @JvmStatic private external fun nativeAbort(roundEpoch: Long)
    /** 取当前轮次编号（供 [beginCancelable] 绑定归属；未在生成时是"最后一次的编号"）。 */
    @JvmStatic private external fun nativeCurrentEpoch(): Long
    /**
     * 本轮已进 KV 账本的生成 token 数。取消收尾时与"已下发的步数"对账：
     * 两者不等 = 有一段内容引擎算过、客户端没收到（E-4 的可观测面，**不回滚**）。
     */
    @JvmStatic private external fun nativeRoundLedgerTokens(): Int
    /**
     * 权重重排（repack）档位：`0` = 关、`1` = 开、其它 = **没设过**（native 落回库默认）。
     *
     * 走 JNI 参数而不是系统属性：属性那条链要 `fork` + `exec /system/bin/getprop`，
     * 在真机上读不到时**与"没设过"同形**（见 native `model_use_extra_bufts` 的注释），
     * 而这一档是"少一份匿名拷贝"的唯一旋钮，判定必须落在仓库自己的存储上。
     *
     * 命名：本方法原名 `nativeSetExtraBufts`，与**库内部字段** `use_extra_bufts` 同名，
     * 于是日志/设置页/代码三处对"这一档叫什么"各叫各的（用户对着日志找不着设置项）。
     * 0.9.127 起统一叫 `repack`，并为跨版本改名留一个**别名**（见下）。
     */
    // 注意：本方法（以及下面的旧名别名）在 native 侧的定义**必须落在**
    // `extern "C"` 作用域内。少了那层链接规格，C++ 会重整符号名，
    // JVM 就找不到它 —— 0.9.125 装机后正是这么报 UnsatisfiedLinkError 的
    // （0.9.126 修；守卫见 run_mmap_device_guard 第 ⑩ 条，按**结构**钉，
    // 不按"名字在文件里出现过"钉）。
    @JvmStatic private external fun nativeSetRepack(mode: Int)

    /**
     * 旧名别名（0.9.127 起弃用）：**只有**在装到了不含 [nativeSetRepack] 的旧 .so
     * （覆盖安装少解压某个 ABI 变体）时才可能命中，命中即有日志、不静默 ——
     * 否则就是"改名把功能改哑了，而日志看不出为什么"。
     */
    @JvmStatic private external fun nativeSetExtraBufts(mode: Int)

    /**
     * 把档位交给 native；优先新名，**只在**新名缺失时才回退旧名。
     * 为什么不是"两个都调"：两个符号在同一个 .so 里写同一个变量，都调等于
     * 把"到底哪条链生效"重新变成不可观测 —— 本轮要修的正是这个病。
     */
    private fun setRepackMode(mode: Int) {
        try {
            nativeSetRepack(mode)
        } catch (t: UnsatisfiedLinkError) {
            uiLog("[repack] ⚠ 本包 native 未导出 nativeSetRepack（旧 .so？）→ 回退旧名；" +
                "档位来源在日志里会写成旧名，报告问题时请一并附上本行")
            try {
                nativeSetExtraBufts(mode)
            } catch (t2: UnsatisfiedLinkError) {
                uiLog("[repack] ⚠ 旧名也不可用 → 本轮档位落回库默认（设置页的值**未生效**）")
            }
        }
    }

    /** 推理期 RSS 峰值（MB）；-1 = 还没跑过任何一轮或读不到。见 native `rss_note_peak`。 */
    @JvmStatic private external fun nativeRssPeakMb(): Int

    /**
     * 卸载后补一脚"把已释放的匿名页还给内核"，返回该打的一行日志（空串 = 无需报读）。
     * 见 [reclaimReleasedHeap] 的注释：这一脚对付的是"free 了但 RSS 不降"的分配器缓存。
     */
    @JvmStatic private external fun nativeReclaimReleasedHeap(): String

    @JvmStatic private external fun nativeFreeSampler()
    @JvmStatic private external fun nativeUnloadModel()
}
