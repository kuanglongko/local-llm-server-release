package com.xiaowan.localinference

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

/**
 * 「就地引用」外部模型：只在 SharedPreferences 里记一个绝对路径，**不复制文件**。
 *
 * 为什么要它：GGUF 动辄 4~8 GB，复制进私有 models 目录等于同一份权重占两倍空间，
 * 而用户下载完本来不打算再留原件；反之，私有目录副本的好处是「不依赖原文件位置」。
 * 两种诉求都真实存在，所以做成导入时二选一，而不是替他决定。
 *
 * 技术上唯一难点：导入入口是 ACTION_OPEN_DOCUMENT（SAF），它给的是 content:// uri，
 * 而 native 侧 nativeProbeGguf/nativeLoadModel 只接受路径字符串。所以必须先把 uri
 * 反解成真实文件路径。反解有两条互补的路：
 * - MediaStore 系 uri（authority 含 `.media`）：直接投影 `_data` 列；
 * - 文档 provider 系 uri（authority 含 `.documents`）：解 documentId
 *   `primary:Download/x.gguf` → `Environment.getExternalStorageDirectory()` 拼接。
 * 两者都拿不到时返回 null，调用方降级为「只能复制到应用内」，不硬编、不猜。
 *
 * 权限说明：SAF 授予的是 **uri 级** 读权限，不延伸到裸路径；裸路径 fopen 依赖
 * READ_EXTERNAL_STORAGE + targetSdk=28 的 legacy 存储可见性。因此就地引用前必须
 * 单独申请该运行时权限，缺权限时只做复制。
 */
object ExternalModel {

    private const val PREFS = "external_models"
    /** 登记格式：绝对路径 ␟ content uri（uri 允许为空，用于手工登记/降级场景） */
    private const val SEP = '␟'

    data class Ref(val name: String, val path: String, val uri: String)

    // ---- 登记簿 ----

    /** 全部引用条目（保持登记顺序）。 */
    fun all(ctx: Context): List<Ref> {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val order = sp.getString("order", "").orEmpty()
        if (order.isBlank()) return emptyList()
        return order.split(';').mapNotNull { name ->
            val raw = sp.getString(key(name), null) ?: return@mapNotNull null
            val path = raw.substringBefore(SEP)
            if (path.isBlank()) return@mapNotNull null
            Ref(name, path, raw.substringAfter(SEP, ""))
        }
    }

    fun find(ctx: Context, name: String): Ref? =
        all(ctx).firstOrNull { it.name == name }

    /** 登记一条引用；同名或解析不出真实文件时返回 false，调用方据此降级为复制。 */
    fun add(ctx: Context, name: String, path: String, uri: String): Boolean {
        val f = File(path)
        if (!f.isAbsolute || !f.isFile || f.length() <= 0) return false
        val list = all(ctx).toMutableList()
        if (list.any { it.name == name }) return false
        if (File(ctx.getExternalFilesDir(null), "models/$name").isFile) return false
        list += Ref(name, path, uri)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key(name), "$path$SEP$uri")
            .putString("order", list.joinToString(";") { it.name })
            .apply()
        // 顺手留存 uri 权限：万一原文件后来被移动，仍可从同一 uri 复制一份救回来。
        if (uri.isNotEmpty()) runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        return true
    }

    /** 只删登记，绝不碰磁盘上的原始 GGUF。 */
    fun remove(ctx: Context, name: String): Ref? {
        val target = find(ctx, name) ?: return null
        val rest = all(ctx).filterNot { it.name == name }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(key(name))
            .putString("order", rest.joinToString(";") { it.name })
            .apply()
        return target
    }

    private fun key(name: String) = "ref_$name"

    // ---- uri 反解 ----

    /** SAF 选择器给出的显示文件名（缺省回退 uri 末段）。 */
    fun displayName(ctx: Context, uri: Uri): String {
        var name = uri.lastPathSegment ?: "model.gguf"
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i)?.let { name = it }
            }
        }
        return name.substringAfterLast('/').take(180)
    }

    /**
     * content:// → 真实绝对路径；解不出返回 null。
     * 只认「文件确实存在且可读」的结果，避免登记一堆幽灵条目。
     */
    fun resolvePath(ctx: Context, uri: Uri): String? {
        if (uri.scheme != "content") {
            return uri.path?.let { if (File(it).isFile) it else null }
        }
        val authority = uri.authority.orEmpty()
        val guess = when {
            authority.contains(".documents") -> fromDocumentId(ctx, uri)
            authority.contains(".media") -> fromMediaData(ctx, uri)
            else -> fromMediaData(ctx, uri) ?: fromDocumentId(ctx, uri)
        }
        return guess?.takeIf { File(it).isFile }
    }

    /** MediaStore 系 uri：投影 `_data`（该列已 deprecated，但对 targetSdk 28 仍是唯一可靠反解手段）。 */
    @Suppress("DEPRECATION")
    private fun fromMediaData(ctx: Context, uri: Uri): String? {
        return try {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { c ->
                    if (!c.moveToFirst()) null
                    else c.getColumnIndex(MediaStore.MediaColumns.DATA)
                        .takeIf { it >= 0 }
                        ?.let { c.getString(it)?.takeIf { s -> s.isNotBlank() } }
                }
        } catch (t: Throwable) {
            null // 个别 provider 不支持投影 _data，按"解不出"处理，交给上层降级
        }
    }

    /** 文档 provider 系 uri：`primary:Download/x.gguf` → `/storage/emulated/0/Download/x.gguf`。 */
    private fun fromDocumentId(ctx: Context, uri: Uri): String? {
        val docId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (t: Throwable) {
            null
        } ?: return null
        val root = docId.substringBefore(':', docId)
        val rel = docId.substringAfter(':', "")
        if (rel.isEmpty()) return null
        // SD 卡/私有卷的 docId 前缀是卷 UUID，映射关系不凭记忆写死，交给 MediaStore 兜底查一次
        return if (root.equals("primary", true)) {
            File(Environment.getExternalStorageDirectory(), rel).absolutePath
        } else {
            fromMediaData(ctx, uri)
        }
    }

    /** 供 UI 展示的短路径（去掉 /storage/emulated/0 前缀）。 */
    fun shortPath(path: String): String =
        path.removePrefix(Environment.getExternalStorageDirectory().absolutePath + "/")
}
