package com.mobiledaw.model

import android.content.Context
import com.mobiledaw.audio.SynthEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The sound of a wavetable-synth channel. Only values that differ from the defaults need to be
 * in [params]; names are the C++ parameter names (see wavesynth/WaveSynth.cpp).
 */
@Serializable
data class SynthPatch(
    /** The preset this sound started from, shown in the editor. */
    val preset: String = "Init",
    val category: String = "",
    val params: Map<String, Float> = emptyMap(),
    /** True once a knob was turned after loading the preset. */
    val edited: Boolean = false,
) {
    fun value(name: String): Float = params[name] ?: SynthParams.byName(name)?.default ?: 0f

    fun with(name: String, v: Float): SynthPatch = copy(params = params + (name to v), edited = true)

    /** All values in engine order, ready for [SynthEngine.setParams]. */
    fun toArray(): FloatArray = FloatArray(SynthParams.all.size) { i -> value(SynthParams.all[i].name) }

    companion object {
        fun from(preset: SynthPreset) = SynthPatch(preset.name, preset.category, preset.params)
    }
}

/** A stored preset, in the same format as assets/wavesynth/factory_presets.json. */
@Serializable
data class SynthPreset(
    val name: String,
    val category: String,
    val params: Map<String, Float> = emptyMap(),
)

@Serializable
private data class PresetFile(val version: Int = 1, val presets: List<SynthPreset> = emptyList())

/** Parameter ranges, read once from the engine. */
object SynthParams {
    class Info(val index: Int, val name: String, val min: Float, val max: Float, val default: Float, val stepped: Boolean)

    val all: List<Info> by lazy {
        val names = SynthEngine.paramNames()
        val r = SynthEngine.paramRanges()
        names.mapIndexed { i, n -> Info(i, n, r[i * 4], r[i * 4 + 1], r[i * 4 + 2], r[i * 4 + 3] > 0.5f) }
    }

    private val map by lazy { all.associateBy { it.name } }

    fun byName(name: String): Info? = map[name]

    val tables: List<String> by lazy { SynthEngine.tableNames().toList() }
}

/** Factory presets from the app's assets plus the user's own, saved as JSON files. */
class SynthPresetLibrary(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val dir = File(context.filesDir, "synth_presets").apply { mkdirs() }

    val factory: List<SynthPreset> by lazy {
        runCatching {
            context.assets.open("wavesynth/factory_presets.json").use { stream ->
                json.decodeFromString(PresetFile.serializer(), stream.readBytes().decodeToString()).presets
            }
        }.getOrDefault(emptyList())
    }

    fun user(): List<SynthPreset> =
        dir.listFiles { f -> f.extension == "json" }
            ?.sortedBy { it.name.lowercase() }
            ?.mapNotNull { f -> runCatching { json.decodeFromString(SynthPreset.serializer(), f.readText()) }.getOrNull() }
            .orEmpty()

    fun save(preset: SynthPreset) {
        file(preset.name).writeText(json.encodeToString(SynthPreset.serializer(), preset))
    }

    fun delete(name: String) {
        file(name).delete()
    }

    fun exists(name: String) = file(name).exists()

    private fun file(name: String) = File(dir, name.replace(Regex("[^A-Za-z0-9 _-]"), "_").ifBlank { "Preset" } + ".json")

    companion object {
        val CATEGORIES = listOf("808", "Bass", "Pluck", "Pad", "Lead", "Bells", "Keys")
        const val USER = "Yours"
    }
}
