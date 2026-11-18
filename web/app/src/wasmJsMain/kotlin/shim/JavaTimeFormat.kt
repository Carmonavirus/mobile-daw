@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER")

package java.time.format

private fun localParts(ms: Double): String =
    js("{ const d = new Date(ms); return [d.getFullYear(), d.getMonth(), d.getDate(), d.getHours(), d.getMinutes(), d.getSeconds()].join(','); }")

/** Formats with the few pattern letters the app uses: yyyy MMM MM M dd d HH mm ss. */
class DateTimeFormatter private constructor(private val pattern: String) {
    fun withZone(zone: java.time.ZoneId): DateTimeFormatter = this
    fun format(instant: java.time.Instant): String {
        val p = localParts(instant.toEpochMilli().toDouble()).split(',').map { it.toInt() }
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            var n = 1
            while (i + n < pattern.length && pattern[i + n] == c) n++
            sb.append(
                when (c) {
                    'y' -> p[0].toString()
                    'M' -> if (n >= 3) months[p[1]] else (p[1] + 1).toString().padStart(n, '0')
                    'd' -> p[2].toString().padStart(n, '0')
                    'H' -> p[3].toString().padStart(n, '0')
                    'm' -> p[4].toString().padStart(n, '0')
                    's' -> p[5].toString().padStart(n, '0')
                    else -> c.toString().repeat(n)
                },
            )
            i += n
        }
        return sb.toString()
    }
    companion object {
        fun ofPattern(p: String) = DateTimeFormatter(p)
    }
}
