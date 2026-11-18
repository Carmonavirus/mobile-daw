package com.mobiledaw.ui.fx

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/** Turns a block of samples into a smoothed level per display column, for the EQ's spectrum. */
class SpectrumAnalyser(private val size: Int = 2048, private val sampleRate: Float = 48000f) {
    private val window = FloatArray(size) { (0.5 - 0.5 * cos(2 * PI * it / (size - 1))).toFloat() }
    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private var smoothed = FloatArray(0)

    /** Returns dB (about -100..0) at each of [freqs], rising fast and falling slowly. */
    fun analyse(samples: FloatArray, freqs: DoubleArray): FloatArray {
        for (i in 0 until size) {
            re[i] = samples.getOrElse(i) { 0f } * window[i]
            im[i] = 0f
        }
        fft(re, im)
        if (smoothed.size != freqs.size) smoothed = FloatArray(freqs.size) { -120f }
        val binHz = sampleRate / size
        for (c in freqs.indices) {
            // Average the bins between this column and the next, at least one.
            val lo = (freqs[c] / binHz).toInt().coerceIn(1, size / 2 - 1)
            val hi = (if (c + 1 < freqs.size) (freqs[c + 1] / binHz).toInt() else lo + 1).coerceIn(lo + 1, size / 2)
            var power = 0.0
            for (b in lo until hi) power = maxOf(power, (re[b] * re[b] + im[b] * im[b]).toDouble())
            // Normalise so a full-scale sine reads about 0 dB, and tilt +4.5 dB/oct like most analysers.
            val db = (10 * log10(power.coerceAtLeast(1e-14)) - 20 * log10(size / 4.0) +
                4.5 * kotlin.math.ln(freqs[c] / 1000.0) / kotlin.math.ln(2.0)).toFloat()
            smoothed[c] = if (db > smoothed[c]) db else smoothed[c] - 1.6f
        }
        return smoothed.copyOf()
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
