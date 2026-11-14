package com.mobiledaw.sound

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Gets the audio of a video link (an Instagram reel, for personal use) so it can go on the
 * playlist. Instagram has no public API and changes often, so where the media comes from is
 * swappable:
 *
 * - **Your downloader** (Settings → Link downloads), tried first when set. Either a
 *   cobalt-compatible server (`https://…/`, the open-source downloader anyone can self-host), or a
 *   URL template with `{url}` in it, whose answer can be the file itself, JSON or a web page that
 *   links to the file.
 * - **Built in**: reads the reel's public embed page the way a browser does. Phone app only:
 *   Instagram doesn't allow browser pages (the web app) to read it.
 *
 * Only ever runs when the user pastes or shares a link. Every failure ends in a [LinkError] with a
 * message the app can show as is.
 */
class LinkImport(private val cacheDir: File) {

    /** Where the user's own downloader is, if set. [key] is sent as "Authorization: Api-Key …". */
    class Service(val url: String, val key: String = "") {
        val isSet get() = url.isNotBlank()
        val isTemplate get() = url.contains("{url}")
    }

    /** A downloaded media file (video or audio) and a short name for the clip. */
    class Media(val file: File, val name: String)

    class LinkError(message: String) : IOException(message)

    /** Downloads the media behind [link]. Blocking: call it off the main thread. */
    fun fetch(link: String, service: Service): Media {
        val clean = link.trim()
        if (!clean.startsWith("http")) throw LinkError("That isn't a link. Copy the reel's link (Share → Copy link) and paste it here.")
        val code = shortcode(clean)
        val name = if (code != null) "Reel $code" else "Link audio"
        val problems = mutableListOf<String>()
        if (service.isSet) {
            try {
                return Media(fromService(clean, service), name)
            } catch (e: Exception) {
                problems += "Your downloader: ${reason(e)}"
            }
        }
        if (code != null) {
            try {
                return Media(fromInstagram(code), name)
            } catch (e: Exception) {
                problems += "Instagram: ${reason(e)}"
            }
        } else if (!service.isSet) {
            throw LinkError("Only Instagram reel and post links work without a downloader. Add one in Settings → Link downloads for other sites.")
        }
        val hint = if (service.isSet) "Check the reel is public, or try another downloader in Settings → Link downloads."
        else "Check the reel is public. If it keeps failing, Instagram changed something: add a downloader in Settings → Link downloads (in the web app you always need one)."
        throw LinkError("Couldn't get the audio. ${problems.joinToString(" ")} $hint")
    }

    // ---------------------------------------------------------------- your downloader

    private fun fromService(link: String, service: Service): File {
        if (service.isTemplate) {
            val url = service.url.replace("{url}", URLEncoder.encode(link, "UTF-8"))
            return fromAnyAnswer(get(url, auth(service)), url, depth = 0)
        }
        // cobalt API: POST {url, downloadMode: audio} to the server root.
        val body = buildJsonObject {
            put("url", link)
            put("downloadMode", "audio")
            put("audioFormat", "mp3")
        }.toString()
        val headers = auth(service) + mapOf("Accept" to "application/json", "Content-Type" to "application/json")
        val answer = request("POST", service.url.trim(), headers, body.toByteArray())
        val json = runCatching { Json.parseToJsonElement(String(answer.body)) }.getOrNull() as? JsonObject
            ?: throw LinkError("it didn't answer like a cobalt server (HTTP ${answer.code}).")
        when (json.str("status")) {
            "tunnel", "redirect" -> return download(json.str("url") ?: throw LinkError("no file in its answer."), emptyMap())
            "picker" -> {
                val url = json.str("audio")
                    ?: (json["picker"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.str("url") }
                    ?: throw LinkError("no file in its answer.")
                return download(url, emptyMap())
            }
            "error" -> {
                val code = (json["error"] as? JsonObject)?.str("code") ?: json.str("text") ?: "unknown error"
                throw LinkError("it said \"$code\".")
            }
            else -> throw LinkError("it answered \"${json.str("status") ?: "HTTP ${answer.code}"}\", which isn't a file.")
        }
    }

    private fun auth(service: Service) =
        if (service.key.isNotBlank()) mapOf("Authorization" to "Api-Key ${service.key.trim()}") else emptyMap()

    /** A template's answer: the media itself, JSON with a link to it, or a page that links to it. */
    private fun fromAnyAnswer(answer: Answer, from: String, depth: Int): File {
        if (answer.code !in 200..299) throw LinkError("HTTP ${answer.code}.")
        val bytes = answer.body
        val start = bytes.firstOrNull { it.toInt().toChar() !in " \r\n\t" }?.toInt()?.toChar()
        if (start != '{' && start != '[' && start != '<') return saveMedia(bytes)
        if (depth > 0) throw LinkError("its link led to another page instead of a file.")
        val text = String(bytes)
        val link = if (start == '<') mediaLinkInPage(text, from)
        else runCatching { Json.parseToJsonElement(text) }.getOrNull()?.let { mediaLinkInJson(it) }
        link ?: throw LinkError("no download link in its answer.")
        return fromAnyAnswer(get(link, emptyMap()), link, depth + 1)
    }

    // ---------------------------------------------------------------- built in (Instagram)

    private fun fromInstagram(code: String): File {
        val headers = mapOf("User-Agent" to BROWSER_UA, "Accept-Language" to "en-US,en;q=0.9")
        // 1. The embed page often carries the video link.
        val embed = runCatching { get("https://www.instagram.com/p/$code/embed/captioned/", headers) }.getOrNull()
        val embedText = embed?.takeIf { it.code in 200..299 }?.let { String(it.body) }.orEmpty()
        videoUrl(embedText)?.let { return download(it, headers + ("Referer" to "https://www.instagram.com/")) }
        // 2. The same query the website runs for a post, with the page's token.
        val lsd = Regex("\"LSD\",\\[\\],\\{\"token\":\"([^\"]+)\"").find(embedText)?.groupValues?.get(1) ?: "AVqbxe3J_YA"
        val form = "variables=" + URLEncoder.encode("{\"shortcode\":\"$code\"}", "UTF-8") +
            "&doc_id=8845758582119845&lsd=$lsd"
        val gql = runCatching {
            request(
                "POST", "https://www.instagram.com/graphql/query", headers + mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "X-IG-App-ID" to "936619743392459",
                    "X-FB-LSD" to lsd,
                    "X-ASBD-ID" to "129477",
                    "Referer" to "https://www.instagram.com/reel/$code/",
                ), form.toByteArray(),
            )
        }.getOrNull()
        videoUrl(gql?.let { String(it.body) }.orEmpty())?.let { return download(it, headers + ("Referer" to "https://www.instagram.com/")) }
        if (embed == null && gql == null) throw LinkError("couldn't reach it.")
        if (embedText.contains("\"is_private\":true") || embedText.contains("EmbedIsBroken")) throw LinkError("this reel isn't public.")
        throw LinkError("no video found for that reel.")
    }

    // ---------------------------------------------------------------- HTTP

    private class Answer(val code: Int, val body: ByteArray)

    private fun get(url: String, headers: Map<String, String>) = request("GET", url, headers, null)

    private fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Answer {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        if (!headers.containsKey("User-Agent")) conn.setRequestProperty("User-Agent", APP_UA)
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body) }
        }
        val code = try {
            conn.responseCode
        } catch (e: IOException) {
            throw LinkError("couldn't reach ${hostOf(url)}.")
        }
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val bytes = stream?.use { readCapped(it) } ?: ByteArray(0)
        return Answer(code, bytes)
    }

    private fun download(url: String, headers: Map<String, String>): File {
        val answer = get(url, headers)
        if (answer.code !in 200..299) throw LinkError("the file download failed (HTTP ${answer.code}).")
        return saveMedia(answer.body)
    }

    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 15)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) throw LinkError("the file is too big (over ${MAX_BYTES shr 20} MB).")
        }
        return out.toByteArray()
    }

    private fun saveMedia(bytes: ByteArray): File {
        if (bytes.size < 1024) throw LinkError("the download was empty.")
        cacheDir.mkdirs()
        val file = File(cacheDir, "link-${System.currentTimeMillis()}.${extensionOf(bytes)}")
        file.writeBytes(bytes)
        return file
    }

    companion object {
        /** Reels are at most a few minutes; this leaves room for HD video. */
        const val MAX_BYTES = 200 shl 20

        private const val APP_UA = "MobileDAW/0.9 (Android; link import)"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        private val LINK = Regex("https?://[^\\s<>\"']+")
        private val SHORTCODE = Regex("instagram\\.com/(?:[A-Za-z0-9_.]+/)?(?:reels?|p|tv)/([A-Za-z0-9_-]{5,})", RegexOption.IGNORE_CASE)

        /** The first link in shared text ("Check out this reel https://… "). */
        fun findLink(text: String?): String? = text?.let { LINK.find(it)?.value?.trimEnd('.', ',', ')', '!') }

        /** "https://www.instagram.com/reel/DAbc123/?igsh=…" -> "DAbc123". */
        fun shortcode(link: String): String? = SHORTCODE.find(link)?.groupValues?.get(1)

        /** The video link in an Instagram page or answer, with its JSON escaping (often doubled) undone. */
        fun videoUrl(text: String): String? {
            val m = Regex("video_url\\\\*\"\\s*:\\s*\\\\*\"(https?:.+?)\\\\*\"").find(text) ?: return null
            return unescape(m.groupValues[1]).takeIf { it.startsWith("http") }
        }

        fun unescape(s: String): String {
            var out = s
            repeat(3) {
                out = out.replace("\\\\", "\\").replace("\\/", "/")
                    .replace(Regex("\\\\u([0-9a-fA-F]{4})")) { it.groupValues[1].toInt(16).toChar().toString() }
            }
            return out.replace("&amp;", "&")
        }

        /** The most likely file link in a downloader's JSON answer. */
        fun mediaLinkInJson(json: JsonElement): String? {
            val found = mutableListOf<Pair<String, String>>()
            fun walk(e: JsonElement, key: String) {
                when (e) {
                    is JsonObject -> e.forEach { (k, v) -> walk(v, k) }
                    is JsonArray -> e.forEach { walk(it, key) }
                    is JsonPrimitive -> if (e.isString && e.content.startsWith("http")) found += key.lowercase() to e.content
                }
            }
            walk(json, "")
            return found.maxByOrNull { (key, url) -> score(url) + if (key.contains("audio")) 3 else if (key.contains("url") || key.contains("download") || key.contains("link")) 1 else 0 }
                ?.takeIf { (key, url) -> score(url) > 0 || key.contains("download") || key.contains("audio") || key == "url" }
                ?.second
        }

        /** The most likely file link on a downloader's web page. */
        fun mediaLinkInPage(html: String, base: String): String? {
            val links = Regex("(?:href|src)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(html)
                .map { unescape(it.groupValues[1]) }
                .map { if (it.startsWith("//")) "https:$it" else if (it.startsWith("/")) originOf(base) + it else it }
                .filter { it.startsWith("http") }
                .toList()
            return links.filter { score(it) > 0 }.maxByOrNull { score(it) }
        }

        private fun score(url: String): Int {
            val u = url.lowercase().substringBefore('?')
            return when {
                u.endsWith(".mp3") || u.endsWith(".m4a") || u.endsWith(".aac") -> 4
                u.endsWith(".mp4") || u.endsWith(".mov") || u.endsWith(".webm") -> 3
                url.contains("cdninstagram") || url.contains("fbcdn") -> 2
                u.contains("download") || u.contains("tunnel") -> 1
                else -> 0
            }
        }

        private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?')
        private fun originOf(url: String) = url.substringBefore("://") + "://" + hostOf(url)

        /** A file name ending that Android's decoder (and the browser's) recognises. */
        fun extensionOf(b: ByteArray): String = when {
            b.size > 12 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() && b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte() -> "mp4"
            b.size > 3 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte() -> "mp3"
            b.size > 2 && (b[0].toInt() and 0xff) == 0xff && (b[1].toInt() and 0xe0) == 0xe0 -> "mp3"
            b.size > 4 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() -> "wav"
            b.size > 4 && b[0] == 'O'.code.toByte() && b[1] == 'g'.code.toByte() -> "ogg"
            b.size > 4 && (b[0].toInt() and 0xff) == 0x1a && (b[1].toInt() and 0xff) == 0x45 -> "webm"
            else -> "mp4"
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun reason(e: Exception): String = when (e) {
            is LinkError -> e.message.orEmpty()
            else -> (e.message ?: e::class.simpleName ?: "error").take(120) + "."
        }
    }
}
