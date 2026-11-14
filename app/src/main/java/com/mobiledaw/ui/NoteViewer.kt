package com.mobiledaw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobiledaw.DawViewModel
import com.mobiledaw.model.FoundNote
import com.mobiledaw.model.NoteAnalysis
import com.mobiledaw.model.NoteSource
import com.mobiledaw.model.isBlackKey
import com.mobiledaw.model.noteName
import com.mobiledaw.ui.theme.DawColors
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** "See notes" row for a clip or sample window; opens the note viewer on top. */
@Composable
fun SeeNotesRow(vm: DawViewModel, source: NoteSource) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(DawColors.Card)
            .border(1.dp, DawColors.Line, RoundedCornerShape(12.dp))
            .clickable { open = true }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("See notes", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
            Text("Shows which notes this sound plays, on a piano roll.", fontSize = 12.sp, color = DawColors.Muted)
        }
        Text("›", fontSize = 20.sp, color = DawColors.Muted)
    }
    if (open) NoteViewer(vm, source) { open = false }
}

/**
 * Note viewer: the notes found in a sound drawn as blobs on a piano roll, with the pitch line
 * running through them (so slides and vibrato show). Tap a blob to see its note, how far off
 * it is and how long it lasts.
 */
@Composable
fun NoteViewer(vm: DawViewModel, source: NoteSource, onDismiss: () -> Unit) {
    var analysis by remember(source) { mutableStateOf<NoteAnalysis?>(null) }
    var failed by remember(source) { mutableStateOf(false) }
    var selected by remember(source) { mutableStateOf<FoundNote?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    LaunchedEffect(source) {
        val a = vm.analyseNotes(source)
        if (a == null) failed = true else analysis = a
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(DawColors.Paper)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Notes", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                    Text(source.title, fontSize = 12.sp, color = DawColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ZoomButton("−", zoom > 0.3f) { zoom = max(0.25f, zoom / 1.5f) }
                Spacer(Modifier.width(6.dp))
                ZoomButton("+", zoom < 12f) { zoom = min(16f, zoom * 1.5f) }
                Spacer(Modifier.width(12.dp))
                TextAction("Done", strong = true, onClick = onDismiss)
            }
            val a = analysis
            when {
                failed -> Message("Couldn't read this sound.")
                a == null -> Message("Finding the notes…")
                a.notes.isEmpty() -> Message("No clear notes found. Drums and noisy sounds have no pitch; the viewer works best on one voice or instrument at a time.")
                else -> {
                    Summary(a, selected)
                    NoteCanvas(a, zoom, selected, Modifier.weight(1f)) { selected = it }
                }
            }
        }
    }
}

@Composable
private fun ZoomButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .border(1.dp, DawColors.LineStrong, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 16.sp, color = if (enabled) DawColors.Ink else DawColors.Muted) }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 14.sp, color = DawColors.Muted)
    }
}

@Composable
private fun Summary(a: NoteAnalysis, selected: FoundNote?) {
    val low = a.notes.minOf { it.pitch }
    val high = a.notes.maxOf { it.pitch }
    val key = remember(a) { a.key() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
        Text(
            buildString {
                append("${a.notes.size} notes · ${noteName(low)} to ${noteName(high)}")
                if (key != null) append(" · fits ${key.label}")
            },
            fontSize = 13.sp,
            color = DawColors.Ink,
        )
        Text(
            if (selected != null) {
                val c = selected.cents
                val tune = when {
                    abs(c) <= 5 -> "in tune"
                    c > 0 -> "$c cents sharp"
                    else -> "${-c} cents flat"
                }
                "${selected.name} · $tune · %.2f s long, at %.2f s".format(selected.seconds, selected.start)
            } else {
                "Tap a note to see it. The line shows the exact pitch, with slides and vibrato."
            },
            fontSize = 12.sp,
            color = DawColors.Muted,
        )
    }
}

@Composable
private fun NoteCanvas(a: NoteAnalysis, zoom: Float, selected: FoundNote?, modifier: Modifier, onSelect: (FoundNote?) -> Unit) {
    val low = a.notes.minOf { it.pitch } - 3
    val high = a.notes.maxOf { it.pitch } + 3
    val rows = high - low + 1
    val rowH = 20.dp
    val keysW = 40.dp
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val viewW = maxWidth - keysW
        // Whole sound fits the screen at zoom 1.
        val contentW = max(viewW.value, viewW.value * zoom).dp
        val contentH = rowH * rows
        val vScroll = rememberScrollState()
        val hScroll = rememberScrollState()
        Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
            // Keyboard column.
            Canvas(Modifier.width(keysW).height(contentH)) {
                val h = rowH.toPx()
                for (r in 0 until rows) {
                    val pitch = high - r
                    val y = r * h
                    drawRect(if (isBlackKey(pitch)) DawColors.Note.copy(alpha = 0.75f) else DawColors.Card, Offset(0f, y), Size(size.width, h))
                    drawLine(DawColors.Line, Offset(0f, y + h), Offset(size.width, y + h), 1f)
                    if (pitch.mod(12) == 0 || h >= 18.dp.toPx()) {
                        val label = measurer.measure(
                            noteName(pitch),
                            TextStyle(fontSize = 9.sp, color = if (isBlackKey(pitch)) DawColors.OnNote else DawColors.Ink, fontWeight = if (pitch.mod(12) == 0) FontWeight.SemiBold else FontWeight.Normal),
                        )
                        drawText(label, topLeft = Offset(4.dp.toPx(), y + (h - label.size.height) / 2f))
                    }
                }
            }
            Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
                Canvas(
                    Modifier
                        .width(contentW)
                        .height(contentH)
                        .pointerInput(a, zoom) {
                            detectTapGestures { pos ->
                                val secPerPx = a.seconds / size.width
                                val t = pos.x * secPerPx
                                val pitch = high - floor(pos.y / rowH.toPx()).toInt()
                                val slop = 8.dp.toPx() * secPerPx
                                onSelect(a.notes.firstOrNull { it.pitch == pitch && t >= it.start - slop && t <= it.end + slop })
                            }
                        },
                ) {
                    val h = rowH.toPx()
                    val pxPerSec = size.width / a.seconds.coerceAtLeast(0.01f)
                    // Lanes.
                    for (r in 0 until rows) {
                        val pitch = high - r
                        if (isBlackKey(pitch)) drawRect(DawColors.Chip.copy(alpha = 0.6f), Offset(0f, r * h), Size(size.width, h))
                        drawLine(DawColors.Line, Offset(0f, (r + 1) * h), Offset(size.width, (r + 1) * h), 1f)
                    }
                    // Second marks.
                    val step = when {
                        pxPerSec > 160f -> 0.25f
                        pxPerSec > 60f -> 1f
                        pxPerSec > 15f -> 5f
                        else -> 15f
                    }
                    var s = step
                    while (s < a.seconds) {
                        drawLine(DawColors.LineStrong.copy(alpha = 0.6f), Offset(s * pxPerSec, 0f), Offset(s * pxPerSec, size.height), 1f)
                        s += step
                    }
                    // Blobs.
                    val minLabelW = with(density) { 26.dp.toPx() }
                    for (n in a.notes) {
                        if (n.pitch < low || n.pitch > high) continue
                        val x = n.start * pxPerSec
                        val w = max(3f, (n.end - n.start) * pxPerSec)
                        val y = (high - n.pitch) * h
                        val on = n == selected
                        val color = if (on) DawColors.Accent else DawColors.Note.copy(alpha = 0.35f + 0.55f * n.level)
                        drawRoundRect(color, Offset(x, y + 1.5f), Size(w, h - 3f), CornerRadius(h / 2f))
                        if (w >= minLabelW) {
                            val label = measurer.measure(n.name, TextStyle(fontSize = 9.sp, color = DawColors.OnNote, fontWeight = FontWeight.SemiBold))
                            if (label.size.width + 8.dp.toPx() <= w) drawText(label, topLeft = Offset(x + 6.dp.toPx(), y + (h - label.size.height) / 2f))
                        }
                    }
                    // Pitch line (exact pitch, so slides and vibrato show).
                    val path = Path()
                    var drawing = false
                    for (i in a.pitch.indices) {
                        val p = a.pitch[i]
                        if (p <= 0f || p < low - 0.5f || p > high + 0.5f) {
                            drawing = false
                            continue
                        }
                        val x = (i + 0.5f) * a.hopSeconds * pxPerSec
                        val y = (high - p + 0.5f) * h
                        if (drawing) path.lineTo(x, y) else path.moveTo(x, y)
                        drawing = true
                    }
                    drawPath(path, DawColors.Accent.copy(alpha = 0.85f), style = Stroke(width = 1.5.dp.toPx()))
                }
            }
        }
        // Start near the notes vertically.
        LaunchedEffect(a) {
            val top = a.notes.maxOf { it.pitch }
            val px = with(density) { (rowH * (high - top - 1).coerceAtLeast(0)).toPx() }
            vScroll.scrollTo(px.roundToInt())
        }
    }
}
