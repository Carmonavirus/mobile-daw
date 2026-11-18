@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER")

package java.time

private fun parseIso(s: String): Double = js("Date.parse(s)")

class Instant private constructor(val millis: Long) {
    fun toEpochMilli(): Long = millis
    companion object {
        fun parse(s: String): Instant {
            val v = parseIso(s)
            if (v.isNaN()) throw IllegalArgumentException(s)
            return Instant(v.toLong())
        }
        fun ofEpochMilli(ms: Long) = Instant(ms)
        fun now() = Instant(com.mobiledaw.web.Clock.now())
    }
}

class ZoneId private constructor() {
    companion object {
        fun systemDefault() = ZoneId()
    }
}
