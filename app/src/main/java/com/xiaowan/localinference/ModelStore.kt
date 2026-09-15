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

    fun modelsDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "models").apply { mkdirs() }

    /** 模型库 = 应用内副本 + 就地引用条目，按修改时间倒序（引用文件已丢失时排在末尾）。 */
    fun list(ctx: Context): List<File> {
        val internal = modelsDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
            ?.toList() ?: emptyList()
        val external = ExternalModel.all(ctx).map { File(it.path) }
        return (internal + external).sortedByDescending { it.lastModified() }
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

    /** 文件名 -> 别名 映射（/v1/models 全库列表用） */
    fun aliasMap(ctx: Context): Map<String, String> =
        list(ctx).associate { it.name to aliasOf(ctx, it.name) }
}
