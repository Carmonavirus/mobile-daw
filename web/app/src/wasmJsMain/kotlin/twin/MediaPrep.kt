// Web version of app/src/main/java/com/mobiledaw/sound/MediaPrep.kt: the browser decodes a
// downloaded file (MP3, MP4/M4A...) up front, so the app's synchronous MediaExtractor can read it.
@file:Suppress("PackageDirectoryMismatch")

package com.mobiledaw.sound

import com.mobiledaw.web.DecodedAudio
import com.mobiledaw.web.toJs
import com.mobiledaw.web.toKotlin
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Int8Array
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private external fun decodeBytes(bytes: Int8Array, done: (Int, Float32Array?, Float32Array?) -> Unit)

object MediaPrep {
    suspend fun prepare(file: File) {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return
        suspendCoroutine { cont ->
            decodeBytes(bytes.toJs()) { rate, left, right ->
                if (rate > 0 && left != null) DecodedAudio.register(bytes, rate, listOfNotNull(left.toKotlin(), right?.toKotlin()))
                cont.resume(Unit)
            }
        }
    }
}
