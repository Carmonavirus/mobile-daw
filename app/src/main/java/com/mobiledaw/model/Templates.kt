package com.mobiledaw.model

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A channel as stored in a template; [key] links it to the template's notes. */
@Serializable
data class TemplateChannel(
    val key: Int,
    val name: String,
    val instrument: Instrument,
    val sample: String? = null,
    val volume: Float = 0.8f,
    val pan: Float = 0f,
    val variant: Int = 0,
    val source: String? = null,
    val pitch: Int? = null,
)

/** A saved pattern plus the sounds it uses, ready to drop into any project. */
@Serializable
data class PatternTemplate(
    val name: String,
    val bars: Int,
    val channels: List<TemplateChannel>,
    val notes: Map<Int, List<Note>>,
) {
    companion object {
        fun from(name: String, project: Project, pattern: Pattern): PatternTemplate {
            val used = project.channels.filter { pattern.notesFor(it.id).isNotEmpty() }
            val channels = used.mapIndexed { i, c -> TemplateChannel(i, c.name, c.instrument, c.sample, c.volume, c.pan, c.variant, c.source, c.pitch) }
            val notes = used.mapIndexed { i, c -> i to pattern.notesFor(c.id) }.toMap()
            return PatternTemplate(name, pattern.bars, channels, notes)
        }
    }

    /**
     * Adds this template to [project] as a new pattern. Channels are reused when the project
     * already has the same sound, otherwise added. Returns the project and the new pattern's id.
     */
    fun applyTo(project: Project, maxChannels: Int): Pair<Project, Long> {
        val channels = project.channels.toMutableList()
        var nextId = project.nextId()
        val idFor = mutableMapOf<Int, Long>()
        for (tc in this.channels) {
            val same = channels.firstOrNull { it.instrument == tc.instrument && it.sample == tc.sample && it.variant == tc.variant && it.source == tc.source && it.name == tc.name }
                ?: channels.firstOrNull { it.instrument == tc.instrument && it.sample == tc.sample && it.variant == tc.variant && it.source == tc.source && !idFor.containsValue(it.id) }
            if (same != null) {
                idFor[tc.key] = same.id
            } else if (channels.size < maxChannels) {
                val ch = Channel(nextId++, tc.name, tc.instrument, tc.volume, tc.pan, sample = tc.sample, variant = tc.variant, source = tc.source, pitch = tc.pitch)
                channels += ch
                idFor[tc.key] = ch.id
            }
        }
        val patternId = nextId
        val pattern = Pattern(
            id = patternId,
            name = name,
            bars = bars,
            notes = notes.mapNotNull { (key, list) -> idFor[key]?.let { it to list } }.toMap(),
        )
        return project.copy(channels = channels, patterns = project.patterns + pattern) to patternId
    }
}

/** Templates are saved as JSON files in the app's storage, shared by all projects. */
class TemplateStore(context: Context) {
    private val dir = File(context.filesDir, "templates").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun list(): List<PatternTemplate> =
        dir.listFiles { f -> f.extension == "json" }
            ?.sortedBy { it.name.lowercase() }
            ?.mapNotNull { f -> runCatching { json.decodeFromString(PatternTemplate.serializer(), f.readText()) }.getOrNull() }
            .orEmpty()

    fun save(template: PatternTemplate) {
        file(template.name).writeText(json.encodeToString(PatternTemplate.serializer(), template))
    }

    fun delete(name: String) {
        file(name).delete()
    }

    private fun file(name: String) =
        File(dir, name.replace(Regex("[^A-Za-z0-9 _-]"), "_").ifBlank { "Template" } + ".json")
}
