@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER")

package java.net

import com.mobiledaw.web.toJs
import com.mobiledaw.web.toKotlin
import org.khronos.webgl.Int8Array

private external fun httpSync(method: String, url: String, headers: String, body: Int8Array?): JsAny
private external fun httpStatus(r: JsAny): Int
private external fun httpBody(r: JsAny): Int8Array

class URL(val spec: String) {
    fun openConnection(): URLConnection = HttpURLConnection(spec)
    override fun toString() = spec
}

open class URLConnection

/**
 * HttpURLConnection over a synchronous XMLHttpRequest (the app's library code runs requests in a
 * background coroutine on Android; on the web they block the page for the time of the request).
 * The server must allow cross-origin requests.
 */
class HttpURLConnection(private val url: String) : URLConnection() {
    var requestMethod = "GET"
    var doOutput = false
    var connectTimeout = 0
    var readTimeout = 0
    var instanceFollowRedirects = true
    private val headers = LinkedHashMap<String, String>()
    private val body = java.io.ByteArrayOutputStream()
    private var status = -1
    private var response = ByteArray(0)

    fun setRequestProperty(key: String, value: String) {
        if (key.equals("User-Agent", ignoreCase = true)) return // browsers send their own
        headers[key] = value
    }

    val outputStream: java.io.OutputStream get() = body

    private fun send() {
        if (status >= 0) return
        val h = headers.entries.joinToString("\n") { "${it.key}:${it.value}" }
        val r = httpSync(requestMethod, url, h, if (doOutput) body.toByteArray().toJs() else null)
        status = httpStatus(r)
        response = httpBody(r).toKotlin()
        if (status == 0) throw java.io.IOException("Couldn't reach $url")
    }

    val responseCode: Int get() { send(); return status }
    val inputStream: java.io.InputStream get() {
        send()
        if (status !in 200..299) throw java.io.IOException("HTTP $status")
        return java.io.ByteArrayInputStream(response)
    }
    val errorStream: java.io.InputStream? get() { send(); return if (status in 200..299) null else java.io.ByteArrayInputStream(response) }
    fun disconnect() {}
    fun getHeaderField(name: String): String? = null
}

object URLEncoder {
    fun encode(s: String, enc: String): String {
        val sb = StringBuilder()
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' || ch == '-' || ch == '*' || ch == '_' -> sb.append(ch)
                ch == ' ' -> sb.append('+')
                else -> sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
            }
        }
        return sb.toString()
    }
}
