package com.mobiledaw

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import com.mobiledaw.model.TICKS_PER_BAR
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
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
        android.util.Log.i("Shot", "taking $name")
        rule.waitForIdle()
        Thread.sleep(600) // let sounds start and animations settle
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        TestStorage().openOutputFile("screenshots/$name.png").use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        android.util.Log.i("Shot", "saved $name")
    }

    @Test
    fun rack() {
        shot("01-rack-transport")
    }

    @Test
    fun recording() {
        rule.runOnUiThread { vm.tab = Tab.PLAYLIST }
        shot("60-playlist-transport")

        // Grant the mic up front so the permission dialog doesn't cover the screen.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(rule.activity.packageName, android.Manifest.permission.RECORD_AUDIO)
        rule.runOnUiThread { vm.enterRecordMode() }
        shot("62-record-mode")

        // Count-in (stopped before the mic opens; the emulator has no microphone).
        rule.runOnUiThread { vm.toggleTake() }
        Thread.sleep(150)
        shot("63-count-in")
        rule.runOnUiThread { vm.stopTake() }

        // Two takes, made from a synthetic voice-like signal, as if recorded.
        rule.runOnUiThread {
            vm.placeRecording(fakeTake(vm.recordingFile("shot-1.wav"), 6f), 6f, 2, 0)
            vm.placeRecording(fakeTake(vm.recordingFile("shot-2.wav"), 4f), 4f, 3, TICKS_PER_BAR * 2)
        }
        shot("64-takes-on-playlist")

        rule.runOnUiThread { vm.exitRecordMode() }
        shot("65-back-to-normal")
    }

    private fun fakeTake(file: File, seconds: Float): File {
        val sr = 48000
        val n = (sr * seconds).toInt()
        val data = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            // Syllable-like bursts.
            val env = abs(sin(PI * t * 2.3)) * (0.4 + 0.6 * abs(sin(PI * t * 0.37)))
            val s = env * (sin(2 * PI * 220 * t) * 0.6 + sin(2 * PI * 440 * t) * 0.25)
            data.putShort((s * 0.8 * 32767).toInt().toShort())
        }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + n * 2); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(sr); h.putInt(sr * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(n * 2)
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(0); it.write(h.array()); it.write(data.array()) }
        return file
    }
}
