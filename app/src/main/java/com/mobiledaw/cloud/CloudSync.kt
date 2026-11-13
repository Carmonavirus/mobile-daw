package com.mobiledaw.cloud

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mobiledaw.model.Project
import com.mobiledaw.model.ProjectStore
import com.mobiledaw.sound.SfzLibrary
import com.mobiledaw.sound.SoundFontLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/** What the sync is doing, for the little cloud in the header and the Settings card. */
enum class SyncStatus { OFF, SYNCING, SYNCED, OFFLINE, SIGN_IN_AGAIN, PROBLEM }

/** What a sync changed on this device, so the open project and its sounds can be reloaded. */
class SyncResult(val changedProjects: Set<String>, val newSounds: Boolean)

/**
 * Keeps projects, and the sounds they use, in a "Mobile DAW" folder in the person's own
 * Google Drive, so the phone, the tablet and the web version all see the same beats.
 *
 * A visible folder (not the hidden app-data folder) so the beats can be seen, downloaded and
 * shared from Drive itself; the app still only sees files it made (drive.file scope).
 *
 * Each sync compares every project file with Drive and with how both looked after the last sync:
 * only changed here -> upload; only changed in Drive -> download; changed on both -> the newest
 * wins and the other one is kept as "<name> (older copy)". Without a connection the app works
 * as before and syncs once it's back.
 */
class CloudSync(private val context: Context, private val store: ProjectStore, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stateFile = File(context.filesDir, "cloud/sync-state.json")
    private val filesDir: File = context.filesDir

    var status by mutableStateOf(SyncStatus.OFF)
        private set
    /** A short sentence for Settings when something went wrong. */
    var problem by mutableStateOf<String?>(null)
        private set
    var lastSynced by mutableStateOf(prefs.getLong(LAST_SYNC, 0L))
        private set
    var email by mutableStateOf(prefs.getString(EMAIL, null))
        private set
    var signedIn by mutableStateOf(prefs.getBoolean(SIGNED_IN, false))
        private set
    /** Goes up after every sync that changed something here (the projects list re-reads). */
    var revision by mutableStateOf(0)
        private set

    val configured: Boolean get() = CloudPlatform.configured(context)

    /** Run (on the main thread) just before a sync: saves the open project if it has unsaved edits. */
    var beforeSync: (() -> Unit)? = null
    /** Key of the open project: it is never removed here, even when deleted on another device. */
    var openProjectKey: (() -> String?)? = null
    /** Run (on the main thread) after a sync that changed files here. */
    var onSynced: ((SyncResult) -> Unit)? = null

    /** False while the app is in the background: no periodic checks then. */
    var visible = true

    private var token: String? = null
    private var pending: Job? = null
    private var running = false
    private var again = false
    private var quiet = false
    private var failures = 0

    init {
        if (signedIn) status = SyncStatus.SYNCED
        // While the app is open, look for changes made on other devices every few minutes.
        scope.launch {
            while (true) {
                delay(CHECK_EVERY_MS)
                if (visible && signedIn && !running && status != SyncStatus.SIGN_IN_AGAIN) syncSoon(0)
            }
        }
    }

    // ---------------------------------------------------------------- account

    /** Shows Google's sign-in, then syncs. Call it straight from the tap (the web needs that). */
    fun signIn() {
        if (!configured) return
        CloudPlatform.startInteractive(context, email)
        scope.launch {
            problem = null
            status = SyncStatus.SYNCING
            val t = try {
                CloudPlatform.authorize(context, interactive = true, hint = email)
            } catch (e: Exception) {
                null
            }
            if (t == null) {
                status = if (signedIn) SyncStatus.SIGN_IN_AGAIN else SyncStatus.OFF
                problem = "Google sign-in didn't finish. Try again."
                return@launch
            }
            token = t
            val who = try {
                api().about()
            } catch (e: Exception) {
                status = if (signedIn) SyncStatus.SIGN_IN_AGAIN else SyncStatus.OFF
                problem = "Couldn't reach Google Drive. Check the connection and try again."
                return@launch
            }
            // Another Google account: start fresh (nothing is deleted on either side).
            val state = loadState()
            if (state.account != null && state.account != who) saveState(SyncState(account = who))
            email = who
            signedIn = true
            prefs.edit().putBoolean(SIGNED_IN, true).putString(EMAIL, who).apply()
            failures = 0
            syncSoon(0)
        }
    }

    /** Stops syncing on this device. Nothing is deleted, here or in Drive. */
    fun signOut() {
        pending?.cancel()
        val t = token
        token = null
        signedIn = false
        status = SyncStatus.OFF
        problem = null
        prefs.edit().putBoolean(SIGNED_IN, false).apply()
        if (t != null) scope.launch {
            runCatching { CloudPlatform.forget(context, t) }
            runCatching { CloudPlatform.http("POST", "https://oauth2.googleapis.com/revoke?token=$t", mapOf("Content-Type" to "application/x-www-form-urlencoded"), ByteArray(0)) }
        }
    }

    // ---------------------------------------------------------------- scheduling

    /** Syncs after [delayMs] (later calls push it back), e.g. a few seconds after an edit is saved. */
    fun syncSoon(delayMs: Long = AFTER_SAVE_MS) {
        if (!signedIn || quiet) return
        if (running) {
            again = true
            return
        }
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            run()
        }
    }

    private suspend fun run() {
        if (running || !signedIn) return
        running = true
        status = SyncStatus.SYNCING
        try {
            quiet = true
            try {
                beforeSync?.invoke()
            } finally {
                quiet = false
            }
            val result = syncAll()
            failures = 0
            problem = null
            status = SyncStatus.SYNCED
            lastSynced = System.currentTimeMillis()
            prefs.edit().putLong(LAST_SYNC, lastSynced).apply()
            if (result.changedProjects.isNotEmpty() || result.newSounds) {
                revision++
                onSynced?.invoke(result)
            }
        } catch (e: CloudException) {
            status = e.status
            problem = e.message
            if (e.status != SyncStatus.SIGN_IN_AGAIN) retryLater()
        } catch (e: java.io.IOException) {
            status = SyncStatus.OFFLINE
            problem = null
            retryLater()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            status = SyncStatus.PROBLEM
            problem = "Sync stopped: ${e.message ?: "unknown problem"}. It will try again."
            retryLater()
        } finally {
            running = false
        }
        if (again) {
            again = false
            syncSoon(AFTER_SAVE_MS)
        }
    }

    private fun retryLater() {
        failures++
        val wait = (RETRY_MS shl (failures - 1).coerceAtMost(4)).coerceAtMost(MAX_RETRY_MS)
        pending?.cancel()
        pending = scope.launch {
            delay(wait)
            run()
        }
    }

    // ---------------------------------------------------------------- the sync itself

    private suspend fun syncAll(): SyncResult {
        val api = api()
        val state = loadState()
        if (state.account == null) state.account = email
        val root = state.rootId?.takeIf { api.exists(it) } ?: api.folder(ROOT_NAME, null, "root").also {
            state.rootId = it
            state.projectsId = null
            state.soundsId = null
        }
        val projectsId = state.projectsId?.takeIf { api.exists(it) } ?: api.folder("Projects", root, "projects").also { state.projectsId = it }
        val soundsId = state.soundsId?.takeIf { api.exists(it) } ?: api.folder("Sounds", root, "sounds").also { state.soundsId = it }
        saveState(state)

        val changed = syncProjects(api, state, projectsId)
        saveState(state)
        val newSounds = syncSounds(api, soundsId)
        return SyncResult(changed, newSounds)
    }

    private class Remote(val id: String, val key: String, val hash: String?, val savedAt: Long)

    private suspend fun syncProjects(api: DriveApi, state: SyncState, folderId: String): Set<String> {
        val changed = HashSet<String>()
        val remote = HashMap<String, Remote>()
        for (f in api.list("'$folderId' in parents and trashed = false", "id,name,description,appProperties")) {
            val props = f["appProperties"] as? JsonObject
            val key = f.str("description")?.takeIf { it.isNotBlank() } ?: f.str("name")?.removeSuffix(".json") ?: continue
            val r = Remote(f.str("id") ?: continue, key, props?.str("hash"), props?.str("savedAt")?.toLongOrNull() ?: 0L)
            // Two files for one project (two devices uploaded at once): keep the newest.
            val old = remote[key]
            if (old == null || r.savedAt > old.savedAt) remote[key] = r
        }
        val local = withContext(Dispatchers.IO) {
            store.dir.listFiles { f -> f.extension == "json" }.orEmpty().associateBy { it.nameWithoutExtension }
        }
        val open = openProjectKey?.invoke()

        for (key in (local.keys + remote.keys + state.projects.keys).sorted()) {
            val file = local[key]
            val r = remote[key]
            val base = state.projects[key]
            val bytes = file?.let { withContext(Dispatchers.IO) { runCatching { it.readBytes() }.getOrNull() } }
            val stamp = file?.lastModified() ?: 0L
            val localHash = bytes?.let { contentHash(it) }
            when {
                bytes == null && r == null -> state.projects.remove(key)
                bytes != null && r == null -> {
                    if (base != null && base == localHash && key != open) {
                        // Deleted on another device, and not changed here since.
                        withContext(Dispatchers.IO) { file?.delete() }
                        state.projects.remove(key)
                        changed += key
                    } else {
                        state.projects[key] = api.uploadProject(null, folderId, key, bytes, stamp)
                    }
                }
                bytes == null && r != null -> {
                    if (base != null && base == r.hash) {
                        // Deleted here, and not changed in Drive since.
                        api.trash(r.id)
                        state.projects.remove(key)
                    } else {
                        val got = api.download(r.id)
                        writeProject(key, got)
                        state.projects[key] = contentHash(got)
                        changed += key
                    }
                }
                bytes != null && r != null -> {
                    if (localHash == r.hash) {
                        state.projects[key] = localHash!!
                        continue
                    }
                    val localChanged = base == null || base != localHash
                    val remoteChanged = base == null || base != r.hash
                    if (!remoteChanged) {
                        state.projects[key] = api.uploadProject(r.id, folderId, key, bytes, stamp)
                    } else if (!localChanged) {
                        val got = api.download(r.id)
                        writeProject(key, got)
                        state.projects[key] = contentHash(got)
                        changed += key
                    } else {
                        // Changed on both: the newest wins, the other is kept as a copy. An
                        // untouched empty project (a fresh install's first song) never wins, and
                        // isn't worth a copy.
                        val got = api.download(r.id)
                        if (isBlank(bytes) || isBlank(got)) {
                            if (isBlank(bytes)) {
                                writeProject(key, got)
                                state.projects[key] = contentHash(got)
                                changed += key
                            } else {
                                state.projects[key] = api.uploadProject(r.id, folderId, key, bytes, stamp)
                            }
                            saveState(state)
                            continue
                        }
                        val localNewer = stamp >= r.savedAt
                        val older = if (localNewer) got else bytes
                        if (localNewer) {
                            state.projects[key] = api.uploadProject(r.id, folderId, key, bytes, stamp)
                        } else {
                            writeProject(key, got)
                            state.projects[key] = contentHash(got)
                            changed += key
                        }
                        val copyKey = keepCopy(older)
                        if (copyKey != null) {
                            val copyFile = File(store.dir, "$copyKey.json")
                            val copyBytes = withContext(Dispatchers.IO) { copyFile.readBytes() }
                            state.projects[copyKey] = api.uploadProject(null, folderId, copyKey, copyBytes, copyFile.lastModified())
                            changed += copyKey
                        }
                    }
                }
            }
            saveState(state)
        }
        return changed
    }

    /** A project with nothing in it (no sounds, no audio). */
    private fun isBlank(bytes: ByteArray): Boolean {
        val p = store.decode(bytes) ?: return false
        return p.channels.isEmpty() && p.audioClips.isEmpty()
    }

    /** Saves the losing side of a conflict as its own project. Returns its key. */
    private suspend fun keepCopy(bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val p: Project = store.decode(bytes) ?: return@withContext null
        val name = store.uniqueName("${p.name} (older copy)")
        store.saveCopy(p.copy(name = name))
        store.key(name)
    }

    private suspend fun writeProject(key: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val target = File(store.dir, "$key.json")
        val tmp = File(store.dir, "$key.json.download")
        tmp.writeBytes(bytes)
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }

    /**
     * Sounds (imported samples, recordings, kits, SoundFonts, SFZ) used by any project: uploads the
     * ones Drive doesn't have and downloads the ones this device is missing. Sounds never change
     * once saved (new recordings get new names), so a file's path is enough to know it.
     */
    private suspend fun syncSounds(api: DriveApi, folderId: String): Boolean {
        val (paths, folders) = withContext(Dispatchers.IO) { usedSounds() }
        val remote = HashMap<String, String>()
        for (f in api.list("'$folderId' in parents and trashed = false", "id,description")) {
            val path = f.str("description")?.takeIf { safePath(it) } ?: continue
            remote[path] = f.str("id") ?: continue
        }
        var downloaded = false
        for (path in paths) {
            val file = File(filesDir, path)
            val here = withContext(Dispatchers.IO) { file.isFile }
            if (here && path !in remote) {
                val size = withContext(Dispatchers.IO) { file.length() }
                if (size in 1..MAX_SOUND_BYTES) {
                    val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                    api.uploadSound(folderId, path, bytes)
                }
            }
        }
        for ((path, id) in remote) {
            val wanted = path in paths || folders.any { path.startsWith(it) }
            if (!wanted) continue
            val file = File(filesDir, path)
            if (withContext(Dispatchers.IO) { file.isFile }) continue
            val bytes = api.download(id)
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".download")
                tmp.writeBytes(bytes)
                tmp.renameTo(file)
            }
            downloaded = true
        }
        return downloaded
    }

    /** Paths (relative to the files folder) of every sound the saved projects use, and SFZ folders. */
    private fun usedSounds(): Pair<Set<String>, Set<String>> {
        val paths = LinkedHashSet<String>()
        val folders = LinkedHashSet<String>()
        for (f in store.dir.listFiles { f -> f.extension == "json" }.orEmpty()) {
            val p = runCatching { store.decode(f.readBytes()) }.getOrNull() ?: continue
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
                        if (dir.isNotEmpty()) {
                            folders += "$dir/"
                            File(filesDir, dir).walkTopDown().filter { it.isFile }.forEach { paths += it.relativeTo(filesDir).invariantSeparatorsPath }
                        }
                    }
                }
            }
            for (clip in p.audioClips) paths += clip.path
        }
        return paths.filter { safePath(it) }.toSet() to folders.filter { safePath(it) }.toSet()
    }

    private fun safePath(p: String) = p.isNotBlank() && !p.startsWith("/") && !p.contains("..") && !p.contains(':')

    // ---------------------------------------------------------------- state

    @Serializable
    internal class SyncState(
        var account: String? = null,
        var rootId: String? = null,
        var projectsId: String? = null,
        var soundsId: String? = null,
        /** Project key -> content hash after the last sync (both sides were the same then). */
        val projects: MutableMap<String, String> = mutableMapOf(),
    )

    private fun loadState(): SyncState = runCatching {
        json.decodeFromString(SyncState.serializer(), stateFile.readText())
    }.getOrElse { SyncState() }

    private fun saveState(s: SyncState) {
        runCatching {
            stateFile.parentFile?.mkdirs()
            stateFile.writeText(json.encodeToString(SyncState.serializer(), s))
        }
    }

    // ---------------------------------------------------------------- Drive

    private fun api() = DriveApi { refresh -> accessToken(refresh) }

    private suspend fun accessToken(refresh: Boolean): String {
        if (refresh) {
            token?.let { CloudPlatform.forget(context, it) }
            token = null
        }
        token?.let { return it }
        val t = try {
            CloudPlatform.authorize(context, interactive = false, hint = email)
        } catch (e: Exception) {
            null
        } ?: throw CloudException(SyncStatus.SIGN_IN_AGAIN, "Sign in to Google again to keep syncing.")
        token = t
        return t
    }

    private companion object {
        const val ROOT_NAME = "Mobile DAW"
        const val SIGNED_IN = "signed_in"
        const val EMAIL = "email"
        const val LAST_SYNC = "last_sync"
        const val AFTER_SAVE_MS = 5_000L
        const val CHECK_EVERY_MS = 3 * 60_000L
        const val RETRY_MS = 30_000L
        const val MAX_RETRY_MS = 10 * 60_000L
        const val MAX_SOUND_BYTES = 200L * 1024 * 1024
    }
}

/** FNV-1a, 64 bit: tells whether a project file changed (not a security hash). */
internal fun contentHash(bytes: ByteArray): String {
    var h = -0x340d631b7bdddcdbL
    for (b in bytes) {
        h = h xor (b.toLong() and 0xff)
        h *= 0x100000001b3L
    }
    return h.toULong().toString(16)
}

internal class CloudException(val status: SyncStatus, message: String) : Exception(message)

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** The few Google Drive v3 calls the sync needs. */
internal class DriveApi(private val token: suspend (refresh: Boolean) -> String) {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun send(method: String, url: String, body: ByteArray?, contentType: String?, extra: Map<String, String> = emptyMap()): HttpReply {
        var reply: HttpReply? = null
        for (attempt in 0..1) {
            val headers = LinkedHashMap<String, String>()
            headers["Authorization"] = "Bearer ${token(attempt > 0)}"
            if (contentType != null) headers["Content-Type"] = contentType
            headers.putAll(extra)
            reply = CloudPlatform.http(method, url, headers, body)
            if (reply.code != 401) break
        }
        val r = reply!!
        when {
            r.code == 401 -> throw CloudException(SyncStatus.SIGN_IN_AGAIN, "Sign in to Google again to keep syncing.")
            r.code == 403 && r.body.decodeToString().contains("storageQuotaExceeded") ->
                throw CloudException(SyncStatus.PROBLEM, "Your Google Drive is full, so new changes can't be saved there.")
            r.code == 403 && r.body.decodeToString().contains("accessNotConfigured") ->
                throw CloudException(SyncStatus.PROBLEM, "The Google Drive API isn't turned on for this app yet.")
            r.code == 429 || r.code >= 500 || (r.code == 403 && r.body.decodeToString().contains("ateLimit")) ->
                throw CloudException(SyncStatus.OFFLINE, "Google Drive is busy. It will try again in a moment.")
            r.code !in 200..299 -> throw CloudException(SyncStatus.PROBLEM, "Google Drive said no (error ${r.code}). It will try again.")
        }
        return r
    }

    private suspend fun call(method: String, url: String, body: JsonObject? = null): JsonObject {
        val r = send(method, url, body?.toString()?.encodeToByteArray(), if (body != null) "application/json; charset=UTF-8" else null)
        val text = r.body.decodeToString()
        return if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text).jsonObject
    }

    suspend fun about(): String {
        val o = call("GET", "$API/about?fields=user(emailAddress)")
        return (o["user"] as? JsonObject)?.str("emailAddress") ?: "your Google account"
    }

    suspend fun exists(id: String): Boolean = try {
        val o = call("GET", "$API/files/${enc(id)}?fields=id,trashed")
        (o["trashed"] as? JsonPrimitive)?.contentOrNull != "true"
    } catch (e: CloudException) {
        if (e.status == SyncStatus.PROBLEM) false else throw e
    }

    suspend fun list(q: String, fields: String): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        var page: String? = null
        do {
            val url = "$API/files?pageSize=1000&spaces=drive&q=${enc(q)}&fields=${enc("nextPageToken,files($fields)")}" +
                (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val o = call("GET", url)
            (o["files"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(out::add) }
            page = o.str("nextPageToken")
        } while (page != null)
        return out
    }

    /** The app's folder tagged [tag] inside [parent] ("root" = My Drive), made if missing. */
    suspend fun folder(name: String, parent: String?, tag: String): String {
        val inside = parent ?: "root"
        val q = "mimeType = '$FOLDER' and trashed = false and '$inside' in parents and appProperties has { key = 'daw' and value = '$tag' }"
        list(q, "id").firstOrNull()?.str("id")?.let { return it }
        val made = call("POST", "$API/files?fields=id", buildJsonObject {
            put("name", name)
            put("mimeType", FOLDER)
            putJsonArray("parents") { add(JsonPrimitive(inside)) }
            putJsonObject("appProperties") { put("daw", tag) }
        })
        return made.str("id") ?: throw CloudException(SyncStatus.PROBLEM, "Couldn't make the $name folder in Google Drive.")
    }

    /** Uploads (or replaces, with [id]) a project file. Returns its hash. */
    suspend fun uploadProject(id: String?, folderId: String, key: String, bytes: ByteArray, savedAt: Long): String {
        val hash = contentHash(bytes)
        val meta = buildJsonObject {
            if (id == null) {
                put("name", "$key.json")
                put("mimeType", "application/json")
                put("description", key)
                putJsonArray("parents") { add(JsonPrimitive(folderId)) }
            }
            putJsonObject("appProperties") {
                put("daw", "project")
                put("hash", hash)
                put("savedAt", savedAt.toString())
            }
        }
        upload(id, meta, bytes, "application/json")
        return hash
    }

    suspend fun uploadSound(folderId: String, path: String, bytes: ByteArray) {
        val meta = buildJsonObject {
            put("name", path.substringAfterLast('/'))
            put("description", path)
            putJsonArray("parents") { add(JsonPrimitive(folderId)) }
            putJsonObject("appProperties") { put("daw", "sound") }
        }
        upload(null, meta, bytes, mimeOf(path))
    }

    /** A resumable upload: works for any size and on every platform. */
    private suspend fun upload(id: String?, meta: JsonObject, bytes: ByteArray, mime: String) {
        val start = if (id == null) "$UPLOAD/files?uploadType=resumable&fields=id" else "$UPLOAD/files/${enc(id)}?uploadType=resumable&fields=id"
        val session = send(
            if (id == null) "POST" else "PATCH",
            start,
            meta.toString().encodeToByteArray(),
            "application/json; charset=UTF-8",
            mapOf("X-Upload-Content-Type" to mime),
        )
        val location = session.location ?: throw CloudException(SyncStatus.PROBLEM, "Google Drive didn't accept the upload. It will try again.")
        send("PUT", location, bytes, mime)
    }

    suspend fun download(id: String): ByteArray = send("GET", "$API/files/${enc(id)}?alt=media", null, null).body

    suspend fun trash(id: String) {
        try {
            call("PATCH", "$API/files/${enc(id)}", buildJsonObject { put("trashed", true) })
        } catch (e: CloudException) {
            if (e.status != SyncStatus.PROBLEM) throw e // already gone: fine
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun mimeOf(path: String) = when (path.substringAfterLast('.').lowercase()) {
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "aif", "aiff" -> "audio/aiff"
        "m4a" -> "audio/mp4"
        "sfz" -> "text/plain"
        else -> "application/octet-stream"
    }

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER = "application/vnd.google-apps.folder"
    }
}
