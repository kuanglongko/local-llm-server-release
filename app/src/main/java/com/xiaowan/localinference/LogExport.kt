package com.xiaowan.localinference

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * 修「导出 txt 存到 filesDir/logs/」。
 *
 * 原来写死 `File(filesDir, "logs")`，那是应用私有目录：文件管理器看不到、也没法发给别人，
 * Toast 里那句 "已导出 filesDir/logs/xxx" 对用户等于没用——他们想拿的是崩溃前那次日志，
 * 却在文件管理器里根本找不到文件。现在优先写公共「下载」目录，并返回**人能看懂的真实路径**。
 *
 * 分支理由：
 * - API 29+：MediaStore.Downloads，不需要存储权限，写完在系统「下载」里就能看到；
 * - API 26~28：先试公共 Download（需 WRITE_EXTERNAL_STORAGE，运行时未授予会抛异常），
 * 失败再退回应用专属外部目录（无需权限，路径形如 Android/data/<包名>/files/Download）。
 */
object LogExport {

    /** @return 人类可读的落盘位置（已含文件名），失败抛异常由调用方提示 */
    fun saveText(context: Context, fileName: String, text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { return viaMediaStore(context, fileName, bytes) }
            // 个别 ROM 的 MediaStore Download 集合不可写时继续往下兜底
        }
        runCatching { return viaPublicDir(fileName, bytes) }
        return viaAppExternalDir(context, fileName, bytes)
    }

    private fun viaMediaStore(context: Context, name: String, bytes: ByteArray): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            // 不加 IS_PENDING：写完直接可见，省一次通知调用，也避免中途崩溃留下"永久待定"文件
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore 未返回 uri")
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("无法打开输出流")
        return "公共「下载」目录/$name"
    }

    @Suppress("DEPRECATION")
    private fun viaPublicDir(name: String, bytes: ByteArray): String {
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val out = java.io.File(dl, name)
        out.writeBytes(bytes)
        return "${out.absolutePath}"
    }

    private fun viaAppExternalDir(context: Context, name: String, bytes: ByteArray): String {
        val dl = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        if (!dl.exists()) dl.mkdirs()
        val out = java.io.File(dl, name)
        out.writeBytes(bytes)
        return out.absolutePath
    }
}
