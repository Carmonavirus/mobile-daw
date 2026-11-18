package com.mobiledaw.ui

import kotlin.math.log10
import kotlin.math.roundToInt

/** Quietest level we print; anything below reads as silence (-∞ dB). */
const val SILENT_DB = -90f

/** Linear gain (1 = unity) to decibels. Zero or less is [Float.NEGATIVE_INFINITY]. */
fun gainToDb(gain: Float): Float = if (gain <= 0f) Float.NEGATIVE_INFINITY else 20f * log10(gain)

/** "-6.0 dB", "+3.5 dB", "0.0 dB" or "-∞ dB" for a linear gain. */
fun formatGainDb(gain: Float): String {
    val db = gainToDb(gain)
    if (db < SILENT_DB) return "-∞ dB"
    val tenths = (db * 10f).roundToInt()
    if (tenths == 0) return "0.0 dB"
    return (if (tenths > 0) "+" else "") + "%.1f dB".format(tenths / 10f)
}

/** Volume readout with both units, e.g. "-1.9 dB · 80%". */
fun formatVolume(gain: Float): String = "${formatGainDb(gain)} · ${(gain * 100).roundToInt()}%"

/** Meter readout, whole decibels so it stays readable while it moves: "-12 dB", "-∞". */
fun formatMeterDb(level: Float, floorDb: Float = METER_FLOOR_DB): String {
    val db = gainToDb(level)
    if (db < floorDb) return "-∞"
    val whole = db.roundToInt()
    return if (whole >= 0) "0 dB" else "$whole dB"
}

/** Bottom of the level meters' decibel scale. */
const val METER_FLOOR_DB = -60f

/** Where a linear peak sits on a meter drawn in decibels, 0..1 over [floorDb]..0 dB. */
fun meterFraction(level: Float, floorDb: Float = METER_FLOOR_DB): Float {
    val db = gainToDb(level)
    if (db <= floorDb) return 0f
    return ((db - floorDb) / -floorDb).coerceIn(0f, 1f)
}
