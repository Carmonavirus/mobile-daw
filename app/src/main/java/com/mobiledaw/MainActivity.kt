package com.mobiledaw

import android.content.Intent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.receiveShared(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.onResume()
    }

    override fun onStop() {
        vm.onPause()
        super.onStop()
    }
}
