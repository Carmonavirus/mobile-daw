package com.mobiledaw.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Where exports are rendered, saved for the user (Downloads) and shared from. */
object ExportStorage {
    /** Rendered files live here until the next export, so they can be shared. */
    fun workDir(context: Context): File = File(context.cacheDir, "exports").apply { mkdirs() }

    fun clearWork(context: Context) {
        workDir(context).deleteRecursively()
        workDir(context)
    }

    /** Folder shown to the user, e.g. "Download/Mobile DAW/My song". */
    fun folderLabel(context: Context, songFolder: String): String =
        if (Build.VERSION.SDK_INT >= 29) "${Environment.DIRECTORY_DOWNLOADS}/Mobile DAW/$songFolder"
        else File(legacyRoot(context), songFolder).absolutePath

    private fun legacyRoot(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, "Mobile DAW")

    /**
     * Copies a rendered file into Download/Mobile DAW/<songFolder>/<subFolder> (Android 10+, no
     * permission needed), or into the app's own Music folder on older phones.
     */
    fun saveToDownloads(context: Context, file: File, songFolder: String, subFolder: String?) {
        val rel = listOfNotNull(songFolder, subFolder).joinToString("/")
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType(file))
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Mobile DAW/$rel")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Couldn't create ${file.name} in Downloads")
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IllegalStateException("Couldn't write ${file.name} to Downloads")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            val dir = File(legacyRoot(context), rel).apply { mkdirs() }
            file.copyTo(File(dir, file.name), overwrite = true)
        }
    }

    fun mimeType(file: File): String = when (file.extension.lowercase()) {
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "mid" -> "audio/midi"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    /**
     * Packs [files] into one .zip at [out], each under "<folder>/" with its path relative to
     * [root] (so stems keep their own "<song> stems/" folder inside). WAVs barely compress, so the
     * fastest level is used: the zip is about as big as the files, but made quickly.
     */
    fun zip(files: List<File>, root: File, folder: String, out: File, onProgress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }) {
        val total = files.sumOf { it.length() }.coerceAtLeast(1L)
        var done = 0L
        var shown = -1
        val buf = ByteArray(1 shl 16)
        java.util.zip.ZipOutputStream(out.outputStream().buffered(1 shl 16)).use { zip ->
            zip.setLevel(java.util.zip.Deflater.BEST_SPEED)
            files.forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                zip.putNextEntry(java.util.zip.ZipEntry("$folder/$rel").apply { time = f.lastModified() })
                f.inputStream().use { input ->
                    while (true) {
                        if (cancelled()) throw ExportCancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        zip.write(buf, 0, n)
                        done += n
                        val pct = (done * 100 / total).toInt()
                        if (pct != shown) { shown = pct; onProgress(pct / 100f) }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.exports", file)

    /** Opens the share sheet for one or more rendered files. */
    fun share(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { uriFor(context, it) })
        val type = files.map { mimeType(it) }.distinct().singleOrNull() ?: "audio/*"
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = type
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, if (files.size == 1) "Share ${files[0].name}" else "Share ${files.size} files")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
