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
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
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
import com.mobiledaw.ui.ChannelSettingsSheet
import com.mobiledaw.ui.DawChip
import com.mobiledaw.ui.KitPicker
import com.mobiledaw.ui.RoundToggle
import com.mobiledaw.ui.TextAction
import com.mobiledaw.ui.theme.DawColors

@Composable
fun ChannelRackScreen(vm: DawViewModel) {
    var adding by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var replaceId by remember { mutableStateOf<Long?>(null) }
    var settingsId by remember { mutableStateOf<Long?>(null) }
    val pattern = vm.selectedPattern
    val currentStep = if (vm.playing && vm.mode == PlayMode.PATTERN) (vm.positionTicks / TICKS_PER_STEP).toInt() else -1
    // The rack shows 1-4 bars at a time (pinch to zoom); arrows and swipes page through the rest.
    // `page` is the first bar shown, always a multiple of the number of bars shown.
    val shown = vm.rackBars
    var page by remember(pattern.id) { mutableIntStateOf(0) }
    page = (page.coerceIn(0, pattern.bars - 1) / shown) * shown
    val playingBar = if (currentStep >= 0) currentStep / STEPS_PER_BAR else -1
    val swipePage: (Int) -> Unit = { delta -> page = (page + delta * shown).coerceIn(0, pattern.bars - 1) }

    Column(Modifier.fillMaxSize()) {
        PatternBar(vm)
        BarPager(
            page = page,
            shown = shown,
            bars = pattern.bars,
            playingBar = playingBar,
            onPage = { page = (it.coerceIn(0, pattern.bars - 1) / shown) * shown },
            modifier = Modifier.barSwipe(swipePage),
            onAddBar = {
                vm.setPatternBars(pattern.bars + 1)
                page = pattern.bars
            },
        )
        LazyColumn(
            Modifier.weight(1f).pinchBars(shown, vm::changeRackBars),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(vm.project.channels, key = { it.id }) { channel ->
                val index = vm.project.channels.indexOf(channel)
                SwipeToDelete(onDelete = { vm.deleteChannel(channel.id) }) {
                ChannelRow(
                    vm = vm,
                    channel = channel,
                    level = vm.levels.getOrElse(index) { 0f },
                    selected = channel.id == vm.selectedChannelId,
                    activeSteps = pattern.notesFor(channel.id).map { it.start / TICKS_PER_STEP }.toSet(),
                    page = page,
                    shown = shown,
                    bars = pattern.bars,
                    currentStep = currentStep,
                    onChangeSound = { replaceId = channel.id; browsing = true },
                    onSettings = { settingsId = channel.id },
                    onSwipePage = swipePage,
                )
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    TextAction("+ Add channel") { adding = true }
                    TextAction("+ Add sound", strong = true) { replaceId = null; browsing = true }
                }
            }
        }
    }
    vm.undoSnapshot?.let {
        UndoBar(vm.undoLabel, onUndo = vm::undoDelete, onDismiss = vm::dismissUndo)
    }
    settingsId?.let { id ->
        ChannelSettingsSheet(
            vm = vm,
            channelId = id,
            onChangeSound = { replaceId = id; browsing = true },
            onDismiss = { settingsId = null },
        )
    }
    if (adding) AddChannelDialog(onPick = vm::addChannel, onDismiss = { adding = false })
    if (browsing) {
        KitPicker(
            vm = vm,
            replaceChannelId = replaceId,
            onOpenSettings = { browsing = false; vm.settingsOpen = true },
            onDismiss = { browsing = false },
        )
    }
}

/**
 * Horizontal swipe to change bar: left = next bar, right = previous bar.
 * Consumes the drag so a parent swipe-to-delete doesn't also move.
 */
@Composable
private fun Modifier.barSwipe(onPage: (Int) -> Unit): Modifier {
    val latest = androidx.compose.runtime.rememberUpdatedState(onPage)
    return pointerInput(Unit) {
    val threshold = 48.dp.toPx()
    var total = 0f
    detectHorizontalDragGestures(
        onDragStart = { total = 0f },
        onDragEnd = {
            if (total <= -threshold) latest.value(1) else if (total >= threshold) latest.value(-1)
        },
    ) { change, dx ->
        change.consume()
        total += dx
    }
    }
}

/**
 * Two-finger pinch: spread to show fewer bars (zoom in), squeeze to show more (zoom out), 1..4.
 * Watches in the Initial pass and consumes two-finger moves so the list doesn't scroll meanwhile.
 */
@Composable
private fun Modifier.pinchBars(shown: Int, onChange: (Int) -> Unit): Modifier {
    val latestShown = androidx.compose.runtime.rememberUpdatedState(shown)
    val latest = androidx.compose.runtime.rememberUpdatedState(onChange)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var baseline = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size < 2) { baseline = 0f; continue }
                val span = (pressed[0].position - pressed[1].position).getDistance()
                if (baseline == 0f) baseline = span
                val ratio = span / baseline
                val now = latestShown.value
                if (ratio < 0.7f && now < 4) { latest.value(now + 1); baseline = span }
                else if (ratio > 1.4f && now > 1) { latest.value(now - 1); baseline = span }
                event.changes.forEach { it.consume() }
            }
        }
    }
}

/** Swipe a channel's name row to the right to delete it (an Undo bar appears). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                onDelete()
                true
            } else {
                false
            }
        },
        positionalThreshold = { total -> total * 0.4f },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
                    .background(DawColors.Mute)
                    .padding(start = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("Delete", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        },
    ) { content() }
}

@Composable
internal fun UndoBar(label: String, onUndo: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(label) {
        kotlinx.coroutines.delay(6000)
        onDismiss()
    }
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(DawColors.Ink)
                .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = DawColors.Paper, fontSize = 14.sp, maxLines = 1)
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(DawColors.Paper).clickable(onClick = onUndo)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) { Text("Undo", color = DawColors.Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        }
    }
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
                PatternChip(vm, pat, DawColors.PatternColors[i % DawColors.PatternColors.size])
            }
            NewPatternChip(vm)
        }
        Spacer(Modifier.width(8.dp))
        BarsStepper(vm.selectedPattern.bars, vm::setPatternBars)
    }
}

/** Tap to select; tap the selected pattern again for its menu (save as template, delete). */
@Composable
private fun PatternChip(vm: DawViewModel, pat: com.mobiledaw.model.Pattern, color: Color) {
    var menu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val selected = pat.id == vm.selectedPatternId
    Box {
        DawChip(pat.name, selected = selected, dotColor = color) {
            if (selected) menu = true else vm.selectPattern(pat.id)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Save as template") }, onClick = { menu = false; saving = true })
            if (pat.bars * 2 <= MAX_BARS) {
                DropdownMenuItem(text = { Text("Loop to ${pat.bars * 2} bars") }, onClick = { menu = false; vm.loopPattern() })
            }
            if (vm.project.patterns.size > 1) {
                DropdownMenuItem(text = { Text("Delete pattern", color = DawColors.Mute) }, onClick = {
                    menu = false
                    vm.deletePattern(pat.id)
                })
            }
        }
    }
    if (saving) {
        var name by remember { mutableStateOf(pat.name) }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Save as template") },
            text = {
                Column {
                    Text("Saves this pattern and its sounds. Load it later from the + next to your patterns, in any project.", fontSize = 13.sp, color = DawColors.InkSoft)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
                }
            },
            confirmButton = { TextButton(onClick = { vm.saveTemplate(name); saving = false }) { Text("Save", color = DawColors.Ink) } },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel", color = DawColors.Muted) } },
            containerColor = DawColors.Card,
        )
    }
}

/** "+": a new empty pattern, or one built from a saved template. */
@Composable
private fun NewPatternChip(vm: DawViewModel) {
    var menu by remember { mutableStateOf(false) }
    Box {
        DawChip("+", selected = false, dotColor = null) { menu = true }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Empty pattern") }, onClick = { menu = false; vm.addPattern() })
            Text(
                "TEMPLATES",
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                color = DawColors.Muted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (vm.templates.isEmpty()) {
                Text(
                    "None yet. Tap a selected pattern\nand choose Save as template.",
                    fontSize = 12.sp,
                    color = DawColors.Muted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            vm.templates.forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.name) },
                    onClick = { menu = false; vm.applyTemplate(t) },
                    trailingIcon = {
                        Text(
                            "×",
                            fontSize = 18.sp,
                            color = DawColors.Muted,
                            modifier = Modifier.clip(CircleShape).clickable { vm.deleteTemplate(t.name) }.padding(horizontal = 8.dp),
                        )
                    },
                )
            }
        }
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
private fun BarPager(page: Int, shown: Int, bars: Int, playingBar: Int, onPage: (Int) -> Unit, modifier: Modifier = Modifier, onAddBar: () -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PagerArrow("‹", enabled = page > 0) { onPage(page - shown) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            val last = minOf(page + shown, bars)
            Text(if (last - page > 1) "Bars ${page + 1}–$last of $bars" else "Bar ${page + 1} of $bars", fontSize = 12.sp, color = DawColors.Muted, letterSpacing = 1.sp)
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
                                .size(if (b in page until page + shown) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        b in page until page + shown -> DawColors.Ink
                                        b == playingBar -> DawColors.Accent
                                        else -> DawColors.LineStrong
                                    },
                                ),
                        )
                    }
                }
            }
        }
        if (page + shown < bars) {
            PagerArrow("›", enabled = true) { onPage(page + shown) }
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
    shown: Int,
    bars: Int,
    currentStep: Int,
    onChangeSound: () -> Unit,
    onSettings: () -> Unit,
    onSwipePage: (Int) -> Unit,
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
                                // Tapping the name picks a sound for this channel (synths open their editor).
                                if (channel.instrument == com.mobiledaw.model.Instrument.WAVETABLE) vm.openSynth(channel.id) else onChangeSound()
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
                    // The sound actually playing, small and grey next to the channel's own name.
                    val soundName = channel.sample?.substringAfterLast('/')?.substringBeforeLast('.')
                        ?: channel.instrument.label.lowercase()
                    Text(
                        soundName,
                        fontSize = 11.sp,
                        color = DawColors.Muted,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Channel settings") }, onClick = {
                        menu = false
                        onSettings()
                    })
                    DropdownMenuItem(text = { Text("Open piano roll") }, onClick = {
                        menu = false
                        vm.tab = Tab.PIANO
                    })
                    DropdownMenuItem(text = { Text("Change sound") }, onClick = {
                        menu = false
                        onChangeSound()
                    })
                    for (every in listOf(2, 4, 8)) {
                        DropdownMenuItem(text = { Text("Fill every $every steps") }, onClick = {
                            menu = false
                            vm.fillSteps(channel, every)
                        })
                    }
                    DropdownMenuItem(text = { Text("Clear steps") }, onClick = {
                        menu = false
                        vm.clearSteps(channel)
                    })
                    val bars = vm.selectedPattern.bars
                    if (bars * 2 <= MAX_BARS) {
                        DropdownMenuItem(text = { Text("Loop pattern to ${bars * 2} bars") }, onClick = {
                            menu = false
                            vm.loopPattern()
                        })
                    }
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
            // Channel settings: volume/boost, pan, cut itself, trim, attack/release.
            Text(
                "⋯",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = DawColors.InkSoft,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onSettings)
                    .padding(horizontal = 10.dp),
            )
            RoundToggle("M", channel.mute, DawColors.Mute) {
                vm.updateChannel(channel.id) { it.copy(mute = !it.mute) }
            }
        }
        Spacer(Modifier.height(10.dp))
        // The 16 steps of the bar currently shown. Swiping across them pages bars
        // (the drag is consumed here, so it never reaches swipe-to-delete on the card).
        // With several bars shown the cells shrink to fit and bars get a small gap between them.
        val gap = if (shown == 1) 3.dp else if (shown == 2) 2.dp else 1.dp
        Row(Modifier.barSwipe(onSwipePage), horizontalArrangement = Arrangement.spacedBy(if (shown == 1) 3.dp else 6.dp)) {
            for (b in page until page + shown) {
                if (b >= bars) {
                    Spacer(Modifier.weight(1f))
                    continue
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (col in 0 until STEPS_PER_BAR) {
                        val step = b * STEPS_PER_BAR + col
                        StepCell(
                            on = step in activeSteps,
                            beatShade = (col / 4) % 2 == 1,
                            playing = step == currentStep,
                            compact = shown > 1,
                            modifier = Modifier.weight(1f),
                        ) { vm.toggleStep(channel, step) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepCell(on: Boolean, beatShade: Boolean, playing: Boolean, compact: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val base = if (beatShade) DawColors.Chip else DawColors.Paper
    val shape = RoundedCornerShape(if (compact) 2.dp else 6.dp)
    Box(
        modifier
            .then(if (compact) Modifier.height(34.dp) else Modifier.aspectRatio(0.62f))
            .clip(shape)
            .background(
                when {
                    on -> DawColors.Note
                    playing -> DawColors.AccentSoft
                    else -> base
                },
            )
            .border(1.dp, if (playing) DawColors.Accent else Color.Transparent, shape)
            .clickable(onClick = onClick),
    )
}

private fun lerpInk(level: Float): Color {
    val t = (level * 2.5f).coerceIn(0f, 1f)
    return androidx.compose.ui.graphics.lerp(DawColors.Line, DawColors.Accent, t)
}
