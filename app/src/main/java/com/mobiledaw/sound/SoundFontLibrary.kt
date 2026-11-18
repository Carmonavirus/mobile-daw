package com.mobiledaw.sound

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mobiledaw.audio.NativeEngine
import java.io.File

/**
 * SoundFont banks: GeneralUser GS (bundled in the APK) plus any .sf2/.sf3 the user imports.
 * A bank is loaded into the native engine once, the first time something needs it.
 *
 * Sources are strings stored in [com.mobiledaw.model.Channel.source]:
 * "sf:builtin" for the bundled bank, "sf:file:<path under the app's files folder>" for imports.
 */
class SoundFontLibrary(private val context: Context) {
    class Font(val source: String, val name: String, val builtIn: Boolean, val credit: String)

    class Preset(val index: Int, val bank: Int, val program: Int, val name: String) {
        val isDrumKit: Boolean get() = bank == 128
        val category: String get() = if (isDrumKit) "Drum kits" else GM_CATEGORIES[(program / 8).coerceIn(0, 15)]
    }

    private val root = File(context.filesDir, "soundfonts").apply { mkdirs() }
    private val banks = mutableMapOf<String, Int>()
    private val presets = mutableMapOf<String, List<Preset>>()

    fun fonts(): List<Font> {
        val imported = root.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("sf2", "sf3") }
            .orEmpty()
            .sortedBy { it.name.lowercase() }
            .map { Font(SOURCE_FILE + it.relativeTo(context.filesDir).path, it.nameWithoutExtension, false, "Imported by you") }
        return listOf(Font(BUILTIN, "GeneralUser GS", true, "by S. Christian Collins · free to use and share")) + imported
    }

    fun nameOf(source: String?): String = fonts().firstOrNull { it.source == source }?.name ?: "SoundFont"

    /** Bank id if the font is already in the engine. */
    @Synchronized
    fun loadedBank(source: String?): Int? = source?.let { banks[it] }

    /** Loads the font into the engine (slow, call off the main thread). Returns the bank id or null. */
    fun load(source: String): Int? {
        synchronized(this) { banks[source]?.let { return it } }
        val bank = when {
            source == BUILTIN -> NativeEngine.loadSoundFontAsset(context.assets, BUILTIN_ASSET)
            source.startsWith(SOURCE_FILE) -> {
                val file = File(context.filesDir, source.removePrefix(SOURCE_FILE))
                if (file.exists()) NativeEngine.loadSoundFontFile(file.path) else -1
            }
            else -> -1
        }
        if (bank < 0) return null
        val list = NativeEngine.soundFontPresets(bank).mapIndexed { i, s ->
            val parts = s.split('|', limit = 3)
            Preset(i, parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0, parts.getOrElse(2) { "Preset ${i + 1}" }.trim())
        }.sortedWith(compareBy({ it.bank }, { it.program }))
        synchronized(this) {
            // Two threads may have loaded it at once; keep the first.
            banks[source]?.let { return it }
            banks[source] = bank
            presets[source] = list
        }
        return bank
    }

    @Synchronized
    fun presets(source: String): List<Preset> = presets[source].orEmpty()

    /** Copies a .sf2/.sf3 into the app. Returns its source string, or null. */
    fun import(uri: Uri): String? {
        val name = displayName(uri) ?: "SoundFont.sf2"
        val ext = name.substringAfterLast('.', "sf2").lowercase()
        if (ext !in setOf("sf2", "sf3")) return null
        var target = File(root, safe(name))
        var i = 2
        while (target.exists()) target = File(root, safe(name.substringBeforeLast('.')) + " $i.$ext").also { i++ }
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return null
        return SOURCE_FILE + target.relativeTo(context.filesDir).path
    }

    /** Removes an imported font from the list. Its memory is freed when the app restarts. */
    fun delete(source: String) {
        if (!source.startsWith(SOURCE_FILE)) return
        File(context.filesDir, source.removePrefix(SOURCE_FILE)).delete()
    }

    private fun safe(name: String) = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    companion object {
        const val BUILTIN = "sf:builtin"
        const val SOURCE_FILE = "sf:file:"
        const val BUILTIN_ASSET = "soundfonts/GeneralUser-GS.sf2"

        val GM_CATEGORIES = listOf(
            "Piano", "Chromatic percussion", "Organ", "Guitar", "Bass", "Strings", "Ensemble", "Brass",
            "Reed", "Pipe", "Synth lead", "Synth pad", "Synth effects", "Ethnic", "Percussive", "Sound effects",
        )

        /** General MIDI percussion map (key -> name), used for drum kit presets. */
        val GM_DRUMS: Map<Int, String> = linkedMapOf(
            35 to "Acoustic kick", 36 to "Kick", 37 to "Side stick", 38 to "Snare", 39 to "Clap",
            40 to "Electric snare", 41 to "Low floor tom", 42 to "Closed hi-hat", 43 to "High floor tom",
            44 to "Pedal hi-hat", 45 to "Low tom", 46 to "Open hi-hat", 47 to "Low-mid tom", 48 to "Hi-mid tom",
            49 to "Crash", 50 to "High tom", 51 to "Ride", 52 to "Chinese cymbal", 53 to "Ride bell",
            54 to "Tambourine", 55 to "Splash", 56 to "Cowbell", 57 to "Crash 2", 58 to "Vibraslap",
            59 to "Ride 2", 60 to "Hi bongo", 61 to "Low bongo", 62 to "Mute hi conga", 63 to "Open hi conga",
            64 to "Low conga", 65 to "High timbale", 66 to "Low timbale", 67 to "High agogo", 68 to "Low agogo",
            69 to "Cabasa", 70 to "Maracas", 71 to "Short whistle", 72 to "Long whistle", 73 to "Short guiro",
            74 to "Long guiro", 75 to "Claves", 76 to "Hi wood block", 77 to "Low wood block", 78 to "Mute cuica",
            79 to "Open cuica", 80 to "Mute triangle", 81 to "Open triangle",
        )
    }
}
