package com.mobiledaw.model

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/** Saves projects as JSON files in the app's private storage. */
class ProjectStore(context: Context) {
    private val dir = File(context.filesDir, "projects").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("daw", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

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
    }

    fun load(name: String): Project? = runCatching {
        json.decodeFromString(Project.serializer(), file(name).readText())
    }.getOrNull()

    fun delete(name: String) {
        file(name).delete()
    }

    fun rename(from: String, to: String) {
        if (from != to) file(from).renameTo(file(to))
    }

    fun exists(name: String) = file(name).exists()

    fun lastOpened(): String? = prefs.getString(LAST, null)

    fun uniqueName(base: String): String {
        if (!exists(base)) return base
        var i = 2
        while (exists("$base $i")) i++
        return "$base $i"
    }

    private fun file(name: String) = File(dir, sanitize(name) + ".json")

    private fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9 _-]"), "_").ifBlank { "Untitled" }

    private companion object {
        const val LAST = "last_project"
    }
}
