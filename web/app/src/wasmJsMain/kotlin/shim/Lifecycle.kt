@file:Suppress("unused", "PackageDirectoryMismatch", "UNCHECKED_CAST")

package androidx.lifecycle

/** Android's AndroidViewModel: a ViewModel that knows the Application. */
open class AndroidViewModel(private val app: android.app.Application) : ViewModel() {
    fun <T : android.app.Application> getApplication(): T = app as T
}
