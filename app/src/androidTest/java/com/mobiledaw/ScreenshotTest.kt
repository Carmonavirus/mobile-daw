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

    /** The mixer with the Vocals track, then its Parametric EQ and Pitch Correct editors. */
    @Test
    fun vocalChain() {
        rule.runOnUiThread {
            val p = vm.project
            // Send the lead synth through the Vocals track so the plugins have something to show.
            p.channels.firstOrNull { it.instrument == com.mobiledaw.model.Instrument.LEAD }?.let {
                vm.routeChannel(it.id, com.mobiledaw.model.VOCALS_TRACK_ID)
            }
            vm.tab = Tab.MIXER
            if (!vm.playing) vm.togglePlay()
        }
        shot("40-mixer-vocals-track")

        val track = vm.project.mixerTrack(com.mobiledaw.model.VOCALS_TRACK_ID) ?: error("no Vocals track")
        val eq = track.effects.first { it.type == com.mobiledaw.model.EffectType.EQ }
        rule.runOnUiThread {
            vm.updateEffect(track.id, eq.id) {
                it.copy(
                    eq = com.mobiledaw.model.EqSettings(
                        listOf(
                            com.mobiledaw.model.EqBand(com.mobiledaw.model.EqBandType.LOW_CUT, 90f, 0f, 0.71f, slope = 2),
                            com.mobiledaw.model.EqBand(com.mobiledaw.model.EqBandType.BELL, 320f, -4.5f, 1.4f),
                            com.mobiledaw.model.EqBand(com.mobiledaw.model.EqBandType.BELL, 3200f, 5f, 0.9f),
                            com.mobiledaw.model.EqBand(com.mobiledaw.model.EqBandType.HIGH_SHELF, 11000f, 3f, 0.71f),
                        ),
                    ),
                )
            }
            vm.openEffect(track.id, eq.id)
        }
        Thread.sleep(1200) // let the spectrum fill in
        shot("41-parametric-eq")
        tap("3")
        shot("42-parametric-eq-band-3")
        rule.runOnUiThread { vm.closeEffect() }

        val pitch = track.effects.first { it.type == com.mobiledaw.model.EffectType.PITCH }
        rule.runOnUiThread { vm.openEffect(track.id, pitch.id) }
        Thread.sleep(800)
        shot("43-pitch-correct")
        tap("Robot")
        shot("44-pitch-correct-robot")
        rule.runOnUiThread { vm.closeEffect() }
    }
}
