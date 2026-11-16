package com.mobiledaw.sound

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

/**
 * Second way to find a reel's video, for when Instagram turns away the app's plain requests: the
 * reel's page is opened in the phone's own browser engine (off screen), exactly like opening the
 * link in Chrome, and the video's address is read from the page. Phone only; the web app's twin
 * finds nothing.
 */
object ReelBrowser {
    const val available = true

    /** The video's address (or null) and a short note on what happened, for the error message. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun find(context: Context, code: String): Pair<String?, String> = withContext(Dispatchers.Main) {
        val web = try {
            WebView(context)
        } catch (e: Throwable) {
            return@withContext null to "this phone has no browser engine (Android System WebView)."
        }
        val seen = CopyOnWriteArrayList<String>()
        try {
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.mediaPlaybackRequiresUserGesture = false
            // Look like Chrome on this phone, not like an app's embedded page.
            web.settings.userAgentString = web.settings.userAgentString.replace("; wv", "")
            CookieManager.getInstance().setAcceptCookie(true)
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url.toString()
                    if (isMedia(u)) seen.addIfAbsent(wholeFile(u))
                    return null
                }
            }
            web.layout(0, 0, 1080, 1920)
            web.loadUrl("https://www.instagram.com/reel/$code/")
            val start = SystemClock.elapsedRealtime()
            var found: String? = null
            var login = false
            while (found == null && SystemClock.elapsedRealtime() - start < TIMEOUT_MS) {
                delay(700)
                val raw = suspendCancellableCoroutine<String?> { c -> web.evaluateJavascript(FIND_JS) { c.resume(it) } }
                val text = runCatching { (Json.parseToJsonElement(raw ?: "null") as? JsonPrimitive)?.content }.getOrNull().orEmpty()
                if (text.startsWith("http")) found = LinkImport.unescape(text)
                if (web.url.orEmpty().contains("/accounts/login")) {
                    login = true
                    break
                }
                // No address in the page itself, but the player already asked for a video file.
                if (found == null && seen.isNotEmpty() && SystemClock.elapsedRealtime() - start > 8_000) break
            }
            val url = found ?: seen.firstOrNull()
            when {
                url != null -> url to "found"
                login -> null to "Instagram asked to log in to see this reel."
                else -> null to "the page opened but showed no video."
            }
        } catch (e: Throwable) {
            null to ((e.message ?: "error").take(100) + ".")
        } finally {
            runCatching { web.stopLoading() }
            runCatching { web.destroy() }
        }
    }

    private const val TIMEOUT_MS = 25_000L

    private fun isMedia(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/')
        return (host.contains("cdninstagram") || host.contains("fbcdn")) && url.substringBefore('?').endsWith(".mp4")
    }

    /** Players ask for a file in slices; without the slice range the same address gives the whole file. */
    private fun wholeFile(url: String): String =
        url.replace(Regex("([?&])byte(start|end)=\\d+&?"), "\$1").trimEnd('&', '?')

    /** The playing video's address, or the one in the page's data. */
    private const val FIND_JS = """(function(){try{
var v=document.querySelector('video');var s=v&&(v.currentSrc||v.src)||'';
if(s.indexOf('http')===0)return s;
var h=document.documentElement.innerHTML;
var m=h.match(/"video_url"\s*:\s*"(http[^"]+)"/)||h.match(/"video_versions"\s*:\s*\[\s*\{[^\]]*?"url"\s*:\s*"(http[^"]+)"/)||h.match(/property="og:video(?::secure_url)?"\s+content="(http[^"]+)"/);
return m?m[1]:'';}catch(e){return '';}})()"""
}
