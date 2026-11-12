package com.mobiledaw.sound

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mobiledaw.audio.NativeEngine
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.pow

/**
 * SFZ instruments the user imported (a .zip holding .sfz files and their samples).
 * Our own small SFZ reader handles the common opcodes: sample, key ranges, root key,
 * velocity ranges, tuning, volume, loops and amp release.
 *
 * Source strings: "sfz:<path of the .sfz under the app's files folder>".
 */
class SfzLibrary(private val context: Context) {
    class Instrument(val source: String, val name: String, val pack: String)

    class Region(
        val sample: File,
        val loKey: Int, val hiKey: Int, val root: Int,
        val loVel: Int, val hiVel: Int,
        val tuneCents: Float, val gain: Float,
        val loop: Boolean, val loopStart: Int, val loopEnd: Int,
    )

    class Loaded(val zones: Array<FloatArray>, val rates: FloatArray, val meta: IntArray, val tuning: FloatArray, val release: Float)

    private val root = File(context.filesDir, "sfz").apply { mkdirs() }
    private val cache = object : LinkedHashMap<String, Loaded>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Loaded>?) = size > 3
    }

    fun instruments(): List<Instrument> =
        root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name.lowercase() }.flatMap { pack ->
            pack.walkTopDown().filter { it.isFile && it.extension.equals("sfz", true) }
                .sortedBy { it.name.lowercase() }
                .map { Instrument(SOURCE + it.relativeTo(context.filesDir).path, it.nameWithoutExtension, pack.name) }
                .toList()
        }

    fun nameOf(source: String?): String = source?.substringAfterLast('/')?.substringBeforeLast('.') ?: "SFZ"

    fun packs(): List<String> = root.listFiles { f -> f.isDirectory }.orEmpty().map { it.name }.sorted()

    fun deletePack(name: String) {
        File(root, name).deleteRecursively()
        synchronized(cache) { cache.clear() }
    }

    /** Imports a .zip with .sfz files and samples. Returns the pack name, or null if it held no .sfz. */
    fun importZip(uri: Uri): String? {
        val base = (displayName(uri) ?: "Instrument").substringBeforeLast('.')
        var name = safe(base).ifBlank { "Instrument" }
        var i = 2
        while (File(root, name).exists()) name = "${safe(base)} ${i++}"
        val dir = File(root, name)
        val canonical = dir.canonicalPath + File.separator
        var sfzCount = 0
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || entry.name.contains("__MACOSX")) continue
                    val ext = entry.name.substringAfterLast('.', "").lowercase()
                    if (ext !in WANTED) continue
                    val target = File(dir, entry.name)
                    if (!target.canonicalPath.startsWith(canonical)) continue  // zip slip
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                    if (ext == "sfz") sfzCount++
                }
            }
        }
        if (sfzCount == 0) {
            dir.deleteRecursively()
            return null
        }
        return name
    }

    /** Parses and decodes an instrument (slow, call off the main thread). */
    fun load(source: String): Loaded? {
        synchronized(cache) { cache[source]?.let { return it } }
        val file = File(context.filesDir, source.removePrefix(SOURCE))
        if (!file.exists()) return null
        val (regions, release) = parse(file)
        val decoded = HashMap<String, com.mobiledaw.audio.WavDecoder.Decoded?>()
        val zones = ArrayList<FloatArray>()
        val rates = ArrayList<Float>()
        val meta = ArrayList<Int>()
        val tuning = ArrayList<Float>()
        var total = 0L
        for (r in regions) {
            val d = decoded.getOrPut(r.sample.path) { AudioFiles.decode(r.sample) } ?: continue
            if (total + d.frames.size > MAX_TOTAL_FRAMES) break  // keep memory in check on huge libraries
            total += d.frames.size
            zones += d.frames
            rates += d.sampleRate
            meta += listOf(r.loKey, r.hiKey, r.root, r.loVel, r.hiVel, if (r.loop) 1 else 0, r.loopStart, r.loopEnd)
            tuning += listOf(r.tuneCents, r.gain)
        }
        if (zones.isEmpty()) return null
        val loaded = Loaded(zones.toTypedArray(), rates.toFloatArray(), meta.toIntArray(), tuning.toFloatArray(), release)
        synchronized(cache) { cache[source] = loaded }
        return loaded
    }

    fun sendTo(slot: Int, loaded: Loaded?) {
        if (loaded == null) NativeEngine.setMultiSample(slot, null, null, null, null, 0.25f)
        else NativeEngine.setMultiSample(slot, loaded.zones, loaded.rates, loaded.meta, loaded.tuning, loaded.release)
    }

    // ---------------------------------------------------------------- parsing

    private fun parse(file: File): Pair<List<Region>, Float> {
        val text = file.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        val control = HashMap<String, String>()
        val global = HashMap<String, String>()
        val master = HashMap<String, String>()
        val group = HashMap<String, String>()
        var region: HashMap<String, String>? = null
        var section = ""
        val regions = ArrayList<Map<String, String>>()
        fun closeRegion() {
            region?.let { regions += global + master + group + it }
            region = null
        }
        for (rawLine in text.lines()) {
            val line = rawLine.substringBefore("//").trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            // A line can hold headers and opcodes mixed: split at each <header>.
            var rest = line
            while (rest.isNotEmpty()) {
                val h = Regex("<(\\w+)>").find(rest)
                val before = if (h == null) rest else rest.substring(0, h.range.first)
                val target = when (section) {
                    "control" -> control
                    "global" -> global
                    "master" -> master
                    "group" -> group
                    "region" -> region
                    else -> null
                }
                if (target != null) parseOpcodes(before, target)
                if (h == null) break
                when (h.groupValues[1]) {
                    "region" -> { closeRegion(); region = HashMap(); section = "region" }
                    "group" -> { closeRegion(); group.clear(); section = "group" }
                    "master" -> { closeRegion(); master.clear(); group.clear(); section = "master" }
                    "global" -> { closeRegion(); global.clear(); section = "global" }
                    "control" -> { closeRegion(); section = "control" }
                    else -> { closeRegion(); section = "other" }
                }
                rest = rest.substring(h.range.last + 1)
            }
        }
        closeRegion()
        val defaultPath = control["default_path"].orEmpty().replace('\\', '/')
        val dir = file.parentFile ?: file
        var release = 0.25f
        val out = regions.mapNotNull { op ->
            val sampleName = op["sample"]?.replace('\\', '/') ?: return@mapNotNull null
            if (sampleName.startsWith("*")) return@mapNotNull null  // built-in generators (*sine...) aren't supported
            val sample = File(dir, defaultPath + sampleName).takeIf { it.exists() } ?: findCaseInsensitive(dir, defaultPath + sampleName)
                ?: return@mapNotNull null
            val key = op["key"]?.let(::noteNumber)
            val lo = op["lokey"]?.let(::noteNumber) ?: key ?: 0
            val hi = op["hikey"]?.let(::noteNumber) ?: key ?: 127
            val rootKey = op["pitch_keycenter"]?.let(::noteNumber) ?: key ?: 60
            val transpose = op["transpose"]?.toIntOrNull() ?: 0
            val tune = (op["tune"]?.toFloatOrNull() ?: 0f) + transpose * 100f
            val volume = op["volume"]?.toFloatOrNull() ?: 0f
            val loopMode = op["loop_mode"] ?: op["loopmode"]
            val loop = loopMode == "loop_continuous" || loopMode == "loop_sustain"
            op["ampeg_release"]?.toFloatOrNull()?.let { release = it.coerceIn(0.01f, 8f) }
            Region(
                sample = sample,
                loKey = lo.coerceIn(0, 127), hiKey = hi.coerceIn(0, 127), root = rootKey.coerceIn(0, 127),
                loVel = op["lovel"]?.toIntOrNull()?.coerceIn(0, 127) ?: 0,
                hiVel = op["hivel"]?.toIntOrNull()?.coerceIn(0, 127) ?: 127,
                tuneCents = tune,
                gain = 10f.pow(volume / 20f),
                loop = loop,
                loopStart = (op["loop_start"] ?: op["loopstart"])?.toIntOrNull() ?: 0,
                loopEnd = (op["loop_end"] ?: op["loopend"])?.toIntOrNull() ?: 0,
            )
        }
        return out to release
    }

    /** Opcodes are key=value pairs; values (sample paths) may contain spaces. */
    private fun parseOpcodes(text: String, into: MutableMap<String, String>) {
        val matches = Regex("([A-Za-z0-9_]+)=").findAll(text).toList()
        matches.forEachIndexed { i, m ->
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            into[m.groupValues[1]] = text.substring(m.range.last + 1, end).trim()
        }
    }

    private fun findCaseInsensitive(dir: File, relative: String): File? {
        var current = dir
        for (part in relative.split('/').filter { it.isNotEmpty() }) {
            current = when (part) {
                "." -> current
                ".." -> current.parentFile ?: return null
                else -> current.listFiles()?.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
            }
        }
        return current.takeIf { it.isFile }
    }

    private fun safe(name: String) = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    companion object {
        const val SOURCE = "sfz:"
        private val WANTED = setOf("sfz", "wav", "flac", "ogg", "mp3", "aif", "aiff")
        private const val MAX_TOTAL_FRAMES = 24_000_000L  // about 100 MB of samples

        /** "60", "c4", "C#3", "eb2" -> MIDI note (c4 = 60, as in SFZ). */
        fun noteNumber(text: String): Int? {
            text.trim().toIntOrNull()?.let { return it }
            val m = Regex("^([a-gA-G])([#b]?)(-?\\d+)$").find(text.trim()) ?: return null
            val base = mapOf('c' to 0, 'd' to 2, 'e' to 4, 'f' to 5, 'g' to 7, 'a' to 9, 'b' to 11)[m.groupValues[1].lowercase()[0]]!!
            val accidental = when (m.groupValues[2]) { "#" -> 1; "b" -> -1; else -> 0 }
            return (m.groupValues[3].toInt() + 1) * 12 + base + accidental
        }
    }
}
