package com.mobiledaw

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import com.mobiledaw.ui.DawApp
import com.mobiledaw.ui.theme.DawColors
import com.mobiledaw.ui.theme.DawTheme
import com.mobiledaw.ui.theme.ThemeStore

class MainActivity : ComponentActivity() {
    private val vm: DawViewModel by viewModels()

    /** True when this activity replaces one that went away for a config change (onStop skipped onPause). */
    private var resumedFromConfigChange = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resumedFromConfigChange = savedInstanceState?.getBoolean(KEY_CONFIG_CHANGE) == true
        allowRotationOnTablets()
        // Google sign-in for Drive sync shows its consent screen over this activity.
        com.mobiledaw.cloud.CloudPlatform.attach(this)
        ThemeStore.load(this)
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(transparent, transparent),
            navigationBarStyle = SystemBarStyle.light(transparent, transparent),
        )
        setContent {
            // Dark icons on light themes, light icons on dark themes.
            val dark = DawColors.IsDark
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            DawTheme { DawApp(vm) }
        }
        // Opened with "Share" or "Open with" on an audio file (not again after a rotation/restore).
        if (savedInstanceState == null) vm.receiveShared(intent)
    }

    /**
     * Tablets (smallest side 600dp or more) turn freely, like any app. Phones also turn to
     * landscape, but only after a 3-second countdown (see [PhoneTurn]), so a phone tilted by
     * accident while working doesn't flip. Rotation is handled in place (configChanges in the
     * manifest), so the activity isn't recreated and playback keeps going.
     */
    private val isTablet get() = resources.configuration.smallestScreenWidthDp >= TABLET_MIN_DP

    private val phoneTurn by lazy {
        PhoneTurn(
            context = this,
            onCountdown = { vm.turnCountdown = it },
            onTurn = { landscape ->
                requestedOrientation =
                    if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            },
        )
    }

    private fun allowRotationOnTablets() {
        if (isTablet) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        } else {
            // A phone keeps whatever it was turned to before (e.g. after coming back to the app).
            phoneTurn.landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A foldable opening or closing changes the smallest width: follow it.
        val tablet = newConfig.smallestScreenWidthDp >= TABLET_MIN_DP
        if (tablet) {
            phoneTurn.cancel()
            if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_FULL_USER) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            }
        } else if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_FULL_USER) {
            // Folded back into a phone: start upright, the countdown handles turning from here.
            phoneTurn.landscape = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isTablet) phoneTurn.start()
    }

    override fun onPause() {
        phoneTurn.stop()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.receiveShared(intent)
    }

    override fun onStart() {
        super.onStart()
        if (resumedFromConfigChange) {
            resumedFromConfigChange = false
            return
        }
        vm.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_CONFIG_CHANGE, isChangingConfigurations)
    }

    override fun onStop() {
        // Never stop the music just because the screen is being rebuilt (e.g. a rotation).
        if (isChangingConfigurations) {
            super.onStop()
            return
        }
        vm.onPause()
        super.onStop()
    }

    private companion object {
        const val TABLET_MIN_DP = 600
        const val KEY_CONFIG_CHANGE = "config_change"
    }
}
