// The browser side of things the Android app gets from the phone: picked files, the file and
// folder pickers, opening links, the APK's assets, zip files and decoded audio.
package com.mobiledaw.web

import android.net.Uri
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Int8Array

// resources/platform.js
private external fun pickFiles(accept: String, multiple: Boolean, onFile: (String, Int8Array, Int, Float32Array?, Float32Array?) -> Unit, onDone: () -> Unit)
private external fun pickFolder(onFile: (String, Int8Array) -> Unit, onDone: (String?) -> Unit)
private external fun micPermission(): String
private external fun askMicPermission(done: (Boolean) -> Unit)
private external fun openUrl(url: String)
private external fun assetGet(name: String): Int8Array?
private external fun assetList(path: String): String
private external fun unzipAll(bytes: Int8Array, onEntry: (String, Int8Array?) -> Unit)
private external fun zipNew(): JsAny
private external fun zipAdd(zip: JsAny, name: String, bytes: Int8Array)
private external fun zipFinish(zip: JsAny): Int8Array
private external fun buildVersion(): String
private external fun buildTime(): Double

object WebApp {
    val context: android.app.Application by lazy { android.app.Application() }
}

object WebBuild {
    val VERSION: String by lazy { buildVersion() }
    val TIME: Long by lazy { buildTime().toLong() }
}

/** Files the person picked with the browser's file or folder picker, until the page closes. */
object Picked {
    private class Folder(val name: String, val files: LinkedHashMap<String, ByteArray>)

    private val files = HashMap<String, ByteArray>()
    private val folders = HashMap<String, Folder>()
    private var next = 0

    fun add(name: String, bytes: ByteArray): Uri {
        val key = "f${next++}"
        files[key] = bytes
        return Uri(key, name)
    }

    fun bytes(uri: Uri): ByteArray? = files[uri.key] ?: folders[uri.key]?.files?.get(uri.name)

    fun addFolder(name: String, entries: LinkedHashMap<String, ByteArray>): Uri {
        val key = "d${next++}"
        folders[key] = Folder(name, entries)
        return Uri(key, name)
    }

    fun folderName(key: String): String? = folders[key]?.name
    fun folderPaths(key: String): List<String> = folders[key]?.files?.keys?.toList().orEmpty()

    /** A file inside a picked folder: same key as the folder, [rel] as its name. */
    fun fileUri(key: String, rel: String): Uri = Uri(key, rel)
}

object Picker {
    // [accept] is Android's MIME filter, e.g. audio/* or application/zip; */* means anything.
    fun pickFiles(accept: String, multiple: Boolean, done: (List<Uri>) -> Unit) {
        val picked = ArrayList<Uri>()
        pickFiles(
            if (accept == "*/*") "" else accept,
            multiple,
            { name, bytes, rate, left, right ->
                val data = bytes.toKotlin()
                // Compressed audio was decoded by the browser; MediaExtractor finds it by its bytes.
                if (left != null && rate > 0) DecodedAudio.register(data, rate, listOfNotNull(left.toKotlin(), right?.toKotlin()))
                picked += Picked.add(name, data)
            },
            { done(picked) },
        )
    }

    fun pickFolder(done: (Uri?) -> Unit) {
        val entries = LinkedHashMap<String, ByteArray>()
        pickFolder(
            { path, bytes -> entries[path] = bytes.toKotlin() },
            { name -> done(if (name == null) null else Picked.addFolder(name, entries)) },
        )
    }

    fun permissionState(permission: String): Int = if (micPermission() == "granted") 0 else -1

    fun requestPermission(permission: String, done: (Boolean) -> Unit) = askMicPermission(done)
}

object Browser {
    fun open(url: String) = openUrl(url)
}

/** The APK's assets folder (app/src/main/assets), served next to the page. */
object Assets {
    fun get(name: String): ByteArray? = assetGet(name)?.toKotlin()
    fun list(path: String): List<String> = assetList(path).split('\n').filter { it.isNotEmpty() }
}

object Unzip {
    fun unzip(bytes: ByteArray): List<java.util.zip.ZipEntry> {
        val out = ArrayList<java.util.zip.ZipEntry>()
        unzipAll(bytes.toJs()) { name, data -> out += java.util.zip.ZipEntry(name, data?.toKotlin()) }
        return out
    }

    fun zip(files: List<Pair<String, ByteArray>>): ByteArray {
        val z = zipNew()
        for ((name, data) in files) zipAdd(z, name, data.toJs())
        return zipFinish(z).toKotlin()
    }
}

/**
 * Audio the browser decoded when it was picked (MP3, M4A, OGG, FLAC...), found again by the file's
 * bytes, so the app's own import code (MediaExtractor + MediaCodec) can read it.
 */
object DecodedAudio {
    class Entry(val rate: Int, val channels: List<FloatArray>) {
        val frames: Int get() = channels.firstOrNull()?.size ?: 0
    }

    private val entries = HashMap<String, Entry>()

    private fun fingerprint(bytes: ByteArray): String {
        var h = 0x811c9dc5.toInt()
        val n = minOf(bytes.size, 65536)
        for (i in 0 until n) h = (h xor bytes[i].toInt()) * 0x01000193
        for (i in maxOf(n, bytes.size - 4096) until bytes.size) h = (h xor bytes[i].toInt()) * 0x01000193
        return "${bytes.size}:$h"
    }

    fun register(bytes: ByteArray, rate: Int, channels: List<FloatArray>) {
        entries[fingerprint(bytes)] = Entry(rate, channels)
    }

    fun find(bytes: ByteArray): Entry? = entries[fingerprint(bytes)]
}
