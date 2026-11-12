package com.mobiledaw

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import com.mobiledaw.model.Instrument
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Takes screenshots of the app on an emulator (see .github/workflows/screenshots.yml).
 * Add a test here for each new instrument or screen, so every change comes with pictures.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val vm: DawViewModel get() = ViewModelProvider(rule.activity)[DawViewModel::class.java]

    /** Whole screen, including dialogs, saved as <name>.png in the test output. */
    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(600) // let sounds start and animations settle
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        TestStorage().openOutputFile("screenshots/$name.png").use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tap(text: String) {
        rule.onAllNodesWithText(text)[0].performClick()
    }

    @Test
    fun rack() {
        shot("01-rack")
    }

    @Test
    fun wavetableSynth() {
        rule.runOnUiThread { vm.addChannel(Instrument.WAVETABLE) }
        val id = vm.synthEditorChannelId ?: error("synth editor did not open")
        fun load(name: String) = rule.runOnUiThread {
            val preset = vm.synthPresets.factory.first { it.name == name }
            vm.loadSynthPreset(id, preset)
        }

        load("Warm Strings")
        shot("10-synth-oscillators-warm-strings")
        tap("Filter")
        shot("11-synth-filter-warm-strings")
        tap("Movement")
        shot("12-synth-movement-warm-strings")
        tap("Voice")
        shot("13-synth-voice-warm-strings")

        tap("Oscillators")
        load("Deep 808")
        shot("20-synth-oscillators-deep-808")
        load("Growl Bass")
        shot("21-synth-oscillators-growl-bass")
        tap("Movement")
        shot("22-synth-movement-growl-bass")
        load("Clear Bell")
        tap("Oscillators")
        shot("23-synth-oscillators-clear-bell")

        rule.onAllNodesWithText("tap to browse", substring = true)[0].performClick()
        shot("30-synth-presets")
    }
}
