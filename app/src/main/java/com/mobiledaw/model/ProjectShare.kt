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

    /** After an [import] that found no project: how many audio or MIDI files the zip held (an audio export, say). */
    var lastAudioFiles = 0
        private set

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
        // Sounds are unpacked to a staging folder first, one at a time, so a project with long
        // loops and recordings never has to fit in memory at once.
        val staging = File(filesDir, STAGING).apply { deleteRecursively(); mkdirs() }
        try {
            return importFrom(input, staging)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun importFrom(input: InputStream, staging: File): Imported? {
        // name in the zip -> unpacked file (sounds) or bytes (the project and its marker).
        val staged = LinkedHashMap<String, File>()
        val small = HashMap<String, ByteArray>()
        var n = 0
        lastAudioFiles = 0
        ZipInputStream(input.buffered(1 shl 16)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.replace('\\', '/').trimStart('/')
                if (!entry.isDirectory && !name.startsWith("__MACOSX/")) {
                    if (name.substringAfterLast('/') == PROJECT || name.substringAfterLast('/') == MARKER) {
                        small[name] = zip.readBytes()
                    } else if (name.contains(FILES)) {
                        val f = File(staging, "${n++}")
                        f.outputStream().use { zip.copyTo(it) }
                        staged[name] = f
                    } else if (AUDIO.matches(name.substringAfterLast('.', "").lowercase())) {
                        lastAudioFiles++
                    }
                }
                zip.closeEntry()
            }
        }
        // A project that was unzipped and zipped again by hand sits inside one folder.
        val prefix = when {
            PROJECT in small -> ""
            else -> small.keys.filter { it.endsWith("/$PROJECT") && it.count { ch -> ch == '/' } == 1 }
                .minOrNull()?.removeSuffix(PROJECT) ?: return null
        }
        val marked = (prefix + MARKER) in small
        val projectBytes = small[prefix + PROJECT] ?: return null
        val bundled = LinkedHashMap<String, File>()
        for ((name, file) in staged) {
            if (!name.startsWith(prefix + FILES)) continue
            val path = name.removePrefix(prefix + FILES)
            if (safePath(path)) bundled[path] = file
        }
        val shared = runCatching { json.decodeFromString(Project.serializer(), projectBytes.decodeToString().removePrefix("\uFEFF")) }.getOrNull()
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
        for ((path, file) in bundled) {
            if (path in moved) continue
            moved[path] = if (clashes(path, file)) freeFile(path, bundled.keys) else path
        }
        for ((path, file) in bundled) {
            val target = File(filesDir, moved.getValue(path))
            if (target.isFile && sameContent(target, file)) continue
            target.parentFile?.mkdirs()
            if (target.exists()) target.delete()
            if (!file.renameTo(target)) {
                file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            }
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
    private fun clashes(path: String, incoming: File): Boolean {
        val f = File(filesDir, path)
        if (!f.exists()) return false
        return !f.isFile || !sameContent(f, incoming)
    }

    /** Compares two files a block at a time. */
    private fun sameContent(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        a.inputStream().buffered(1 shl 16).use { x ->
            b.inputStream().buffered(1 shl 16).use { y ->
                val bx = ByteArray(1 shl 16)
                val by = ByteArray(1 shl 16)
                while (true) {
                    val nx = readFully(x, bx)
                    val ny = readFully(y, by)
                    if (nx != ny) return false
                    if (nx <= 0) return true
                    for (i in 0 until nx) if (bx[i] != by[i]) return false
                }
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
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
        /** Folder (in the files folder) an import unpacks into before the sounds move in place. */
        private val AUDIO = Regex("wav|mp3|mid|midi|ogg|flac|m4a|aac")
        const val STAGING = ".project-import"

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
