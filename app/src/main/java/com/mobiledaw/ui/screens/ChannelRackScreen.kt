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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.mobiledaw.model.MAX_BARS
import com.mobiledaw.model.STEPS_PER_BAR
import com.mobiledaw.model.TICKS_PER_STEP
import com.mobiledaw.ui.AddChannelDialog
import com.mobiledaw.ui.DawChip
import com.mobiledaw.ui.KitBrowser
import com.mobiledaw.ui.RoundToggle
import com.mobiledaw.ui.TextAction
import com.mobiledaw.ui.theme.DawColors

@Composable
fun ChannelRackScreen(vm: DawViewModel) {
    var adding by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var replaceId by remember { mutableStateOf<Long?>(null) }
    val pattern = vm.selectedPattern
    val currentStep = if (vm.playing && vm.mode == PlayMode.PATTERN) (vm.positionTicks / TICKS_PER_STEP).toInt() else -1
    // The rack shows one bar (16 steps) at a time; arrows page through longer patterns.
    var page by remember(pattern.id) { mutableIntStateOf(0) }
    if (page >= pattern.bars) page = pattern.bars - 1
    val playingBar = if (currentStep >= 0) currentStep / STEPS_PER_BAR else -1

    Column(Modifier.fillMaxSize()) {
        PatternBar(vm)
        BarPager(
            page = page,
            bars = pattern.bars,
            playingBar = playingBar,
            onPage = { page = it },
            onAddBar = {
                vm.setPatternBars(pattern.bars + 1)
                page = pattern.bars
            },
        )
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
                    page = page,
                    currentStep = currentStep,
                    onChangeSound = { replaceId = channel.id; browsing = true },
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    TextAction("+ Add channel") { adding = true }
                    TextAction("Sounds", strong = true) { replaceId = null; browsing = true }
                }
            }
        }
    }
    if (adding) AddChannelDialog(onPick = vm::addChannel, onDismiss = { adding = false })
    if (browsing) KitBrowser(vm, replaceId) { browsing = false }
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
        BarsStepper(vm.selectedPattern.bars, vm::setPatternBars)
    }
}

/** "− 4 bars +": sets how long the selected pattern is. */
@Composable
private fun BarsStepper(bars: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(DawColors.Chip).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperButton("−", enabled = bars > 1) { onChange(bars - 1) }
        Text(
            if (bars == 1) "1 bar" else "$bars bars",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = DawColors.Ink,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        StepperButton("+", enabled = bars < MAX_BARS) { onChange(bars + 1) }
    }
}

@Composable
private fun StepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (enabled) DawColors.Card else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 16.sp, color = if (enabled) DawColors.Ink else DawColors.LineStrong)
    }
}

/**
 * ‹ ● ○ ○ ○ ›  — one dot per bar. Arrows (or a dot) switch the bar shown in the rack; on the
 * last bar the right arrow becomes "+" and adds a new bar.
 */
@Composable
private fun BarPager(page: Int, bars: Int, playingBar: Int, onPage: (Int) -> Unit, onAddBar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PagerArrow("‹", enabled = page > 0) { onPage(page - 1) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Bar ${page + 1} of $bars", fontSize = 12.sp, color = DawColors.Muted, letterSpacing = 1.sp)
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (b in 0 until bars) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .clickable { onPage(b) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(if (b == page) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        b == page -> DawColors.Ink
                                        b == playingBar -> DawColors.Accent
                                        else -> DawColors.LineStrong
                                    },
                                ),
                        )
                    }
                }
            }
        }
        if (page < bars - 1) {
            PagerArrow("›", enabled = true) { onPage(page + 1) }
        } else {
            PagerArrow("+", enabled = bars < MAX_BARS, onClick = onAddBar)
        }
    }
}

@Composable
private fun PagerArrow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (enabled) DawColors.Card else Color.Transparent)
            .border(1.dp, if (enabled) DawColors.Line else Color.Transparent, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 22.sp, color = if (enabled) DawColors.Ink else DawColors.LineStrong)
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
    page: Int,
    currentStep: Int,
    onChangeSound: () -> Unit,
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
                            onClick = {
                                vm.selectChannel(channel.id)
                                // Drum channels: tapping the name picks a sound from your kits.
                                if (channel.instrument.isDrum) onChangeSound() else vm.preview(channel)
                            },
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
                    Text(channel.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
                    Spacer(Modifier.width(6.dp))
                    if (channel.instrument != com.mobiledaw.model.Instrument.SAMPLE) {
                        Text(channel.instrument.label.lowercase(), fontSize = 12.sp, color = DawColors.Muted)
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open piano roll") }, onClick = {
                        menu = false
                        vm.tab = Tab.PIANO
                    })
                    DropdownMenuItem(text = { Text("Change sound") }, onClick = {
                        menu = false
                        onChangeSound()
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
        // The 16 steps of the bar currently shown.
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (col in 0 until STEPS_PER_BAR) {
                val step = page * STEPS_PER_BAR + col
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
