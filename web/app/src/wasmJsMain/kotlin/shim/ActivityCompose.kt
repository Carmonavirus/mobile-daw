@file:Suppress("unused", "PackageDirectoryMismatch")

package androidx.activity.compose

import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

class ManagedActivityResultLauncher<I, O>(private val contract: ActivityResultContract<I, O>, private val onResult: () -> (O) -> Unit) {
    fun launch(input: I) = contract.launch(input) { onResult()(it) }
    fun launch(input: I, options: Any?) = launch(input)
}

@Composable
fun <I, O> rememberLauncherForActivityResult(contract: ActivityResultContract<I, O>, onResult: (O) -> Unit): ManagedActivityResultLauncher<I, O> {
    val current = rememberUpdatedState(onResult)
    return remember(contract) { ManagedActivityResultLauncher(contract) { current.value } }
}
