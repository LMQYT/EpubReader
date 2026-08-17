package com.example.epubreader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/** 内部书籍文件信息 */
data class EpubFileInfo(val name: String, val size: Long, val timestamp: Long)

/**
 * 书籍内部存储：filesDir/epubs/。
 * 导入（流式拷贝，不整包进内存）、列表、删除、安全解析。
 */
class EpubStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "epubs").also { it.mkdirs() }

    /** 同步临时目录（下载中转/进度文件），用完即删 */
    val tempDir: File
        get() = File(context.filesDir, "webdav_tmp").also { it.mkdirs() }

    /** 清理非法字符、限长、保证 .epub 后缀、重名去重 */
    fun sanitizeFileName(raw: String): String {
        var name = raw.replace(Regex("""[\\/:*?"<>|\x00-\x1f]"""), "_").trim()
        if (name.isEmpty()) name = "book"
        if (!name.endsWith(".epub", ignoreCase = true)) name += ".epub"
        if (name.length > 100) {
            name = name.take(90) + ".epub"
        }
        return uniqueName(name)
    }

    private fun uniqueName(name: String): String {
        if (!File(dir, name).exists()) return name
        val base = name.removeSuffix(".epub")
        var i = 2
        while (File(dir, "${base} ($i).epub").exists()) i++
        return "${base} ($i).epub"
    }

    /** 从 SAF Uri 流式导入，返回落盘后的文件信息 */
    fun importFile(uri: Uri): EpubFileInfo {
        val displayName = queryDisplayName(uri) ?: "book"
        val name = sanitizeFileName(displayName)
        val dest = File(dir, name)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("无法打开所选文件")
        input.use { src ->
            FileOutputStream(dest).use { dst -> src.copyTo(dst) }
        }
        return EpubFileInfo(name, dest.length(), dest.lastModified())
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun listFiles(): List<EpubFileInfo> {
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".epub", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { EpubFileInfo(it.name, it.length(), it.lastModified()) }
            ?: emptyList()
    }

    /**
     * 写入同步下载的书籍：文件名安全化但**保留原名不重命名**（覆盖同名文件），
     * 用于云端下载覆盖本地。流式写入，不整包进内存。
     */
    fun saveDownloaded(raw: String, input: java.io.InputStream): EpubFileInfo {
        var name = raw.replace(Regex("""[\\/:*?"<>|\x00-\x1f]"""), "_").trim()
        if (name.isEmpty()) name = "book"
        if (!name.endsWith(".epub", ignoreCase = true)) name += ".epub"
        if (name.length > 100) name = name.take(90) + ".epub"
        val dest = File(dir, name)
        input.use { src -> FileOutputStream(dest).use { dst -> src.copyTo(dst, 8192) } }
        return EpubFileInfo(name, dest.length(), dest.lastModified())
    }

    fun delete(name: String): Boolean {
        return resolveFile(name)?.delete() ?: false
    }

    /** 解析文件名对应的真实文件，带 canonical 路径前缀校验防路径穿越 */
    fun resolveFile(name: String): File? {
        val dirCanonical = dir.canonicalPath
        val target = File(dir, name).canonicalFile
        if (!target.path.startsWith(dirCanonical + File.separator)) return null
        return if (target.isFile) target else null
    }
}
