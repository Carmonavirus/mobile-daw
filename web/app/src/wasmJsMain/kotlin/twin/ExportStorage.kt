// Web version of app/src/main/java/com/mobiledaw/export/ExportStorage.kt: "save" downloads the file
// through the browser, "share" uses the phone's share sheet when the browser has one.
@file:Suppress("PackageDirectoryMismatch", "UNUSED_PARAMETER")

package com.mobiledaw.export

import android.content.Context
import com.mobiledaw.web.toJs
import org.khronos.webgl.Int8Array
import java.io.File

private external fun downloadFile(name: String, bytes: Int8Array, mime: String)
private external fun shareClear()
private external fun shareAdd(name: String, bytes: Int8Array, mime: String)
private external fun shareGo(title: String)

object ExportStorage {
    fun workDir(context: Context): File = File(context.cacheDir, "exports").apply { mkdirs() }

    fun clearWork(context: Context) {
        workDir(context).deleteRecursively()
        workDir(context)
    }

    fun folderLabel(context: Context, songFolder: String): String = "your Downloads folder"

    fun saveToDownloads(context: Context, file: File, songFolder: String, subFolder: String?) {
        downloadFile(file.name, file.readBytes().toJs(), mimeType(file))
    }

    fun mimeType(file: File): String = when (file.extension.lowercase()) {
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "mid" -> "audio/midi"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    fun zip(files: List<File>, root: File, folder: String, out: File, onProgress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }) {
        val entries = files.map { f -> "$folder/${f.relativeTo(root).invariantSeparatorsPath}" to f.readBytes() }
        if (cancelled()) throw ExportCancelled()
        out.writeBytes(com.mobiledaw.web.Unzip.zip(entries))
        onProgress(1f)
    }

    fun share(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        shareClear()
        for (f in files) shareAdd(f.name, f.readBytes().toJs(), mimeType(f))
        shareGo(if (files.size == 1) "Share ${files[0].name}" else "Share ${files.size} files")
    }
}
