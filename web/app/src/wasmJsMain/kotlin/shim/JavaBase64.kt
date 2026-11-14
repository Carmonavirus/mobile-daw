@file:Suppress("unused", "PackageDirectoryMismatch")

package java.util

import kotlin.io.encoding.ExperimentalEncodingApi

object Base64 {
    class Encoder internal constructor(private val url: Boolean, private val pad: Boolean) {
        fun withoutPadding(): Encoder = Encoder(url, false)
        @OptIn(ExperimentalEncodingApi::class)
        fun encodeToString(bytes: ByteArray): String {
            val s = (if (url) kotlin.io.encoding.Base64.UrlSafe else kotlin.io.encoding.Base64.Default).encode(bytes)
            return if (pad) s else s.trimEnd('=')
        }
    }
    class Decoder internal constructor(private val url: Boolean) {
        @OptIn(ExperimentalEncodingApi::class)
        fun decode(s: String): ByteArray = (if (url) kotlin.io.encoding.Base64.UrlSafe else kotlin.io.encoding.Base64.Default).decode(s)
    }
    fun getEncoder() = Encoder(false, true)
    fun getUrlEncoder() = Encoder(true, true)
    fun getDecoder() = Decoder(false)
    fun getUrlDecoder() = Decoder(true)
}
