@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER", "UNCHECKED_CAST")

package androidx.activity.result.contract

import android.net.Uri

/** The Android "pick a file / folder / permission" contracts, done with the browser's pickers. */
abstract class ActivityResultContract<I, O> {
    internal abstract fun launch(input: I, done: (O) -> Unit)
}

object ActivityResultContracts {
    class GetContent : ActivityResultContract<String, Uri?>() {
        override fun launch(input: String, done: (Uri?) -> Unit) = com.mobiledaw.web.Picker.pickFiles(input, multiple = false) { done(it.firstOrNull()) }
    }
    class GetMultipleContents : ActivityResultContract<String, List<Uri>>() {
        override fun launch(input: String, done: (List<Uri>) -> Unit) = com.mobiledaw.web.Picker.pickFiles(input, multiple = true, done)
    }
    class OpenDocument : ActivityResultContract<Array<String>, Uri?>() {
        override fun launch(input: Array<String>, done: (Uri?) -> Unit) = com.mobiledaw.web.Picker.pickFiles(input.joinToString(","), multiple = false) { done(it.firstOrNull()) }
    }
    class OpenMultipleDocuments : ActivityResultContract<Array<String>, List<Uri>>() {
        override fun launch(input: Array<String>, done: (List<Uri>) -> Unit) = com.mobiledaw.web.Picker.pickFiles(input.joinToString(","), multiple = true, done)
    }
    class OpenDocumentTree : ActivityResultContract<Uri?, Uri?>() {
        override fun launch(input: Uri?, done: (Uri?) -> Unit) = com.mobiledaw.web.Picker.pickFolder(done)
    }
    class RequestPermission : ActivityResultContract<String, Boolean>() {
        override fun launch(input: String, done: (Boolean) -> Unit) = com.mobiledaw.web.Picker.requestPermission(input, done)
    }
    class CreateDocument(val mimeType: String = "*/*") : ActivityResultContract<String, Uri?>() {
        override fun launch(input: String, done: (Uri?) -> Unit) = done(null)
    }
}
