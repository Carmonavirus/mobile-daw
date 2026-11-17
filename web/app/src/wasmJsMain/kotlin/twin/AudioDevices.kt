// Web version of app/src/main/java/com/mobiledaw/audio/AudioDevices.kt: the browser's speakers and
// mics (navigator.mediaDevices in platform.js). The output is switched with AudioContext.setSinkId
// where the browser has it (Chrome, Edge); the mic with getUserMedia's deviceId everywhere.
@file:Suppress("PackageDirectoryMismatch", "UNUSED_PARAMETER")

package com.mobiledaw.audio

import android.content.Context

private external fun audioDevicesList(done: (String) -> Unit)
private external fun audioCanPickOutput(): Boolean
private external fun audioNeedNames(): Boolean
private external fun audioSetOutput(id: String)
private external fun audioSetInput(id: String)
private external fun audioWatch(onChange: () -> Unit)
private external fun askMicPermission(done: (Boolean) -> Unit)

object AudioDevices {
    val outputNote: String?
        get() = if (audioCanPickOutput()) null
        else "This browser always plays to the system's output. Change it in your computer's or phone's sound settings (on iPhone, Control Center)."
    val inputNote: String? = null
    val needsPermissionForNames: Boolean get() = audioNeedNames()
    fun askForNames(done: () -> Unit) = askMicPermission { done() }

    private var watching = false

    fun list(context: Context, done: (outputs: List<AudioPort>, inputs: List<AudioPort>) -> Unit) {
        audioDevicesList { text ->
            val outs = ArrayList<AudioPort>()
            val ins = ArrayList<AudioPort>()
            for (line in text.lines()) {
                val parts = line.split('\t')
                if (parts.size < 3) continue
                val (kind, id, label) = parts
                if (kind == "out" && !audioCanPickOutput()) continue
                val list = if (kind == "in") ins else outs
                list += AudioPort(id, label.ifBlank { if (kind == "in") "Microphone ${ins.size + 1}" else "Speaker ${outs.size + 1}" })
            }
            done(outs, ins)
        }
    }

    fun useOutput(context: Context, key: String?) = audioSetOutput(key.orEmpty())

    fun useInput(context: Context, key: String?) = audioSetInput(key.orEmpty())

    fun watch(context: Context, onChange: () -> Unit) {
        if (watching) return
        watching = true
        audioWatch(onChange)
    }
}
