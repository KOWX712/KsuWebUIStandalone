package io.github.a13e300.ksuwebui

import android.content.Context
import android.os.Environment
import android.widget.Toast
import java.io.File

object WebUIFileHelper {

    fun saveContent(context: Context, content: String, filename: String) {
        saveToDownloads(context, filename) { file -> file.writeText(content) }
    }

    fun saveContent(context: Context, content: ByteArray, filename: String) {
        saveToDownloads(context, filename) { file -> file.writeBytes(content) }
    }

    private inline fun saveToDownloads(
        context: Context,
        filename: String,
        writeOperation: (File) -> Unit
    ) {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            var file = File(downloadsDir, filename)
            val name = filename.substringBeforeLast('.')
            val ext = if (filename.contains('.')) ".${filename.substringAfterLast('.')}" else ""
            var counter = 1
            while (file.exists()) {
                val newFilename = "$name ($counter)$ext"
                file = File(downloadsDir, newFilename)
                counter++
            }
            writeOperation(file)
            Toast.makeText(context, "Downloaded to Downloads/${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun sanitizeFilename(filename: String): String {
        return filename.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(255)
    }

    fun getFilename(contentDisposition: String): String {
        if (contentDisposition.isEmpty()) return "download"
        val cdParts = contentDisposition.split(";")
        for (part in cdParts) {
            if (part.trim().startsWith("filename=")) {
                return sanitizeFilename(part.substring(part.indexOf("=") + 1).trim('"'))
            }
        }
        return "download"
    }
}
