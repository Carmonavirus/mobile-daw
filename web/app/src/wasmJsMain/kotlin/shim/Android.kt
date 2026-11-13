// Web stand-ins for the handful of Android classes the app's shared code touches.
@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER")

package android.content

import com.mobiledaw.web.Prefs
import java.io.File

open class Context {
    val filesDir: File = File("/files").also { it.mkdirs() }
    val cacheDir: File = File("/cache").also { it.mkdirs() }
    fun getExternalFilesDir(type: String?): File = File("/external/${type ?: ""}").also { it.mkdirs() }
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = Prefs.get(name)
    val applicationContext: Context get() = this
    val packageName: String get() = "com.mobiledaw"
    val contentResolver: ContentResolver = ContentResolver()
    val assets: android.content.res.AssetManager = android.content.res.AssetManager()
    val packageManager: android.content.pm.PackageManagerImpl = android.content.pm.PackageManagerImpl()
    fun startActivity(intent: Intent) {
        intent.data?.let { com.mobiledaw.web.Browser.open(it.key) }
    }

    fun getSystemService(name: String): Any? = if (name == AUDIO_SERVICE) android.media.AudioManager() else null

    companion object {
        const val MODE_PRIVATE = 0
        const val AUDIO_SERVICE = "audio"
    }
}

interface SharedPreferences {
    fun getBoolean(key: String, def: Boolean): Boolean
    fun getInt(key: String, def: Int): Int
    fun getLong(key: String, def: Long): Long
    fun getFloat(key: String, def: Float): Float
    fun getString(key: String, def: String?): String?
    fun getStringSet(key: String, def: Set<String>?): Set<String>?
    fun contains(key: String): Boolean
    val all: Map<String, *>
    fun edit(): Editor

    interface Editor {
        fun putBoolean(key: String, v: Boolean): Editor
        fun putInt(key: String, v: Int): Editor
        fun putLong(key: String, v: Long): Editor
        fun putFloat(key: String, v: Float): Editor
        fun putString(key: String, v: String?): Editor
        fun putStringSet(key: String, v: Set<String>?): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }
}

/** Intents only open web links here (the web has no share intents). */
class Intent(val action: String? = null, var data: android.net.Uri? = null) {
    var type: String? = null
    val clipData: ClipData? = null
    fun addFlags(flags: Int): Intent = this
    fun putExtra(name: String, value: Any?): Intent = this
    fun <T : Any> getParcelableExtra(name: String, cls: kotlin.reflect.KClass<T>): T? = null
    fun <T> getParcelableExtra(name: String): T? = null
    fun <T : Any> getParcelableArrayListExtra(name: String, cls: kotlin.reflect.KClass<T>): ArrayList<T>? = null
    fun <T> getParcelableArrayListExtra(name: String): ArrayList<T>? = null
    fun putParcelableArrayListExtra(name: String, value: ArrayList<*>): Intent = this
    companion object {
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val EXTRA_STREAM = "android.intent.extra.STREAM"
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
        const val FLAG_GRANT_READ_URI_PERMISSION = 1
    }
}

class ClipData {
    val itemCount: Int = 0
    fun getItemAt(i: Int): Item = Item()
    class Item { val uri: android.net.Uri? = null }
}

/** Reads files the person picked in the browser (see com.mobiledaw.web.Picked). */
class ContentResolver {
    fun openInputStream(uri: android.net.Uri): java.io.InputStream? =
        com.mobiledaw.web.Picked.bytes(uri)?.let { java.io.ByteArrayInputStream(it) }
    fun query(uri: android.net.Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): android.database.Cursor? =
        android.database.Cursor(mapOf(android.provider.OpenableColumns.DISPLAY_NAME to uri.name, android.provider.OpenableColumns.SIZE to (com.mobiledaw.web.Picked.bytes(uri)?.size ?: 0).toString()))
    fun getType(uri: android.net.Uri): String? = null
}
