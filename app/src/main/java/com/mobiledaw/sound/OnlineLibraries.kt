package com.mobiledaw.sound

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Small HTTP helper. Requests carry a generic app name only (no personal details). */
internal object Http {
    private const val USER_AGENT = "MobileDAW/0.4 (Android; sound library browser)"
    private val json = Json { ignoreUnknownKeys = true }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        json.parseToJsonElement(String(open(url, headers).use { it.readBytes() }))

    fun postForm(url: String, form: Map<String, String>): JsonElement {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(form.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }.toByteArray()) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { String(it.readBytes()) }.orEmpty()
        if (code !in 200..299) throw HttpError(code, body)
        return json.parseToJsonElement(body)
    }

    fun download(url: String, to: File, headers: Map<String, String> = emptyMap(), maxBytes: Long = 60L shl 20) {
        to.parentFile?.mkdirs()
        open(url, headers).use { input ->
            to.outputStream().use { out ->
                val buf = ByteArray(1 shl 15)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("File is too large")
                    out.write(buf, 0, n)
                }
            }
        }
    }

    private fun open(url: String, headers: Map<String, String>): java.io.InputStream {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val body = conn.errorStream?.use { String(it.readBytes()) }.orEmpty()
            throw HttpError(code, body)
        }
        return conn.inputStream
    }
}

class HttpError(val code: Int, body: String) : IOException("HTTP $code ${body.take(200)}")

internal fun JsonElement?.str(): String? = (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
internal fun JsonObject.str(key: String): String? = this[key].str()

/** Strips HTML tags that Wikimedia puts in author fields. */
private fun plain(html: String?): String =
    html.orEmpty().replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").trim()

// ------------------------------------------------------------------------------------------ Freesound

/**
 * Freesound (freesound.org) API v2. Free for non-commercial apps; each user brings their own
 * free API key (freesound.org/apiv2/apply). Searching and previews need only the key.
 * Original files need the user to sign in (OAuth2); without it we save the high-quality preview.
 */
class FreesoundClient(context: Context) {

    private val prefs = context.getSharedPreferences("freesound", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "").orEmpty()
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()
    var clientId: String
        get() = prefs.getString("client_id", "").orEmpty()
        set(v) = prefs.edit().putString("client_id", v.trim()).apply()
    private var accessToken: String
        get() = prefs.getString("access_token", "").orEmpty()
        set(v) = prefs.edit().putString("access_token", v).apply()
    private var refreshToken: String
        get() = prefs.getString("refresh_token", "").orEmpty()
        set(v) = prefs.edit().putString("refresh_token", v).apply()
    private var tokenExpires: Long
        get() = prefs.getLong("token_expires", 0L)
        set(v) = prefs.edit().putLong("token_expires", v).apply()

    val hasKey: Boolean get() = apiKey.isNotBlank()
    val signedIn: Boolean get() = refreshToken.isNotBlank()

    fun search(query: String, filter: LicenseFilter, page: Int, shortOnly: Boolean): List<OnlineSound> {
        val licenses = buildList {
            if (LicenseKind.CC0 in filter.kinds) add("\"Creative Commons 0\"")
            if (LicenseKind.BY in filter.kinds) add("\"Attribution\"")
            if (LicenseKind.NC in filter.kinds) add("\"Attribution NonCommercial\"")
        }
        val filters = buildList {
            if (filter != LicenseFilter.ALL) add("license:(${licenses.joinToString(" OR ")})")
            if (shortOnly) add("duration:[0 TO 30]")
        }.joinToString(" ")
        val params = "query=${Http.enc(query)}" +
            (if (filters.isNotEmpty()) "&filter=${Http.enc(filters)}" else "") +
            "&fields=id,name,username,license,duration,previews,url,type" +
            "&page_size=30&page=$page&token=${Http.enc(apiKey)}"
        val body = try {
            Http.getJson("$API/search/?$params")
        } catch (e: HttpError) {
            if (e.code != 404) throw e
            Http.getJson("$API/search/text/?$params")  // older servers
        }
        val results = body.jsonObject["results"]?.jsonArray ?: JsonArray(emptyList())
        return results.mapNotNull { el ->
            val o = el.jsonObject
            val id = o["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val previews = o["previews"]?.jsonObject
            val preview = previews?.str("preview-hq-mp3") ?: previews?.str("preview-hq-ogg") ?: return@mapNotNull null
            val license = o.str("license").orEmpty()
            OnlineSound(
                source = OnlineSource.FREESOUND,
                id = id.toString(),
                title = o.str("name") ?: "Sound $id",
                author = o.str("username") ?: "unknown",
                license = LicenseKind.from(license),
                licenseName = licenseName(license),
                licenseUrl = license.takeIf { it.startsWith("http") },
                pageUrl = o.str("url") ?: "https://freesound.org/s/$id/",
                durationSeconds = o["duration"]?.jsonPrimitive?.doubleOrNull,
                previewUrl = preview,
                fileUrl = "$API/sounds/$id/download/",
            )
        }
    }

    /** Page where the user approves this app; Freesound then shows a code to paste back. */
    fun authorizeUrl(): String = "$API/oauth2/authorize/?client_id=${Http.enc(clientId)}&response_type=code&state=mobiledaw"

    fun signIn(code: String) {
        val r = Http.postForm(
            "$API/oauth2/access_token/",
            mapOf("client_id" to clientId, "client_secret" to apiKey, "grant_type" to "authorization_code", "code" to code.trim()),
        ).jsonObject
        storeTokens(r)
    }

    fun signOut() {
        accessToken = ""
        refreshToken = ""
        tokenExpires = 0L
    }

    private fun storeTokens(r: JsonObject) {
        accessToken = r.str("access_token") ?: throw IOException("No access token")
        r.str("refresh_token")?.let { refreshToken = it }
        val seconds = r["expires_in"]?.jsonPrimitive?.intOrNull ?: 86_000
        tokenExpires = System.currentTimeMillis() + (seconds - 60) * 1000L
    }

    private fun validToken(): String? {
        if (!signedIn) return null
        if (System.currentTimeMillis() < tokenExpires && accessToken.isNotBlank()) return accessToken
        return runCatching {
            storeTokens(
                Http.postForm(
                    "$API/oauth2/access_token/",
                    mapOf("client_id" to clientId, "client_secret" to apiKey, "grant_type" to "refresh_token", "refresh_token" to refreshToken),
                ).jsonObject,
            )
            accessToken
        }.getOrNull()
    }

    /** Downloads the original file when signed in; returns false so the caller can use the preview. */
    fun downloadOriginal(sound: OnlineSound, to: File): Boolean {
        val token = validToken() ?: return false
        val url = sound.fileUrl ?: return false
        return runCatching { Http.download(url, to, mapOf("Authorization" to "Bearer $token")); true }.getOrDefault(false)
    }

    private fun licenseName(license: String): String = when (LicenseKind.from(license)) {
        LicenseKind.CC0 -> "CC0"
        LicenseKind.BY -> "CC BY"
        LicenseKind.NC -> "CC BY-NC"
        else -> license.ifBlank { "Unknown" }
    }

    companion object {
        const val API = "https://freesound.org/apiv2"
        const val APPLY_URL = "https://freesound.org/apiv2/apply"
        /** Freesound's own page that shows the sign-in code; the API credential must use it as its Callback URL. */
        const val CALLBACK_URL = "https://freesound.org/home/app_permissions/permission_granted/"
    }
}

// ------------------------------------------------------------------------------------------ Wikimedia Commons

/** Wikimedia Commons: public-domain and Creative Commons audio files. No key needed. */
object CommonsClient {
    private const val API = "https://commons.wikimedia.org/w/api.php"

    fun search(query: String, filter: LicenseFilter, page: Int): List<OnlineSound> {
        val url = "$API?action=query&format=json&generator=search&gsrnamespace=6" +
            "&gsrsearch=${Http.enc("$query filetype:audio")}&gsrlimit=40&gsroffset=${(page - 1) * 40}" +
            "&prop=imageinfo&iiprop=url%7Cmime%7Csize%7Cextmetadata" +
            "&iiextmetadatafilter=LicenseShortName%7CLicenseUrl%7CArtist%7CObjectName"
        val pages = Http.getJson(url).jsonObject["query"]?.jsonObject?.get("pages")?.jsonObject ?: return emptyList()
        return pages.values.mapNotNull { el ->
            val o = el.jsonObject
            val info = o["imageinfo"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@mapNotNull null
            val mime = info.str("mime").orEmpty()
            if (!mime.startsWith("audio/") && !mime.startsWith("application/ogg") && !mime.startsWith("video/webm")) return@mapNotNull null
            if ((info["size"]?.jsonPrimitive?.intOrNull ?: 0) > 40 shl 20) return@mapNotNull null
            val ext = info["extmetadata"]?.jsonObject
            fun meta(key: String): String? = ext?.get(key)?.jsonObject?.str("value")
            val licenseName = plain(meta("LicenseShortName")).ifBlank { "Unknown" }
            val kind = LicenseKind.from(licenseName)
            if (kind !in filter.kinds) return@mapNotNull null
            val title = o.str("title").orEmpty().removePrefix("File:").substringBeforeLast('.')
            val fileUrl = info.str("url") ?: return@mapNotNull null
            OnlineSound(
                source = OnlineSource.COMMONS,
                id = o.str("pageid") ?: title,
                title = plain(meta("ObjectName")).ifBlank { title },
                author = plain(meta("Artist")).ifBlank { "unknown" },
                license = kind,
                licenseName = licenseName,
                licenseUrl = meta("LicenseUrl"),
                pageUrl = info.str("descriptionurl") ?: "https://commons.wikimedia.org/wiki/${Http.enc(o.str("title").orEmpty())}",
                durationSeconds = null,
                previewUrl = fileUrl,
                fileUrl = fileUrl,
            )
        }
    }
}

// ------------------------------------------------------------------------------------------ Internet Archive

/** Internet Archive audio items with a Creative Commons or public-domain licence. No key needed. */
object ArchiveClient {
    class Item(val id: String, val title: String, val author: String, val license: LicenseKind, val licenseUrl: String)

    fun search(query: String, filter: LicenseFilter, page: Int): List<Item> {
        val q = "($query) AND mediatype:(audio) AND licenseurl:[* TO *]"
        val url = "https://archive.org/advancedsearch.php?q=${Http.enc(q)}" +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=licenseurl" +
            "&rows=50&page=$page&output=json"
        val docs = Http.getJson(url).jsonObject["response"]?.jsonObject?.get("docs")?.jsonArray ?: return emptyList()
        return docs.mapNotNull { el ->
            val o = el.jsonObject
            val license = first(o["licenseurl"]) ?: return@mapNotNull null
            val kind = LicenseKind.from(license)
            if (kind !in filter.kinds) return@mapNotNull null
            val id = o.str("identifier") ?: return@mapNotNull null
            Item(id, first(o["title"]) ?: id, first(o["creator"]) ?: "unknown", kind, license)
        }
    }

    /** The playable audio files of an item (short ones first). */
    fun files(item: Item): List<OnlineSound> {
        val files = Http.getJson("https://archive.org/metadata/${Http.enc(item.id)}/files").jsonObject["result"]?.jsonArray
            ?: return emptyList()
        return files.mapNotNull { el ->
            val o = el.jsonObject
            val name = o.str("name") ?: return@mapNotNull null
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in setOf("wav", "mp3", "ogg", "flac")) return@mapNotNull null
            val size = o.str("size")?.toLongOrNull() ?: 0L
            if (size > 40L shl 20) return@mapNotNull null
            val url = "https://archive.org/download/${Http.enc(item.id)}/${name.split('/').joinToString("/") { Http.enc(it).replace("+", "%20") }}"
            OnlineSound(
                source = OnlineSource.ARCHIVE,
                id = "${item.id}/$name",
                title = o.str("title") ?: name.substringAfterLast('/').substringBeforeLast('.'),
                author = item.author,
                license = item.license,
                licenseName = item.license.label,
                licenseUrl = item.licenseUrl,
                pageUrl = "https://archive.org/details/${item.id}",
                durationSeconds = o.str("length")?.toDoubleOrNull(),
                previewUrl = url,
                fileUrl = url,
            )
        }.sortedWith(compareBy({ it.durationSeconds ?: 1e9 }, { it.title }))
            // An item often has the same take as WAV, FLAC and MP3; keep one of each.
            .distinctBy { it.title.lowercase() }
    }

    private fun first(e: JsonElement?): String? = when (e) {
        is JsonArray -> e.firstOrNull().str()
        else -> e.str()
    }
}
