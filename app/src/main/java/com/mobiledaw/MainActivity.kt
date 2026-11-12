package com.mobiledaw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.mobiledaw.ui.DawApp
import com.mobiledaw.ui.theme.DawTheme

class MainActivity : ComponentActivity() {
    private val vm: DawViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The UI is always light, so always use dark status/navigation bar icons.
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(transparent, transparent),
            navigationBarStyle = SystemBarStyle.light(transparent, transparent),
        )
        setContent {
            DawTheme { DawApp(vm) }
        }
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
