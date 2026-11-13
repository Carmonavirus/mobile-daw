package com.mobiledaw.web

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** A small String.format for the patterns the app uses: %d %s %f %x %%, with flags + 0 , and width/precision. */
fun formatString(pattern: String, args: Array<out Any?>): String {
    val sb = StringBuilder()
    var i = 0
    var argIndex = 0
    while (i < pattern.length) {
        val c = pattern[i]
        if (c != '%') {
            sb.append(c)
            i++
            continue
        }
        i++
        if (i < pattern.length && pattern[i] == '%') {
            sb.append('%')
            i++
            continue
        }
        var plus = false
        var zero = false
        var left = false
        var comma = false
        while (i < pattern.length && pattern[i] in "+0-, ") {
            when (pattern[i]) {
                '+' -> plus = true
                '0' -> zero = true
                '-' -> left = true
                ',' -> comma = true
            }
            i++
        }
        var width = 0
        while (i < pattern.length && pattern[i].isDigit()) width = width * 10 + (pattern[i++] - '0')
        var precision = -1
        if (i < pattern.length && pattern[i] == '.') {
            i++
            precision = 0
            while (i < pattern.length && pattern[i].isDigit()) precision = precision * 10 + (pattern[i++] - '0')
        }
        val conv = if (i < pattern.length) pattern[i++] else 's'
        val arg = args.getOrNull(argIndex++)
        var body = when (conv) {
            'd' -> {
                val v = (arg as? Number)?.toLong() ?: 0L
                val digits = abs(v).toString().let { if (comma) groupThousands(it) else it }
                (if (v < 0) "-" else if (plus) "+" else "") + digits
            }
            'f' -> {
                val v = (arg as? Number)?.toDouble() ?: 0.0
                (if (plus && v >= 0) "+" else "") + fixed(v, if (precision < 0) 6 else precision)
            }
            'x', 'X' -> {
                val v = (arg as? Number)?.toLong() ?: 0L
                val s = (if (v < 0) v and 0xffffffffL else v).toString(16)
                if (conv == 'X') s.uppercase() else s
            }
            else -> arg.toString().let { if (precision >= 0) it.take(precision) else it }
        }
        if (body.length < width) {
            body = when {
                left -> body.padEnd(width)
                zero && conv != 's' -> {
                    val sign = if (body.startsWith("-") || body.startsWith("+")) body.take(1) else ""
                    sign + body.drop(sign.length).padStart(width - sign.length, '0')
                }
                else -> body.padStart(width)
            }
        }
        sb.append(body)
    }
    return sb.toString()
}

private fun groupThousands(s: String): String = s.reversed().chunked(3).joinToString(",").reversed()

private fun fixed(v: Double, digits: Int): String {
    if (v.isNaN()) return "NaN"
    if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"
    val scale = 10.0.pow(digits)
    val r = (abs(v) * scale).roundToLong()
    val whole = r / scale.toLong()
    val frac = r % scale.toLong()
    val neg = v < 0 && r != 0L
    return (if (neg) "-" else "") + whole + if (digits > 0) "." + frac.toString().padStart(digits, '0') else ""
}
