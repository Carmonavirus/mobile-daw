package com.mobiledaw.model

import com.mobiledaw.sound.SfzLibrary
import com.mobiledaw.sound.SoundFontLibrary
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Projects packed into one .zip to send to a friend: the project plus every sound it uses
 * (imported audio, recordings, kit samples, SoundFonts, SFZ instruments with their samples).
 *
 * Inside the zip:
 *  - `mobile-daw-project.txt` marks it as a project (so a drum-kit .zip is never mistaken for one),
 *  - `project.json` is the project as the app saves it,
 *  - `files/<path>` are the sounds, at the same path (relative to the app's files folder) the
 *    project refers to them by.
 *
 * Importing never overwrites anything: the project gets a free name ("Beat 2") and a sound whose
 * path is already taken by a different file is stored under a new name, with the project updated
 * to match. Identical files are shared rather than copied twice.
 */
class ProjectShare(private val filesDir: File, private val store: ProjectStore) {

    class Packed(val file: File, val sounds: Int, val missing: Int)

    class Imported(val project: Project, val sounds: Int)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    /** Packs [project] and its sounds into [out]. */
    fun pack(project: Project, out: File): Packed {
        val (paths, _) = soundsOf(listOf(project), filesDir)
        var count = 0
        var missing = 0
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered(1 shl 16)).use { zip ->
            zip.putNextEntry(ZipEntry(MARKER))
            zip.write("Mobile DAW project, format $FORMAT. Open it in Mobile DAW: Projects > Import project.\n".encodeToByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(PROJECT))
            zip.write(json.encodeToString(Project.serializer(), project).encodeToByteArray())
            zip.closeEntry()
            for (path in paths) {
                val f = File(filesDir, path)
                if (!f.isFile) { missing++; continue }
                zip.putNextEntry(ZipEntry(FILES + path))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
        }
        return Packed(out, count, missing)
    }

    /**
     * Reads a project .zip, copies its sounds into the app and saves the project under a name no
     * other project has. Returns null when the zip isn't a Mobile DAW project.
     */
    fun import(input: InputStream): Imported? {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name.trimStart('/')] = zip.readBytes()
                zip.closeEntry()
            }
        }
        // A project that was unzipped and zipped again by hand sits inside one folder.
        val prefix = when {
            PROJECT in entries -> ""
            else -> entries.keys.filter { it.endsWith("/$PROJECT") && it.count { ch -> ch == '/' } == 1 }
                .minOrNull()?.removeSuffix(PROJECT) ?: return null
        }
        val marked = (prefix + MARKER) in entries
        val projectBytes = entries[prefix + PROJECT]
        val bundled = LinkedHashMap<String, ByteArray>()
        for ((name, data) in entries) {
            if (!name.startsWith(prefix + FILES)) continue
            val path = name.removePrefix(prefix + FILES)
            if (safePath(path)) bundled[path] = data
        }
        val bytes = projectBytes ?: return null
        val shared = runCatching { json.decodeFromString(Project.serializer(), bytes.decodeToString()) }.getOrNull()
            ?: return if (marked) throw IllegalArgumentException("This project was made by a newer version of the app.") else null

        // Where each bundled file goes. SFZ instruments move as a whole folder so the .sfz still
        // finds its samples; other sounds move one by one.
        val moved = HashMap<String, String>()
        val sfzDirs = shared.channels.mapNotNull { c ->
            c.source?.takeIf { it.startsWith(SfzLibrary.SOURCE) }?.removePrefix(SfzLibrary.SOURCE)
                ?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() && safePath(it) }
        }.distinct().sortedByDescending { it.length }
        for (dir in sfzDirs) {
            val inside = bundled.keys.filter { it.startsWith("$dir/") && it !in moved }
            if (inside.isEmpty()) continue
            val clash = inside.any { clashes(it, bundled.getValue(it)) }
            val newDir = if (clash) freeFolder(dir) else dir
            inside.forEach { moved[it] = newDir + it.removePrefix(dir) }
        }
        for ((path, data) in bundled) {
            if (path in moved) continue
            moved[path] = if (clashes(path, data)) freeFile(path, bundled.keys) else path
        }
        for ((path, data) in bundled) {
            val target = File(filesDir, moved.getValue(path))
            if (target.isFile && target.length() == data.size.toLong() && target.readBytes().contentEquals(data)) continue
            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".importing")
            tmp.writeBytes(data)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }

        fun remap(p: String?): String? = p?.let { moved[it] ?: it }
        fun remapSource(s: String?): String? = when {
            s == null -> null
            s.startsWith(SoundFontLibrary.SOURCE_FILE) -> SoundFontLibrary.SOURCE_FILE + remap(s.removePrefix(SoundFontLibrary.SOURCE_FILE))
            s.startsWith(SfzLibrary.SOURCE) -> SfzLibrary.SOURCE + remap(s.removePrefix(SfzLibrary.SOURCE))
            else -> s
        }
        val project = shared.copy(
            name = store.uniqueName(shared.name.trim().ifEmpty { "Shared project" }),
            channels = shared.channels.map { it.copy(sample = remap(it.sample), source = remapSource(it.source)) },
            audioClips = shared.audioClips.map { it.copy(path = remap(it.path) ?: it.path) },
        )
        store.save(project)
        return Imported(project, bundled.size)
    }

    /** True when [path] is already used by a different file. */
    private fun clashes(path: String, data: ByteArray): Boolean {
        val f = File(filesDir, path)
        if (!f.exists()) return false
        if (!f.isFile || f.length() != data.size.toLong()) return true
        return !f.readBytes().contentEquals(data)
    }

    private fun freeFile(path: String, incoming: Set<String>): String {
        val dir = path.substringBeforeLast('/', "")
        val file = path.substringAfterLast('/')
        val stem = file.substringBeforeLast('.', file)
        val ext = file.substring(stem.length)
        var i = 2
        while (true) {
            val candidate = (if (dir.isEmpty()) "" else "$dir/") + "$stem $i$ext"
            if (!File(filesDir, candidate).exists() && candidate !in incoming) return candidate
            i++
        }
    }

    private fun freeFolder(dir: String): String {
        var i = 2
        while (File(filesDir, "$dir $i").exists()) i++
        return "$dir $i"
    }

    companion object {
        const val MARKER = "mobile-daw-project.txt"
        const val PROJECT = "project.json"
        const val FILES = "files/"
        const val FORMAT = 1

        /** File name for a shared project, e.g. "My beat (Mobile DAW).zip". */
        fun fileName(projectName: String): String =
            projectName.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifBlank { "Project" } + " (Mobile DAW).zip"

        /**
         * Paths (relative to the files folder) of every sound [projects] use, and the SFZ
         * instrument folders they need whole.
         */
        fun soundsOf(projects: List<Project>, filesDir: File): Pair<Set<String>, Set<String>> {
            val paths = LinkedHashSet<String>()
            val folders = LinkedHashSet<String>()
            for (p in projects) {
                for (c in p.channels) {
                    c.sample?.let { paths += it }
                    val source = c.source ?: continue
                    when {
                        source.startsWith(SoundFontLibrary.SOURCE_FILE) -> paths += source.removePrefix(SoundFontLibrary.SOURCE_FILE)
                        source.startsWith(SfzLibrary.SOURCE) -> {
                            // An SFZ instrument is a text file plus the folder of samples next to it.
                            val sfz = source.removePrefix(SfzLibrary.SOURCE)
                            val dir = sfz.substringBeforeLast('/', "")
                            paths += sfz
                            if (dir.isNotEmpty() && safePath(dir)) {
                                folders += "$dir/"
                                File(filesDir, dir).walkTopDown().filter { it.isFile }
                                    .forEach { paths += it.relativeTo(filesDir).invariantSeparatorsPath }
                            }
                        }
                    }
                }
                for (clip in p.audioClips) paths += clip.path
            }
            return paths.filter { safePath(it) }.toSet() to folders.filter { safePath(it) }.toSet()
        }

        /** A path inside the files folder that can't climb out of it. */
        fun safePath(p: String) = p.isNotBlank() && !p.startsWith("/") && !p.contains("..") && !p.contains(':') && !p.contains('\\')
    }
}
