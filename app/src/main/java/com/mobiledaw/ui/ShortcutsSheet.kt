package com.mobiledaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobiledaw.DawViewModel
import com.mobiledaw.ui.theme.DawColors

private val SECTIONS: List<Pair<String, List<Pair<String, String>>>> = listOf(
    "Anywhere" to listOf(
        "Space" to "Play / stop",
        "L" to "Switch between pattern and song",
        "F5 / F6 / F7 / F9" to "Playlist, Rack, Piano roll, Mixer",
        "1 2 3 4" to "Same screens, in tab order",
        "Ctrl+Z" to "Undo",
        "Ctrl+Y or Ctrl+Shift+Z" to "Redo",
        "Ctrl+S" to "Save now",
        "Ctrl+O" to "Projects",
        "Ctrl+K" to "Typing keyboard on / off",
        "F1 or ?" to "This list",
    ),
    "Piano roll: mouse" to listOf(
        "Click" to "Place a note (keep holding to drag it)",
        "Drag a note" to "Move it; drag its right end to resize",
        "Right click" to "Delete a note; right-drag deletes many",
        "Ctrl+drag or Shift+drag" to "Select notes with a box",
        "Ctrl+click" to "Add or remove one note from the selection",
        "Wheel / two fingers" to "Scroll (Shift+wheel: sideways)",
        "Ctrl+wheel or pinch" to "Zoom in and out",
        "Middle-drag" to "Move the view",
    ),
    "Piano roll: keys" to listOf(
        "Ctrl+A / Ctrl+D" to "Select all / none",
        "Ctrl+C / Ctrl+X / Ctrl+V" to "Copy / cut / paste",
        "Ctrl+B" to "Duplicate the selection after itself",
        "Delete" to "Delete the selected notes",
        "Arrow up / down" to "Move a semitone (Shift: an octave)",
        "Arrow left / right" to "Move one grid step (Shift: a bar)",
        "Esc" to "Clear the selection",
    ),
    "Playlist and rack" to listOf(
        "Right click a clip" to "Delete it; right-drag deletes many",
        "Ctrl+wheel or pinch" to "Zoom the playlist",
        "Shift+wheel" to "Scroll sideways",
        "Right click a step" to "Clear it",
    ),
    "Typing keyboard" to listOf(
        "Z S X D C V G B H N J M" to "Play notes from C (the lower row)",
        "Q 2 W 3 E R 5 T 6 Y 7 U" to "One octave higher",
        "- / =" to "Octave down / up",
    ),
)

/** The list of mouse and keyboard controls (F1 or ?), with the typing keyboard switch. */
@Composable
fun ShortcutsSheet(vm: DawViewModel, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 620.dp)
                .heightIn(max = 760.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(DawColors.Paper)
                .border(1.dp, DawColors.Line, RoundedCornerShape(18.dp)),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Keyboard shortcuts", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink, modifier = Modifier.weight(1f))
                TextAction("Done", strong = true, onClick = onDismiss)
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 18.dp)) {
                Text("Ctrl means Cmd on a Mac.", fontSize = 12.sp, color = DawColors.Muted)
                // Typing keyboard: off, play only, or also record into the pattern while it plays.
                Text("TYPING KEYBOARD", fontSize = 11.sp, letterSpacing = 2.sp, color = DawColors.Muted, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypingKeys.entries.forEach { mode ->
                        val on = Desktop.typing == mode
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (on) DawColors.Ink else DawColors.Card)
                                .border(1.dp, DawColors.Line, RoundedCornerShape(50))
                                .clickable { Desktop.typing = mode }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        ) {
                            Text(mode.label, fontSize = 13.sp, color = if (on) DawColors.Paper else DawColors.Ink)
                        }
                    }
                }
                Text(
                    "Plays the selected sound from your computer keyboard. With \"Play + record\", notes you play while the pattern plays are written into it.",
                    fontSize = 12.sp,
                    color = DawColors.Muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                SECTIONS.forEach { (title, rows) ->
                    Text(title.uppercase(), fontSize = 11.sp, letterSpacing = 2.sp, color = DawColors.Muted, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
                    rows.forEach { (keys, what) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                keys,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = DawColors.Ink,
                                modifier = Modifier
                                    .widthIn(min = 150.dp, max = 220.dp)
                                    .padding(end = 12.dp),
                            )
                            Text(what, fontSize = 13.sp, color = DawColors.InkSoft, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
