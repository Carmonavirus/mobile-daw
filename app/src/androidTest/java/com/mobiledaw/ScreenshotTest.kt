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

    /** Screenshot without waiting for Compose to go idle: while the song plays it never does. */
    private fun liveShot(name: String, settleMs: Long = 900) {
        Thread.sleep(settleMs)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        TestStorage().openOutputFile("screenshots/$name.png").use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The mixer with the Vocals track, then its Parametric EQ and Pitch Correct editors, with music playing. */
    @Test
    fun vocalChain() {
        val trackId = com.mobiledaw.model.VOCALS_TRACK_ID
        rule.runOnUiThread {
            // Send the lead synth through the Vocals track so the plugins have something to show.
            vm.project.channels.firstOrNull { it.instrument == Instrument.LEAD }?.let { vm.routeChannel(it.id, trackId) }
            vm.tab = Tab.MIXER
        }
        rule.waitForIdle()
        try {
            rule.runOnUiThread { if (!vm.playing) vm.togglePlay() }
            liveShot("40-mixer-vocals-track", 1500)

            val track = vm.project.mixerTrack(trackId) ?: error("no Vocals track")
            val eq = track.effects.first { it.type == com.mobiledaw.model.EffectType.EQ }
            rule.runOnUiThread {
                vm.updateEffect(trackId, eq.id) {
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
                vm.openEffect(trackId, eq.id)
            }
            liveShot("41-parametric-eq", 2000)
            rule.runOnUiThread { vm.closeEffect() }

            val pitch = track.effects.first { it.type == com.mobiledaw.model.EffectType.PITCH }
            rule.runOnUiThread { vm.openEffect(trackId, pitch.id) }
            liveShot("42-pitch-correct", 1500)
            rule.runOnUiThread { vm.updateEffect(trackId, pitch.id) { it.copy(pitch = it.pitch?.copy(retuneMs = 0f, humanize = 0f)) } }
            liveShot("43-pitch-correct-robot", 1200)
            rule.runOnUiThread { vm.closeEffect() }
        } finally {
            // The engine outlives the activity: stop it so later tests can go idle.
            rule.runOnUiThread { if (vm.playing) vm.togglePlay() }
        }
    }

    /** Piano roll Chords panel on a Wavetable channel in G minor. */
    @Test
    fun chordGenerator() {
        var id = 0L
        rule.runOnUiThread {
            vm.addChannel(Instrument.WAVETABLE)
            id = vm.synthEditorChannelId ?: error("synth editor did not open")
            vm.loadSynthPreset(id, vm.synthPresets.factory.first { it.name == "Warm Strings" })
            vm.closeSynth()
            vm.setKey(7, true) // G minor
            vm.selectChannel(id)
            vm.tab = Tab.PIANO
        }
        liveShot("50-piano-roll-chords-button", 1500)
        rule.runOnUiThread { vm.openChordGen() }
        liveShot("51-chords-conventional", 1500)
        rule.runOnUiThread {
            vm.toggleChordLock(0)
            vm.changeChordSettings { it.copy(adventure = 0.85f, rhythm = com.mobiledaw.model.ChordRhythm.ARP) }
            vm.generateChords()
        }
        liveShot("52-chords-adventurous-arp")
        rule.runOnUiThread {
            vm.changeChordSettings { it.copy(count = 8, lengthTicks = com.mobiledaw.model.TICKS_PER_BAR / 2, rhythm = com.mobiledaw.model.ChordRhythm.HOLD) }
        }
        liveShot("53-chords-eight-half-bars")
        rule.runOnUiThread { vm.closeChordGen(keep = true) }
        liveShot("54-chords-kept-in-piano-roll")
    }
}
