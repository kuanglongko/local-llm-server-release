package com.xiaowan.localinference

import android.content.Context
import java.io.File

/**
 * 模型库：扫描应用私有 models 目录，管理选中、别名、角色与删除。
 * 解决"只能反复导入、无法二次选择"的问题：
 * - 已导入的 GGUF 常驻此目录，启动时列出，点击即选，长按/按钮删除释放空间
 * - 选中项持久化到 SharedPreferences，下次启动自动恢复
 */
object ModelLibrary {
    private const val KEY_SELECTED = "selected_model"
    private const val KEY_ALIAS_PREFIX = "alias_"
    private const val KEY_ROLE_PREFIX = "role_"

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
        /**
         * 这个文件在库里算哪一类。没显式存过时按文件名猜（[ModelRole.guess]）。
         * 默认值只是为了让既有构造点（含测试桩）不必同步改签名，真正取数走 [rows]。
         */
        val role: ModelRole = ModelRole.UNKNOWN,
        /** true = [role] 来自文件名猜测，而不是用户显式选过；UI 会如实标注。 */
        val roleAuto: Boolean = false,
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
        val prefs = ctx.getSharedPreferences(Prefs.MODEL, Context.MODE_PRIVATE)
        val all = prefs.all
        val files = list(ctx)
        val externalNames = ExternalModel.all(ctx).map { it.name }.toHashSet()
        return files.map { f ->
            val ext = externalNames.contains(f.name)
            // 角色与别名一样从同一次 `getAll()` 的快照里取，避免渲染循环里再问 prefs。
            // 没有存过 = 按文件名猜（老模型升级后不必逐个重标）；存成 `unknown`
            // 则尊重用户的"未分类"，不再拿文件名覆盖他。
            val rawRole = all[KEY_ROLE_PREFIX + f.name] as? String
            val role = if (rawRole == null) ModelRole.guess(f.name) else ModelRole.fromId(rawRole)
            Row(
                file = f,
                alias = (all[KEY_ALIAS_PREFIX + f.name] as? String).orEmpty()
                    .ifEmpty { f.name.removeSuffix(".gguf") },
                external = ext,
                missing = ext && !f.isFile,
                bytes = f.length(),
                role = role,
                roleAuto = rawRole == null && role != ModelRole.UNKNOWN,
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
        ctx.getSharedPreferences(Prefs.MODEL, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED, "").orEmpty()

    fun setSelected(ctx: Context, name: String) {
        ctx.getSharedPreferences(Prefs.MODEL, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED, name).apply()
    }

    // ---- 嵌入模型（RAG 双常驻的那一份）----
    //
    // 与对话模型**共用同一个模型库目录**（同一个 `ModelLibrary.list`），但选中项
    // 分开存：两个角色各要一个"当前用哪个"。合并成一个键会让"选嵌入模型"顺手
    // 把对话模型的选择也改掉 —— 那正是双常驻要避免的串味。
    private const val KEY_EMBED_MODEL = "embed_model"

    /** 当前选定的嵌入模型文件名；""= 没选过（不能靠它判断是否已加载，见 [LlmEngine.hasEmbedModel]）。 */
    fun embedModelName(ctx: Context): String =
        ctx.getSharedPreferences(Prefs.MODEL, Context.MODE_PRIVATE)
            .getString(KEY_EMBED_MODEL, "").orEmpty()

    fun setEmbedModelName(ctx: Context, name: String) {
        ctx.getSharedPreferences(Prefs.MODEL, Context.MODE_PRIVATE)
            .edit().putString(KEY_EMBED_MODEL, name).apply()
    }

    /** 模型显示别名（/v1/models 返回它；默认= 文件名去 .gguf） */
    fun aliasOf(ctx: Context, fileName: String): String =
        ctx.getSharedPreferences(Prefs.MODEL, Context.MODE_PRIVATE)
            .getString(KEY_ALIAS_PREFIX + fileName, "").orEmpty()
            .ifEmpty { fileName.removeSuffix(".gguf") }

    fun setAlias(ctx: Context, fileName: String, alias: String) {
        ctx.getSharedPreferences(Prefs.MODEL, Context.MODE_PRIVATE)
            .edit().putString(KEY_ALIAS_PREFIX + fileName, alias.trim()).apply()
    }

    /**
     * 这个文件算哪一类模型（对话 / 向量 / 未分类）。
     *
     * 语义与 [Row.role] 完全一致：
     *   · 显式存过 -> 用存的值（包括用户选的"未分类"，它会压过文件名）；
     *   · 没存过   -> 按文件名猜（`ModelRole.guess`），老模型因此不会突然消失。
     *
     * 为什么加载路径也走这里、而不是只信列表里的 [Row.role]：列表可能是上一次
     * 刷新留下的，而"点加载"发生在之后的任意时刻；这里以磁盘上的选择为准，
     * 才能在**真正装进槽之前**再拦一次。
     */
    fun roleOf(ctx: Context, fileName: String): ModelRole {
        val raw = ctx.getSharedPreferences(Prefs.MODEL, Context.MODE_PRIVATE)
            .getString(KEY_ROLE_PREFIX + fileName, null)
        return if (raw == null) ModelRole.guess(fileName) else ModelRole.fromId(raw)
    }

    /**
     * 记录用户对模型类型的显式选择。
     *
     * [ModelRole.UNKNOWN] 会写入字面量 `unknown`（不是删键）：删键等于"回到
     * 按文件名自动识别"，而用户选"未分类"的意思恰恰是"别再拿文件名替我决定"。
     */
    fun setRole(ctx: Context, fileName: String, role: ModelRole) {
        ctx.getSharedPreferences(Prefs.MODEL, Context.MODE_PRIVATE)
            .edit().putString(KEY_ROLE_PREFIX + fileName, role.id).apply()
    }

    /** 文件名 -> 别名 映射（/v1/models 全库列表用） */
    fun aliasMap(ctx: Context): Map<String, String> =
        list(ctx).associate { it.name to aliasOf(ctx, it.name) }
}
