package com.mobiledaw

import android.graphics.Bitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.mobiledaw.model.Instrument
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Takes screenshots of the app on an emulator (see .github/workflows/screenshots.yml).
 * Add a test here for each new instrument or screen, so every change comes with pictures.
 *
 * The app redraws its meters ~30 times a second, so Compose never goes idle; this test therefore
 * uses UiAutomator (finds on-screen text) and plain waits instead of the Compose test rule.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        device.wait(Until.hasObject(By.text("Rack")), 10_000)
        Thread.sleep(1500)
        dismissSystemDialogs()
    }

    @After
    fun close() = scenario.close()

    /**
     * The emulator sometimes shows "Process system isn't responding" while it is still busy after boot.
     * That dialog belongs to Android, not the app; tap "Wait" so it doesn't cover the screen.
     */
    private fun dismissSystemDialogs() {
        repeat(3) {
            if (device.findObject(By.textContains("isn't responding")) == null) return
            (device.findObject(By.text("Wait")) ?: device.findObject(By.res("android:id/aerr_wait")))?.click()
            Thread.sleep(500)
        }
    }

    private fun onVm(block: (DawViewModel) -> Unit) {
        scenario.onActivity { block(ViewModelProvider(it)[DawViewModel::class.java]) }
    }

    /** Whole screen, including dialogs, saved as screenshots/<name>.png in the test output. */
    private fun shot(name: String) {
        dismissSystemDialogs()
        Thread.sleep(800) // let the screen settle
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        TestStorage().openOutputFile("screenshots/$name.png").use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Taps the first visible element showing exactly [text] (or containing it with [partial]). */
    private fun tap(text: String, partial: Boolean = false) {
        dismissSystemDialogs()
        val selector = if (partial) By.textContains(text) else By.text(text)
        val obj = device.wait(Until.findObject(selector), 5_000) ?: error("\"$text\" not on screen")
        obj.click()
    }

    /** Like [tap], but scrolls down first when [text] is below the fold. */
    private fun tapScrolling(text: String) {
        repeat(8) {
            dismissSystemDialogs()
            device.findObject(By.text(text))?.let { it.click(); return }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
            Thread.sleep(300)
        }
        tap(text)
    }

    @Test
    fun rack() {
        shot("01-rack")
    }

    @Test
    fun wavetableSynth() {
        var id = 0L
        onVm { vm ->
            vm.addChannel(Instrument.WAVETABLE)
            id = vm.synthEditorChannelId ?: error("synth editor did not open")
        }
        fun load(name: String) = onVm { vm -> vm.loadSynthPreset(id, vm.synthPresets.factory.first { it.name == name }) }
        device.wait(Until.hasObject(By.text("Oscillators")), 5_000)

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
        tap("Oscillators")
        load("Clear Bell")
        shot("23-synth-oscillators-clear-bell")

        tap("tap to browse", partial = true)
        shot("30-synth-presets")
    }

    @Test
    fun soundLibrary() {
        onVm { it.settingsOpen = true }
        device.wait(Until.hasObject(By.text("Settings")), 5_000)
        tapScrolling("Sound library")
        device.wait(Until.hasObject(By.text("Credits & licences")), 5_000)
        shot("40-library-home")
        val pages = listOf(
            "SoundFont instruments" to "41-library-instruments",
            "Synth engines" to "42-library-synths",
            "SFZ instruments" to "43-library-sfz",
            "Freesound" to "44-library-freesound",
            "Wikimedia Commons" to "45-library-commons",
            "Internet Archive" to "46-library-archive",
            "Free sample sites" to "47-library-free-sites",
            "Credits & licences" to "48-library-credits",
        )
        for ((title, name) in pages) {
            tapScrolling(title)
            when (title) {
                "SoundFont instruments" -> device.wait(Until.hasObject(By.textContains("Piano")), 10_000)
                "Wikimedia Commons", "Internet Archive" -> Thread.sleep(4000)  // first search
            }
            shot(name)
            device.pressBack()  // back to the list of sources
            device.wait(Until.hasObject(By.text("Sound library")), 5_000)
        }
    }

    @Test
    fun soundPicker() {
        var firstName = ""
        onVm { vm ->
            // A Macro synth channel shows its preset name in the rack.
            val macro = vm.sounds.macroPresets.first()
            vm.applyChoice(null, com.mobiledaw.sound.SoundChoice.Engine(macro.instrument, macro.index, null, null, macro.name))
            firstName = vm.project.channels.first().name
        }
        shot("50-rack-with-macro-synth")
        tap(firstName)
        shot("51-picker-kits")
        tap("Instruments")
        device.wait(Until.hasObject(By.textContains("Piano")), 10_000)
        shot("52-picker-instruments")
        tap("Synths")
        shot("53-picker-synths")
    }
}
