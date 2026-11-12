package com.mobiledaw.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.DawViewModel
import com.mobiledaw.PlayMode
import com.mobiledaw.Tab
import com.mobiledaw.model.Channel
import com.mobiledaw.model.STEPS_PER_BAR
import com.mobiledaw.model.TICKS_PER_STEP
import com.mobiledaw.ui.AddChannelDialog
import com.mobiledaw.ui.DawChip
import com.mobiledaw.ui.RoundToggle
import com.mobiledaw.ui.Segmented
import com.mobiledaw.ui.TextAction
import com.mobiledaw.ui.theme.DawColors

@Composable
fun ChannelRackScreen(vm: DawViewModel) {
    var adding by remember { mutableStateOf(false) }
    val pattern = vm.selectedPattern
    val totalSteps = pattern.bars * STEPS_PER_BAR
    val currentStep = if (vm.playing && vm.mode == PlayMode.PATTERN) (vm.positionTicks / TICKS_PER_STEP).toInt() else -1

    Column(Modifier.fillMaxSize()) {
        PatternBar(vm)
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(vm.project.channels, key = { it.id }) { channel ->
                val index = vm.project.channels.indexOf(channel)
                ChannelRow(
                    vm = vm,
                    channel = channel,
                    level = vm.levels.getOrElse(index) { 0f },
                    selected = channel.id == vm.selectedChannelId,
                    activeSteps = pattern.notesFor(channel.id).map { it.start / TICKS_PER_STEP }.toSet(),
                    totalSteps = totalSteps,
                    currentStep = currentStep,
                )
            }
            item {
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                    TextAction("+ Add channel") { adding = true }
                }
            }
        }
    }
    if (adding) AddChannelDialog(onPick = vm::addChannel, onDismiss = { adding = false })
}

/** Pattern chips plus the pattern length selector, shared by the rack and piano roll. */
@Composable
fun PatternBar(vm: DawViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            vm.project.patterns.forEachIndexed { i, pat ->
                DawChip(
                    pat.name,
                    selected = pat.id == vm.selectedPatternId,
                    dotColor = DawColors.PatternColors[i % DawColors.PatternColors.size],
                ) { vm.selectPattern(pat.id) }
            }
            DawChip("+", selected = false, dotColor = null, onClick = vm::addPattern)
        }
        Spacer(Modifier.width(8.dp))
        Segmented(
            options = listOf(1 to "1", 2 to "2", 4 to "4"),
            selected = vm.selectedPattern.bars,
            onSelect = vm::setPatternBars,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    vm: DawViewModel,
    channel: Channel,
    level: Float,
    selected: Boolean,
    activeSteps: Set<Int>,
    totalSteps: Int,
    currentStep: Int,
) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(DawColors.Card)
            .border(1.dp, if (selected) DawColors.LineStrong else DawColors.Line, RoundedCornerShape(18.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .combinedClickable(
                            onClick = { vm.selectChannel(channel.id); vm.preview(channel) },
                            onLongClick = { vm.selectChannel(channel.id); menu = true },
                        )
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Activity light: brightens with the channel's output level.
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(lerpInk(level)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(channel.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                    Spacer(Modifier.width(6.dp))
                    Text(channel.instrument.label.lowercase(), fontSize = 12.sp, color = DawColors.Muted)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open piano roll") }, onClick = {
                        menu = false
                        vm.tab = Tab.PIANO
                    })
                    DropdownMenuItem(text = { Text("Delete channel") }, onClick = {
                        menu = false
                        vm.deleteChannel(channel.id)
                    })
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "♪",
                fontSize = 16.sp,
                color = DawColors.Muted,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { vm.selectChannel(channel.id); vm.tab = Tab.PIANO }
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
            RoundToggle("M", channel.mute, DawColors.Mute) {
                vm.updateChannel(channel.id) { it.copy(mute = !it.mute) }
            }
        }
        Spacer(Modifier.height(10.dp))
        // Steps, 16 per row so each bar gets its own line.
        for (row in 0 until totalSteps / STEPS_PER_BAR) {
            if (row > 0) Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (col in 0 until STEPS_PER_BAR) {
                    val step = row * STEPS_PER_BAR + col
                    StepCell(
                        on = step in activeSteps,
                        beatShade = (col / 4) % 2 == 1,
                        playing = step == currentStep,
                        modifier = Modifier.weight(1f),
                    ) { vm.toggleStep(channel, step) }
                }
            }
        }
    }
}

@Composable
private fun StepCell(on: Boolean, beatShade: Boolean, playing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val base = if (beatShade) DawColors.Chip else DawColors.Paper
    Box(
        modifier
            .aspectRatio(0.62f)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    on -> DawColors.Ink
                    playing -> DawColors.AccentSoft
                    else -> base
                },
            )
            .border(1.dp, if (playing) DawColors.Accent else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
    )
}

private fun lerpInk(level: Float): Color {
    val t = (level * 2.5f).coerceIn(0f, 1f)
    return androidx.compose.ui.graphics.lerp(DawColors.Line, DawColors.Accent, t)
}
