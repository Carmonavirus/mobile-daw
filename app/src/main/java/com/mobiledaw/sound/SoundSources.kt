package com.mobiledaw.sound

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.mobiledaw.audio.NativeEngine
import com.mobiledaw.audio.WavDecoder
import com.mobiledaw.model.Instrument
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** A built-in preset of the Macro or Modeled engine. */
class EnginePreset(val instrument: Instrument, val index: Int, val name: String, val group: String, val drum: Boolean, val pitch: Int, val library: String)

/** A sound picked in the library: either a sample file or an engine instrument. */
sealed class SoundChoice {
    abstract val name: String

    /** A .wav in the app's files folder (kits and saved online sounds). */
    class Sample(val path: String, override val name: String, val group: String) : SoundChoice()

    /** An extra-engine instrument. [pitch] fixes the pitch steps play (GM drums, drum models). */
    class Engine(
        val instrument: Instrument,
        val variant: Int,
        val source: String?,
        val pitch: Int?,
        override val name: String,
    ) : SoundChoice()
}

/** Free sites to download sound packs from, then import as a kit / SoundFont / SFZ. */
class FreeSite(val name: String, val url: String, val what: String, val terms: String)

/** Everything the sound library knows about: local banks, engines, online clients and credits. */
class SoundSources(private val context: Context) {
    val soundFonts = SoundFontLibrary(context)
    val sfz = SfzLibrary(context)
    val freesound = FreesoundClient(context)

    /** Bumped when imports, downloads or credits change, so screens refresh. */
    var version by mutableIntStateOf(0)
        private set

    fun changed() {
        version++
    }

    val macroPresets: List<EnginePreset> by lazy { presets(Instrument.MACRO) }
    val modeledPresets: List<EnginePreset> by lazy { presets(Instrument.MODELED) }

    private fun presets(instrument: Instrument): List<EnginePreset> =
        NativeEngine.enginePresets(instrument.id).mapIndexed { i, info ->
            val p = info.split('|')
            EnginePreset(
                instrument, i, p[0], p.getOrElse(1) { "" }, p.getOrElse(2) { "0" } == "1",
                p.getOrElse(3) { "60" }.toIntOrNull() ?: 60,
                p.getOrElse(4) { if (instrument == Instrument.MACRO) "Plaits" else "" },
            )
        }

    // ---------------------------------------------------------------- credits

    private val creditsFile = File(context.filesDir, "credits.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Synchronized
    fun credits(): List<Credit> = runCatching {
        json.decodeFromString(ListSerializer(Credit.serializer()), creditsFile.readText())
    }.getOrDefault(emptyList()).filter { File(context.filesDir, it.path).exists() }

    @Synchronized
    fun creditFor(path: String): Credit? = credits().firstOrNull { it.path == path }

    @Synchronized
    private fun addCredit(c: Credit) {
        val list = credits().filter { it.path != c.path } + c
        creditsFile.writeText(json.encodeToString(ListSerializer(Credit.serializer()), list))
    }

    // ---------------------------------------------------------------- online sounds

    private val previewDir = File(context.cacheDir, "previews").apply { mkdirs() }

    /** Downloads (cached) and decodes a sound's preview. Slow: call off the main thread. */
    fun fetchPreview(sound: OnlineSound): WavDecoder.Decoded? {
        val ext = sound.previewUrl.substringAfterLast('.', "mp3").substringBefore('?').take(5)
        val file = File(previewDir, "${sound.source.name}_${sound.id.hashCode()}.$ext")
        if (!file.exists()) {
            val tmp = File(file.path + ".part")
            Http.download(sound.previewUrl, tmp, maxBytes = 40L shl 20)
            tmp.renameTo(file)
        }
        trimPreviewCache()
        return AudioFiles.decode(file)
    }

    private fun trimPreviewCache() {
        val files = previewDir.listFiles().orEmpty().sortedByDescending { it.lastModified() }
        files.drop(40).forEach { it.delete() }
    }

    /**
     * Saves an online sound into the kit named after its library (e.g. kits/Freesound), as WAV,
     * and records its credit. Uses the original file when it can, else the preview.
     * Returns the saved sound's path, relative to the files folder.
     */
    fun save(sound: OnlineSound): String {
        val kitDir = File(context.filesDir, "kits/${sound.source.label}").apply { mkdirs() }
        val base = sound.title.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(60).trim().ifEmpty { "Sound" }
        var target = File(kitDir, "$base.wav")
        var n = 2
        while (target.exists()) target = File(kitDir, "$base $n.wav").also { n++ }

        var decoded: WavDecoder.Decoded? = null
        if (sound.source == OnlineSource.FREESOUND && freesound.signedIn) {
            val original = File(previewDir, "original_${sound.id}")
            if (freesound.downloadOriginal(sound, original)) decoded = AudioFiles.decode(original)
            original.delete()
        } else if (sound.fileUrl != null && sound.fileUrl != sound.previewUrl) {
            val ext = sound.fileUrl.substringAfterLast('.', "").take(5)
            val original = File(previewDir, "original_${sound.id.hashCode()}.$ext")
            runCatching { Http.download(sound.fileUrl, original) }.onSuccess { decoded = AudioFiles.decode(original) }
            original.delete()
        }
        val audio = decoded ?: fetchPreview(sound) ?: throw java.io.IOException("Couldn't read that sound")
        AudioFiles.writeWav(target, audio.frames, audio.sampleRate.toInt())
        val path = target.relativeTo(context.filesDir).path
        addCredit(
            Credit(
                path = path,
                title = sound.title,
                author = sound.author,
                source = sound.source.label,
                license = sound.licenseName,
                licenseUrl = sound.licenseUrl,
                pageUrl = sound.pageUrl,
            ),
        )
        return path
    }

    companion object {
        val FREE_SITES = listOf(
            FreeSite("99Sounds", "https://99sounds.org", "Free sample packs and drum kits", "Royalty-free for your music. Download the .zip, then import it in Drum kits."),
            FreeSite("Bedroom Producers Blog", "https://bedroomproducersblog.com/free-samples/", "Round-ups of free sample packs", "Each pack has its own terms; most allow use in music. Import the .zip in Drum kits."),
            FreeSite("Philharmonia sound samples", "https://philharmonia.co.uk/resources/sound-samples/", "Orchestral one-shots, every instrument", "Free to use in music; check their terms page. Import the .zip in Drum kits."),
            FreeSite("Looperman", "https://www.looperman.com", "Community loops and acapellas", "Royalty-free for music, credit the creator. Download, then import in Drum kits."),
            FreeSite("Cymatics free vault", "https://cymatics.fm/pages/free-download-vault", "Free drum kits and loops (email sign-up)", "Use in your music only. Import the .zip in Drum kits."),
            FreeSite("Musical Artifacts", "https://musical-artifacts.com/artifacts?formats=sf2", "Hundreds of free SoundFonts and SFZ instruments", "Each file lists its licence. Import .sf2 in SoundFonts or a .zip in SFZ instruments."),
            FreeSite("Pianobook", "https://www.pianobook.co.uk", "Free sampled instruments", "Free to use in music. Some packs come as SFZ; import them as a .zip."),
            FreeSite("FluidR3 GM", "https://member.keymusician.com/Member/FluidR3_GM/index.html", "A large General MIDI SoundFont (MIT licence)", "Free, including commercial use. Import the .sf2 in SoundFonts."),
            FreeSite("GeneralUser GS", "https://www.schristiancollins.com/generaluser", "The General MIDI bank built into this app", "Already included. Updates and other instruments by the same author."),
        )
    }
}
