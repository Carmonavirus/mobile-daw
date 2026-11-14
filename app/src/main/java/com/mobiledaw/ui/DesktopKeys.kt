package com.mobiledaw.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mobiledaw.DawViewModel
import com.mobiledaw.PlayMode
import com.mobiledaw.Tab
import com.mobiledaw.model.Instrument
import com.mobiledaw.model.Note
import com.mobiledaw.model.TICKS_PER_STEP

/** What the letter keys do: nothing, play the selected sound, or also write notes while playing. */
enum class TypingKeys(val label: String) { OFF("Off"), PLAY("Play"), RECORD("Play + record") }

/**
 * Computer keyboard and mouse support (the web app on a PC or laptop). Touch never reaches any of
 * this: the keys come from a physical keyboard, and the mouse parts check the pointer type.
 * Shortcuts follow FL Studio's where a browser allows them (F5–F9 screens, Space, Ctrl+B...).
 */
object Desktop {
    /** A mouse or trackpad is in use: shows the "Keyboard shortcuts" entry in Settings. */
    var active by mutableStateOf(false)
    var shortcutsOpen by mutableStateOf(false)
    var projectsRequested by mutableStateOf(false)
    /** A dialog of the main screen (projects, tempo) is open: its own keys win. */
    var dialogOpen by mutableStateOf(false)
    var typing by mutableStateOf(TypingKeys.OFF)
    /** Octave of the Z key row (Q row is one higher). C4 = 60. */
    var typingBase by mutableIntStateOf(60)

    /** Letter → semitones above [typingBase], FL-style two-row layout (KeyboardEvent.code names). */
    private val typingMap: Map<String, Int> = buildMap {
        listOf("KeyZ", "KeyS", "KeyX", "KeyD", "KeyC", "KeyV", "KeyG", "KeyB", "KeyH", "KeyN", "KeyJ", "KeyM", "Comma", "KeyL", "Period", "Semicolon", "Slash")
            .forEachIndexed { i, k -> put(k, i) }
        listOf("KeyQ", "Digit2", "KeyW", "Digit3", "KeyE", "KeyR", "Digit5", "KeyT", "Digit6", "KeyY", "Digit7", "KeyU", "KeyI", "Digit9", "KeyO", "Digit0", "KeyP", "BracketLeft")
            .forEachIndexed { i, k -> put(k, 12 + i) }
    }

    private class Held(val pitch: Int, val channelId: Long, val startTick: Long?, val synth: Boolean)
    private val held = HashMap<String, Held>()

    private fun pianoVisible(vm: DawViewModel) = vm.tab == Tab.PIANO || (vm.wideLayout && vm.tab == Tab.RACK)

    /**
     * One key press or release from the page. [code] is the physical key (KeyboardEvent.code) and
     * [ctrl] means Ctrl, or Cmd on a Mac. Returns true when the app used the key, so the browser
     * should not act on it as well.
     */
    fun onKey(vm: DawViewModel, code: String, down: Boolean, ctrl: Boolean, shift: Boolean, alt: Boolean, repeat: Boolean): Boolean {
        if (!down) return releaseTyping(vm, code)
        active = true
        // Windows on top (settings, export, synth editor...) keep their own keys.
        val covered = vm.settingsOpen || vm.exportOpen || vm.synthEditorChannelId != null || vm.openEffect != null || dialogOpen
        if (shortcutsOpen) {
            if (code == "Escape" || code == "F1" || (code == "Slash" && shift)) { shortcutsOpen = false; return true }
            return false
        }
        if (covered) return false

        // Typing keyboard first, so its letters and numbers play notes.
        if (typing != TypingKeys.OFF && !ctrl && !alt) {
            typingMap[code]?.let { semis ->
                if (!repeat) pressTyping(vm, code, typingBase + semis)
                return true
            }
            when (code) {
                "Minus" -> { shiftOctave(vm, -12); return true }
                "Equal" -> { shiftOctave(vm, 12); return true }
            }
        }

        if (ctrl) {
            when (code) {
                "KeyZ" -> { if (shift) redo(vm) else undo(vm); return true }
                "KeyY" -> { redo(vm); return true }
                "KeyS" -> { vm.saveRightAway(); return true }
                "KeyO" -> { projectsRequested = true; return true }
                "KeyK" -> { toggleTyping(vm); return true }
            }
            if (pianoVisible(vm)) when (code) {
                "KeyA" -> { vm.selectAllNotes(); return true }
                "KeyD" -> { vm.pianoSelection = emptySet(); return true }
                "KeyC" -> { vm.copySelectedNotes(); return true }
                "KeyX" -> { vm.copySelectedNotes(cut = true); return true }
                "KeyV" -> { vm.pasteSelectedNotes(); return true }
                "KeyB" -> { vm.duplicateSelectedNotes(); return true }
            }
            return false
        }
        if (alt) return false

        when (code) {
            "Space" -> { if (!repeat) vm.togglePlay(); return true }
            "F1" -> { shortcutsOpen = true; return true }
            "Slash" -> if (shift) { shortcutsOpen = true; return true }
            "F5" -> { switchTo(vm, Tab.PLAYLIST); return true }
            "F6" -> { switchTo(vm, Tab.RACK); return true }
            "F7" -> { switchTo(vm, Tab.PIANO); return true }
            "F9" -> { switchTo(vm, Tab.MIXER); return true }
            "Digit1" -> if (typing == TypingKeys.OFF) { switchTo(vm, Tab.PLAYLIST); return true }
            "Digit2" -> if (typing == TypingKeys.OFF) { switchTo(vm, Tab.RACK); return true }
            "Digit3" -> if (typing == TypingKeys.OFF) { switchTo(vm, Tab.PIANO); return true }
            "Digit4" -> if (typing == TypingKeys.OFF) { switchTo(vm, Tab.MIXER); return true }
            "KeyL" -> { vm.switchMode(if (vm.mode == PlayMode.PATTERN) PlayMode.SONG else PlayMode.PATTERN); return true }
        }
        if (pianoVisible(vm)) {
            val grid = vm.drawLength
            when (code) {
                "Delete", "Backspace" -> return vm.deleteSelectedNotes()
                "Escape" -> {
                    if (vm.pianoSelection.isEmpty()) return false
                    vm.pianoSelection = emptySet(); return true
                }
                "ArrowUp" -> return vm.nudgeSelectedNotes(0, if (shift) 12 else 1)
                "ArrowDown" -> return vm.nudgeSelectedNotes(0, if (shift) -12 else -1)
                "ArrowLeft" -> return vm.nudgeSelectedNotes(if (shift) -TICKS_PER_STEP * 16 else -grid, 0)
                "ArrowRight" -> return vm.nudgeSelectedNotes(if (shift) TICKS_PER_STEP * 16 else grid, 0)
            }
        }
        return false
    }

    private fun switchTo(vm: DawViewModel, tab: Tab) {
        if (vm.recordMode) vm.exitRecordMode()
        vm.tab = tab
    }

    private fun undo(vm: DawViewModel) {
        when {
            pianoVisible(vm) && vm.canUndo -> vm.undoNoteEdit()
            vm.undoSnapshot != null -> vm.undoDelete()
            vm.canUndo -> vm.undoNoteEdit()
        }
    }

    private fun redo(vm: DawViewModel) {
        if (vm.canRedo) vm.redoNoteEdit()
    }

    fun toggleTyping(vm: DawViewModel) {
        typing = if (typing == TypingKeys.OFF) TypingKeys.PLAY else TypingKeys.OFF
        vm.desktopNotice(if (typing == TypingKeys.OFF) "Typing keyboard off" else "Typing keyboard on: Z–M and Q–P play notes")
    }

    private fun shiftOctave(vm: DawViewModel, by: Int) {
        typingBase = (typingBase + by).coerceIn(24, 96)
        vm.desktopNotice("Typing keyboard from ${com.mobiledaw.model.noteName(typingBase)}")
    }

    private fun pressTyping(vm: DawViewModel, code: String, pitch: Int) {
        val ch = vm.selectedChannel ?: run { vm.desktopNotice("Add a sound first"); return }
        if (held.containsKey(code)) return
        val synth = ch.instrument == Instrument.WAVETABLE
        if (synth) vm.synthNote(ch.id, pitch, true) else vm.preview(ch, pitch)
        val recordTick = if (typing == TypingKeys.RECORD && vm.playing && vm.mode == PlayMode.PATTERN) {
            com.mobiledaw.audio.NativeEngine.positionTicks()
        } else null
        held[code] = Held(pitch, ch.id, recordTick, synth)
    }

    private fun releaseTyping(vm: DawViewModel, code: String): Boolean {
        val h = held.remove(code) ?: return false
        if (h.synth) vm.synthNote(h.channelId, h.pitch, false)
        val start = h.startTick ?: return true
        if (vm.selectedChannel?.id != h.channelId) return true
        val len = vm.selectedPattern.lengthTicks
        val grid = vm.drawLength
        val now = com.mobiledaw.audio.NativeEngine.positionTicks()
        var heldTicks = (now - start).toInt()
        if (heldTicks < 0) heldTicks += len
        val from = (((start % len).toInt() + grid / 2) / grid * grid) % len
        val length = ((heldTicks + grid / 2) / grid * grid).coerceIn(grid, len - from)
        vm.editNotes { it + Note(from, length, h.pitch) }
        return true
    }

    /** Lets go of every held typing-keyboard note (the page lost focus). */
    fun releaseAll(vm: DawViewModel) {
        held.keys.toList().forEach { releaseTyping(vm, it) }
    }
}
