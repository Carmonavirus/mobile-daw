package com.mobiledaw.cloud

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A finished HTTP request: status, body and the Location header (resumable uploads). */
class HttpReply(val code: Int, val body: ByteArray, val location: String?)

/**
 * The phone side of Google Drive sync: Google sign-in (Play services authorization, which needs
 * no client id in the app: Google matches the app by its package name and signing key) and HTTP.
 * The web version (web/app/src/wasmJsMain/kotlin/twin/CloudPlatform.kt) has the same API.
 */
object CloudPlatform {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"

    private var activity: WeakReference<ComponentActivity>? = null

    /** The activity that shows Google's consent screen. Called from MainActivity.onCreate. */
    fun attach(a: ComponentActivity) {
        activity = WeakReference(a)
    }

    /** False when sign-in can't work in this build (the web version without a client id). */
    fun configured(context: Context): Boolean = true

    /** On the web the sign-in window has to open right in the tap; nothing to do on Android. */
    fun startInteractive(context: Context, hint: String?) {}

    /**
     * An access token for Drive, or null when the person has to sign in (again).
     * [interactive] may show Google's account and consent screens.
     */
    suspend fun authorize(context: Context, interactive: Boolean, hint: String?): String? {
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
        val client = Identity.getAuthorizationClient(context)
        val result = client.authorize(request).await()
        if (!result.hasResolution()) return result.accessToken
        if (!interactive) return null
        val host = activity?.get() ?: return null
        val pending = result.pendingIntent ?: return null
        val answer = launch(host, IntentSenderRequest.Builder(pending.intentSender).build())
        if (answer.resultCode != Activity.RESULT_OK || answer.data == null) return null
        val granted: AuthorizationResult = client.getAuthorizationResultFromIntent(answer.data!!)
        return granted.accessToken
    }

    /** Drops a token that stopped working, so the next [authorize] gets a fresh one. */
    suspend fun forget(context: Context, token: String) {
        withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(context, token) } }
    }

    suspend fun http(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpReply =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                // HttpURLConnection has no PATCH; Google's APIs take it as an override header.
                if (method == "PATCH") {
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                } else {
                    conn.requestMethod = method
                }
                conn.connectTimeout = 20_000
                conn.readTimeout = 120_000
                conn.instanceFollowRedirects = true
                for ((k, v) in headers) conn.setRequestProperty(k, v)
                if (body != null) {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(body.size)
                    conn.outputStream.use { it.write(body) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                HttpReply(code, bytes, conn.getHeaderField("Location"))
            } finally {
                conn.disconnect()
            }
        }

    private suspend fun launch(host: ComponentActivity, request: IntentSenderRequest): ActivityResult =
        suspendCancellableCoroutine { cont ->
            lateinit var registration: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>
            registration = host.activityResultRegistry.register(
                "drive-sign-in-${System.nanoTime()}",
                ActivityResultContracts.StartIntentSenderForResult(),
            ) { result ->
                registration.unregister()
                if (cont.isActive) cont.resume(result)
            }
            cont.invokeOnCancellation { registration.unregister() }
            try {
                registration.launch(request)
            } catch (e: Exception) {
                registration.unregister()
                if (cont.isActive) cont.resume(ActivityResult(Activity.RESULT_CANCELED, Intent()))
            }
        }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
