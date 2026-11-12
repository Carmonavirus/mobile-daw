package com.mobiledaw.model

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.mobiledaw.sound.AudioFiles
import java.io.File
import kotlin.math.roundToInt

/**
 * Audio files brought into the app, from the playlist's "Add audio file" picker or from
 * "Share" / "Open with" in a browser, Files or Downloads (e.g. a loop downloaded from Looperman).
 * Any format Android can decode (WAV, MP3, OGG, FLAC, M4A...) is converted to WAV and copied
 * into the app (files/imports/), so projects keep working even after the download is deleted.
 */
class AudioImport(private val context: Context) {
    private val dir = File(context.filesDir, DIR)

    class Imported(val path: String, val name: String, val seconds: Float)

    /** The audio files an incoming share / open-with intent carries (none for a normal launch). */
    fun urisFrom(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        val list = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.streamExtra())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamListExtra()
            else -> return emptyList()
        }
        // Some apps put the files only in ClipData.
        val clip = intent.clipData
        val fromClip = if (list.isEmpty() && clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } else emptyList()
        return (list + fromClip).distinct()
    }

    /** Copies and converts one file. Returns null if it isn't audio Android can read. */
    fun import(uri: Uri): Imported? {
        val display = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Audio"
        val ext = display.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 } ?: "audio"
        val tmp = File(context.cacheDir, "import-${System.nanoTime()}.$ext")
        try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return null
            if (copied <= 0) return null
            val decoded = AudioFiles.decode(tmp) ?: return null
            if (decoded.frames.isEmpty() || decoded.sampleRate <= 0f) return null
            dir.mkdirs()
            val name = cleanName(display)
            val target = uniqueFile(name)
            AudioFiles.writeWav(target, decoded.frames, decoded.sampleRate.roundToInt())
            return Imported(target.relativeTo(context.filesDir).path, name, decoded.frames.size / decoded.sampleRate)
        } finally {
            tmp.delete()
        }
    }

    /** "looperman-l-1234567-0345678-dark-trap-melody.mp3" -> "dark trap melody". */
    private fun cleanName(display: String): String {
        var name = display.substringBeforeLast('.').trim()
        name = name.replace(Regex("^looperman-[a-z]-\\d+-\\d+-?", RegexOption.IGNORE_CASE), "")
        name = name.replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ").trim()
        name = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        return name.take(40).trim().ifEmpty { "Audio" }
    }

    private fun uniqueFile(base: String): File {
        var f = File(dir, "$base.wav")
        var i = 2
        while (f.exists()) f = File(dir, "$base ${i++}.wav")
        return f
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun Intent.streamExtra(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    @Suppress("DEPRECATION")
    private fun Intent.streamListExtra(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()

    companion object {
        const val DIR = "imports"
    }
}
