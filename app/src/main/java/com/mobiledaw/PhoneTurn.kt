package com.mobiledaw

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.OrientationEventListener

/** What the countdown overlay shows: seconds left and which way the screen is about to turn. */
data class TurnCountdown(val seconds: Int, val toLandscape: Boolean)

/**
 * Phone rotation with a safety delay. The screen stays locked; this watches how the phone is
 * actually held. When it has been turned (portrait to landscape, or back), the screen is
 * blocked with a 3, 2, 1 countdown and only then flips. Turning the phone back before the
 * countdown ends cancels it. Respects the phone's own auto-rotate switch.
 */
class PhoneTurn(
    private val context: Context,
    private val onCountdown: (TurnCountdown?) -> Unit,
    private val onTurn: (landscape: Boolean) -> Unit,
) {
    /** The orientation the screen is locked to right now. */
    var landscape = false

    private val handler = Handler(Looper.getMainLooper())
    private var target: Boolean? = null
    private var secondsLeft = 0

    private val listener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(degrees: Int) = onTilt(degrees)
    }

    private val tick = object : Runnable {
        override fun run() {
            val to = target ?: return
            secondsLeft--
            if (secondsLeft > 0) {
                onCountdown(TurnCountdown(secondsLeft, to))
                handler.postDelayed(this, 1000)
            } else {
                target = null
                onCountdown(null)
                landscape = to
                onTurn(to)
            }
        }
    }

    fun start() {
        if (listener.canDetectOrientation()) listener.enable()
    }

    fun stop() {
        listener.disable()
        cancel()
    }

    fun cancel() {
        handler.removeCallbacks(tick)
        if (target != null) onCountdown(null)
        target = null
    }

    private fun onTilt(degrees: Int) {
        if (degrees == OrientationEventListener.ORIENTATION_UNKNOWN) return // lying flat
        val held = when {
            degrees in 65..115 || degrees in 245..295 -> true
            degrees <= 25 || degrees >= 335 -> false
            // In between, or upside down: keep whatever is going on.
            else -> return
        }
        when {
            held == landscape -> cancel() // turned back in time (or never left)
            target == held -> Unit // already counting down
            !autoRotateOn() -> Unit
            else -> {
                target = held
                secondsLeft = SECONDS
                onCountdown(TurnCountdown(secondsLeft, held))
                handler.postDelayed(tick, 1000)
            }
        }
    }

    private fun autoRotateOn(): Boolean =
        Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1

    private companion object {
        const val SECONDS = 3
    }
}
