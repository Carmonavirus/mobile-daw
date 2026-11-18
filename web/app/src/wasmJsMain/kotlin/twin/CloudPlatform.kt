// Web version of app/src/main/java/com/mobiledaw/cloud/CloudPlatform.kt: Google sign-in through
// Google Identity Services (a pop-up window) and HTTP through fetch. See resources/cloud.js.
@file:Suppress("PackageDirectoryMismatch", "UNUSED_PARAMETER")

package com.mobiledaw.cloud

import android.content.Context
import com.mobiledaw.web.toJs
import com.mobiledaw.web.toKotlin
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Int8Array
import kotlin.coroutines.resume

private external fun cloudConfigured(): Boolean
private external fun cloudStartSignIn(hint: String)
private external fun cloudToken(interactive: Boolean, done: (String?) -> Unit)
private external fun cloudForget(token: String)
private external fun cloudFetch(method: String, url: String, headers: String, body: Int8Array?, done: (Int, Int8Array, String?) -> Unit)

/** A finished HTTP request: status, body and the Location header (resumable uploads). */
class HttpReply(val code: Int, val body: ByteArray, val location: String?)

object CloudPlatform {
    fun configured(context: Context): Boolean = cloudConfigured()

    /** Opens Google's sign-in window. Browsers only allow that right inside a tap. */
    fun startInteractive(context: Context, hint: String?) = cloudStartSignIn(hint ?: "")

    /**
     * The access token: the one from the sign-in window [startInteractive] opened, or a saved one
     * that hasn't run out (they last an hour). Null when the person has to sign in again.
     */
    suspend fun authorize(context: Context, interactive: Boolean, hint: String?): String? =
        suspendCancellableCoroutine { cont -> cloudToken(interactive) { t -> if (cont.isActive) cont.resume(t) } }

    suspend fun forget(context: Context, token: String) = cloudForget(token)

    suspend fun http(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpReply =
        suspendCancellableCoroutine { cont ->
            val h = headers.entries.joinToString("\n") { "${it.key}:${it.value}" }
            cloudFetch(method, url, h, body?.toJs()) { code, bytes, location ->
                if (!cont.isActive) return@cloudFetch
                if (code == 0) cont.resumeWith(Result.failure(java.io.IOException("offline")))
                else cont.resume(HttpReply(code, bytes.toKotlin(), location))
            }
        }
}
