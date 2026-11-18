package com.mobiledaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mobiledaw.DawViewModel
import com.mobiledaw.model.TICKS_PER_BAR
import com.mobiledaw.ui.theme.DawColors

/** Playlist rows offered when a file arrives through Share / Open with (same as the playlist). */

/**
 * Shown after an audio file was shared to the app: pick the playlist row it goes on.
 * It lands after whatever is already on that row, with its own new mixer track.
 */
@Composable
fun SharedAudioDialog(vm: DawViewModel) {
    val files = vm.sharedAudio
    if (files.isEmpty()) return
    val p = vm.project
    Dialog(onDismissRequest = vm::dismissShared) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(DawColors.Card)
                .padding(20.dp),
        ) {
            Text(
                if (files.size == 1) "Add to playlist" else "Add ${files.size} files to playlist",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = DawColors.Ink,
            )
            Spacer(Modifier.height(4.dp))
            files.take(3).forEach {
                Text(
                    "${it.name}  ·  %.1f s".format(it.seconds),
                    fontSize = 13.sp,
                    color = DawColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (files.size > 3) Text("and ${files.size - 3} more", fontSize = 13.sp, color = DawColors.Muted)
            Spacer(Modifier.height(14.dp))
            Text("PICK A ROW", fontSize = 11.sp, letterSpacing = 2.sp, color = DawColors.Muted)
            Spacer(Modifier.height(6.dp))
            for (lane in 0 until p.rowCount) {
                val used = p.clips.count { it.lane == lane } + p.audioClips.count { it.lane == lane }
                val endBar = maxOf(
                    p.clips.filter { it.lane == lane }.maxOfOrNull { it.bar + p.clipBars(it) } ?: 0,
                    p.audioClips.filter { it.lane == lane }.maxOfOrNull { (it.endTick(p.bpm) + TICKS_PER_BAR - 1) / TICKS_PER_BAR } ?: 0,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { vm.placeShared(lane) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Row ${lane + 1}", fontSize = 15.sp, color = DawColors.Ink, modifier = Modifier.width(72.dp))
                    Text(
                        if (used == 0) "empty, starts at bar 1" else "after bar $endBar",
                        fontSize = 13.sp,
                        color = DawColors.Muted,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextAction("Cancel", onClick = vm::dismissShared)
            }
        }
    }
}
