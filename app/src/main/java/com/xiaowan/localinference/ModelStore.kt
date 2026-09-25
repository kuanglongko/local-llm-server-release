package com.xiaowan.localinference

import android.content.Context
import java.io.File

/**
 * 模型库：扫描应用私有 models 目录，管理选中与删除。
 * 解决"只能反复导入、无法二次选择"的问题：
 * - 已导入的 GGUF 常驻此目录，启动时列出，点击即选，长按/按钮删除释放空间
 * - 选中项持久化到 SharedPreferences，下次启动自动恢复
 */
object ModelStore {
    private const val PREFS = "model_store"
    private const val KEY_SELECTED = "selected_model"
    private const val KEY_ALIAS_PREFIX = "alias_"

    /**
     * 模型库目录。**永不返回相对路径**。
     *
     * `getExternalFilesDir(null)` 在外部存储不可用（未挂载 / 被 OEM 限制）时返回 **null**，
     * 而 `File(parent: File?, child: String)` 在 `parent == null` 时会退化成
     * `java.io.File(child)` —— 一个**相对进程 CWD** 的路径。那意味着 `listFiles{}` 去 CWD 下
     * 列目录、`mkdirs()` 去 CWD 下建目录，而调用方完全看不出异常（不抛、不返回 null）。
     * 外部存储拿不到时回落到应用内部 `filesDir`：目录换了个位置但语义不变，
     * 且**永远**是一个绝对路径。返回的目录保证已存在。
     */
    fun modelsDir(ctx: Context): File {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(base, "models").apply { mkdirs() }
    }

    /**
     * 模型库列表的一行：文件 + 展示所需的全部字段，**一次算完**。
     *
     * 为什么要有这个类型：UI 原先是在渲染循环里逐行去问 `isExternal()` / `aliasOf()` /
     * `length()` —— 每个模型各触发 2 次 `getSharedPreferences` + 若干次 `getString` + 一次 `stat`，
     * 而这段循环跑在**主线程**上，耗时随模型数线性增长。把"取数"与"渲染"拆开后，
     * 取数可以整体挪到后台线程，渲染只读现成字段。
     */
    data class Row(
        val file: File,
        val alias: String,
        val external: Boolean,
        val missing: Boolean,
        val bytes: Long,
    )

    /** 模型库 = 应用内副本 + 就地引用条目，按修改时间倒序（引用文件已丢失时排在末尾）。 */
    fun list(ctx: Context): List<File> {
        val internal = modelsDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
            ?.toList() ?: emptyList()
        val external = ExternalModel.all(ctx).map { File(it.path) }
        return (internal + external).sortedByDescending { it.lastModified() }
    }

    /**
     * 一次读 prefs（**一次** `getSharedPreferences` 调用取全部键），产出可直接渲染的行。
     *
     * 与 [list] 的区别只在"问了几次 prefs"：
     * - [list] 之后逐行调 [isExternal] / [aliasOf]，每次都是一轮 prefs 往返；
     * - 这里只 `getAll()` 一次，其余全在内存里查。
     *
     * 外部存储不可用时 `getExternalFilesDir(null)` 返回 null，本方法**不**落入相对路径
     * （见 [modelsDir]），所以取数在会被调用的任何环境下都指同一批文件。
     */
    fun rows(ctx: Context): List<Row> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = prefs.all
        val files = list(ctx)
        val externalNames = ExternalModel.all(ctx).map { it.name }.toHashSet()
        return files.map { f ->
            val ext = externalNames.contains(f.name)
            Row(
                file = f,
                alias = (all[KEY_ALIAS_PREFIX + f.name] as? String).orEmpty()
                    .ifEmpty { f.name.removeSuffix(".gguf") },
                external = ext,
                missing = ext && !f.isFile,
                bytes = f.length(),
            )
        }
    }

    /** 该条目是否为「就地引用」——决定删除按钮是「移除引用」还是「删除文件」。 */
    fun isExternal(ctx: Context, name: String): Boolean =
        ExternalModel.find(ctx, name) != null

    /** 同名时应用内副本优先：副本不依赖原文件位置，语义更确定。 */
    fun findByName(ctx: Context, name: String): File? =
        File(modelsDir(ctx), name).takeIf { it.isFile }
            ?: ExternalModel.find(ctx, name)?.let { File(it.path) }

    fun selectedName(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED, "").orEmpty()

    fun setSelected(ctx: Context, name: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED, name).apply()
    }

    /** 模型显示别名（/v1/models 返回它；默认= 文件名去 .gguf） */
    fun aliasOf(ctx: Context, fileName: String): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ALIAS_PREFIX + fileName, "").orEmpty()
            .ifEmpty { fileName.removeSuffix(".gguf") }

    fun setAlias(ctx: Context, fileName: String, alias: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ALIAS_PREFIX + fileName, alias.trim()).apply()
    }

    private const val KEY_PORT = "server_port"

    /** HTTP 服务端口（1024-65535），默认 8080；非法值自动回落。 */
    fun serverPort(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PORT, "").orEmpty().toIntOrNull()
            ?.takeIf { it in 1024..65535 } ?: 8080

    fun setServerPort(ctx: Context, port: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PORT, port.toString()).apply()
    }

    private const val KEY_LAN = "lan_access"
    /** 全局默认关闭思考。 */
    private const val KEY_DIS_THINK = "disable_thinking"

    /** 局域网访问开关（绑定 0.0.0.0，无鉴权，默认关）。 */
    fun lanAccess(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LAN, false)

    /** 默认关闭思考（true=关；客户端请求参数可覆盖）。 */
    fun disableThinking(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DIS_THINK, true)

    fun setDisableThinking(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DIS_THINK, on).apply()
    }

    fun setLanAccess(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LAN, on).apply()
    }

    private const val KEY_CORS_ON = "cors_enabled"

    /**
     * CORS 开关（默认**开**）。
     *
     * 默认开的理由：CORS 白名单的默认集只放行回环与本机 IP，**不放行任何外部来源** ——
     * 也就是说默认开着的边界与"完全不开"相比没有扩大攻击面（外部来源仍被拦），
     * 但"本机自用"这条路径立刻可用。反过来默认关，第一步就会有用户遇到
     * "浏览器调不通"，然后去把整个 CORS 关掉/改成通配 —— 那才是真的危险。
     */
    fun corsEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CORS_ON, true)

    fun setCorsEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CORS_ON, on).apply()
    }

    private const val KEY_CORS_EXTRA = "cors_extra_origins"

    /**
     * CORS 白名单里**额外**放行的来源（默认空 = 只放行回环与本机 IP）。
     *
     * 为什么是"追加"而不是"整个白名单可改"：默认集（回环 + 本机 IP）是
     * 「本机自用」这条路径的兜底，把它交给用户去填，第一步就会有人填错成
     * `*` 或直接清空。这里只开放"再加几个外部来源"，默认行为不可破坏。
     *
     * 存换行分隔的原始串（用户可能从别处粘一串进来），解析时逐个规范化。
     */
    fun corsExtraOrigins(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CORS_EXTRA, "").orEmpty()
            .split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }

    fun setCorsExtraOrigins(ctx: Context, raw: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CORS_EXTRA, raw).apply()
    }

    private const val KEY_API_TOKEN = "api_token"

    /**
     * 生成端点的 Bearer token。**空串 = 鉴权关闭**（与升级前完全同行为）。
     *
     * 为什么默认空（= 不开鉴权）而不是"默认生成一个"：
     * 默认生成会让所有既有客户端（`tools/acceptance_*.py`、README 的 curl 示例、
     * 第三方 UI）在升级后集体 401 —— 而它们的失败形态是"请求失败"，
     * 看起来正像"功能坏了"。默认空 = 行为不变；要开启是用户的一次**显式动作**。
     *
     * 存 SharedPreferences（应用私有，非 root 读不到）。这不是"密钥保险箱"
     *（设备被 root 就没有隐私可言），但它与本仓库的威胁模型一致：
     * 要挡住的是**同网段的其它设备**，不是本机已提权的进程。
     */
    fun apiToken(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API_TOKEN, "").orEmpty().trim()

    fun setApiToken(ctx: Context, token: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_API_TOKEN, token.trim()).apply()
    }

    private const val KEY_FLASH = "flash_attn"

    /** flash attention 开关（默认关；开启后 KV cache 额外支持 iq4_nl(24)；iq4_xs 无 KV LUT 不支持）。 */
    fun flashAttn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FLASH, false)

    fun setFlashAttn(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FLASH, on).apply()
    }

    // 推理参数固化（键值对，值统一存字符串，兼容 int/float 参数）
    private const val KEY_PARAMS = "engine_params"

    /** 已保存的推理参数；未保存过返回空 Map。 */
    fun engineParams(ctx: Context): Map<String, String> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PARAMS, "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        return raw.split(';').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
    }

    fun setEngineParams(ctx: Context, params: Map<String, String>) {
        val raw = params.entries.joinToString(";") { "${it.key}=${it.value}" }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PARAMS, raw).apply()
    }

    /**
     * 权重重排（repack）档位：`1` = 开（库默认），`0` = 关，**`null` = 用户没设过**。
     *
     * 为什么第三种状态必须与 `0` 分开：没设过时要落回库默认（开）。如果只存 Boolean，
     * 用户关掉一次之后就无法表达"回到默认"，而这一个开关的语义恰恰是"默认开"。
     * 更实际的一层：native 侧要靠 `-1` 判定"这轮的档位是谁定的"，丢了这一位，
     * 日志里就再也分不出"用户关的"与"库默认"。
     *
     * 为什么这个档位值得抬成设置项（而不是继续用系统属性）：见
     * `llama_jni.cpp` 里 `model_use_extra_bufts()` 上方那段 —— 属性通道在真机上
     * 读不到（`getprop` 子进程的读数被静默吞掉），而它是本轮唯一能"少一份拷贝"
     * 的旋钮（省下的是随 CPU 层数增长的一份匿名拷贝，具体读数见 HTP-STATUS §52）。
     * 判定必须走仓库自己的存储。
     *
     * **单一真源**：本函数读的就是设置页写的那一格 —— `engine_params` 里的 `"repack"`
     * （见 `EngineActivity.persistParams()`）。0.9.127 曾另开一个 prefs 键
     * `use_extra_bufts`（`KEY_EXTRA_BUFTS`）来回传，但**全仓没有任何一处写它**：
     * 设置页存进 `engine_params.repack`、本函数读另一个键 → 恒为 `null` →
     * native 恒收到 `-1` → 日志永远写"来源：库默认（本仓库未设过）"，
     * 而 UI 上那一档**显示已选中**（设置页从 `engine_params.repack` 还原）。
     * 两处各自都能自证，中间那条链断了却看不出来 —— 这正是 issue #132 反复出现
     * 的同一种病，所以这里不再保留第二个键。
     */
    fun extraBufts(ctx: Context): Int? =
        engineParams(ctx)["repack"]?.takeIf { it == "0" || it == "1" }?.toInt()

    /** 文件名 -> 别名 映射（/v1/models 全库列表用） */
    fun aliasMap(ctx: Context): Map<String, String> =
        list(ctx).associate { it.name to aliasOf(ctx, it.name) }
}
