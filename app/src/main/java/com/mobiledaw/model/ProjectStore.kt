package com.mobiledaw.model

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/** Saves projects as JSON files in the app's private storage. */
class ProjectStore(context: Context) {
    /** The folder the project files live in (Google Drive sync reads and writes it too). */
    val dir = File(context.filesDir, "projects").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("daw", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    /** Called after a project file is saved, deleted or renamed (starts a Google Drive sync). */
    var onChange: (() -> Unit)? = null

    fun list(): List<String> =
        dir.listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.nameWithoutExtension }
            .orEmpty()

    fun save(project: Project) {
        val target = file(project.name)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(json.encodeToString(Project.serializer(), project))
        tmp.renameTo(target)
        prefs.edit().putString(LAST, project.name).apply()
        onChange?.invoke()
    }

    /** Saves a project without making it the one that opens next time (sync backup copies). */
    fun saveCopy(project: Project) {
        file(project.name).writeText(json.encodeToString(Project.serializer(), project))
    }

    /** Reads a project from raw file bytes, or null when they aren't a project. */
    fun decode(bytes: ByteArray): Project? = runCatching {
        json.decodeFromString(Project.serializer(), bytes.decodeToString())
    }.getOrNull()

    fun load(name: String): Project? = runCatching {
        json.decodeFromString(Project.serializer(), file(name).readText())
    }.getOrNull()

    fun delete(name: String) {
        file(name).delete()
        onChange?.invoke()
    }

    fun rename(from: String, to: String) {
        if (from != to) file(from).renameTo(file(to))
        onChange?.invoke()
    }

    fun exists(name: String) = file(name).exists()

    /** True when both names are stored in the same file (list() gives the file's name). */
    fun sameFile(a: String, b: String) = file(a) == file(b)

    fun lastOpened(): String? = prefs.getString(LAST, null)

    fun uniqueName(base: String): String {
        if (!exists(base)) return base
        var i = 2
        while (exists("$base $i")) i++
        return "$base $i"
    }

    /** The file name (without .json) a project with this name is stored under. */
    fun key(name: String) = sanitize(name)

    private fun file(name: String) = File(dir, sanitize(name) + ".json")

    private fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9 _-]"), "_").ifBlank { "Untitled" }

    private companion object {
        const val LAST = "last_project"
    }
}
