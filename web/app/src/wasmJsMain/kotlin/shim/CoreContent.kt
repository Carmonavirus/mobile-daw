@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER")

package androidx.core.content

object ContextCompat {
    /** The browser asks for the microphone itself when recording starts. */
    fun checkSelfPermission(context: android.content.Context, permission: String): Int = com.mobiledaw.web.Picker.permissionState(permission)
}
