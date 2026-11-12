package com.mobiledaw.ui.fx

import com.mobiledaw.model.EqBand
import com.mobiledaw.model.EqBandType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The EQ's frequency response, for drawing. Same RBJ-cookbook formulas as
 * app/src/main/cpp/fx/ParametricEq.cpp, so the curve matches what you hear.
 */
object EqMath {
    const val SAMPLE_RATE = 48000.0

    private class Coeffs(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    private fun design(type: EqBandType, freq: Double, gainDb: Double, q: Double): Coeffs {
        val f = freq.coerceIn(10.0, SAMPLE_RATE * 0.49)
        val qq = q.coerceIn(0.1, 30.0)
        val w0 = 2 * PI * f / SAMPLE_RATE
        val cw = cos(w0)
        val sw = sin(w0)
        val alpha = sw / (2 * qq)
        val a = 10.0.pow(gainDb / 40)
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a0 = 1.0; var a1 = 0.0; var a2 = 0.0
        when (type) {
            EqBandType.BELL -> {
                b0 = 1 + alpha * a; b1 = -2 * cw; b2 = 1 - alpha * a
                a0 = 1 + alpha / a; a1 = -2 * cw; a2 = 1 - alpha / a
            }
            EqBandType.LOW_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                b0 = a * ((a + 1) - (a - 1) * cw + s); b1 = 2 * a * ((a - 1) - (a + 1) * cw); b2 = a * ((a + 1) - (a - 1) * cw - s)
                a0 = (a + 1) + (a - 1) * cw + s; a1 = -2 * ((a - 1) + (a + 1) * cw); a2 = (a + 1) + (a - 1) * cw - s
            }
            EqBandType.HIGH_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                b0 = a * ((a + 1) + (a - 1) * cw + s); b1 = -2 * a * ((a - 1) + (a + 1) * cw); b2 = a * ((a + 1) + (a - 1) * cw - s)
                a0 = (a + 1) - (a - 1) * cw + s; a1 = 2 * ((a - 1) - (a + 1) * cw); a2 = (a + 1) - (a - 1) * cw - s
            }
            EqBandType.LOW_CUT -> {
                b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2
                a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
            }
            EqBandType.HIGH_CUT -> {
                b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2
                a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
            }
            EqBandType.NOTCH -> {
                b0 = 1.0; b1 = -2 * cw; b2 = 1.0
                a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
            }
        }
        return Coeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun magnitudeDb(c: Coeffs, hz: Double): Double {
        val w = 2 * PI * hz / SAMPLE_RATE
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = c.b0 + c.b1 * c1 + c.b2 * c2
        val ni = -(c.b1 * s1 + c.b2 * s2)
        val dr = 1 + c.a1 * c1 + c.a2 * c2
        val di = -(c.a1 * s1 + c.a2 * s2)
        val num = nr * nr + ni * ni
        val den = dr * dr + di * di
        return 10 * log10((num / den).coerceAtLeast(1e-12))
    }

    /** One band's gain in dB at [hz] (0 when the band is off). */
    fun bandDb(band: EqBand, hz: Double): Double {
        if (!band.on) return 0.0
        val stages = if (band.type == EqBandType.LOW_CUT || band.type == EqBandType.HIGH_CUT) band.slope.coerceIn(1, 4) else 1
        var db = 0.0
        for (s in 0 until stages) {
            db += magnitudeDb(design(band.type, band.freq.toDouble(), band.gain.toDouble(), if (s == 0) band.q.toDouble() else 0.7071), hz)
        }
        return db
    }

    fun totalDb(bands: List<EqBand>, output: Float, hz: Double): Double =
        bands.sumOf { bandDb(it, hz) } + output

    /** One band's response (dB) at each of [freqs], designing its filters once. */
    fun bandCurve(band: EqBand, freqs: DoubleArray): DoubleArray {
        val out = DoubleArray(freqs.size)
        if (!band.on) return out
        val stages = if (band.type == EqBandType.LOW_CUT || band.type == EqBandType.HIGH_CUT) band.slope.coerceIn(1, 4) else 1
        val coeffs = (0 until stages).map {
            design(band.type, band.freq.toDouble(), band.gain.toDouble(), if (it == 0) band.q.toDouble() else 0.7071)
        }
        for (i in freqs.indices) out[i] = coeffs.sumOf { magnitudeDb(it, freqs[i]) }
        return out
    }
}
