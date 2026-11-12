package com.mobiledaw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobiledaw.DawViewModel
import com.mobiledaw.model.Channel
import com.mobiledaw.model.Instrument
import com.mobiledaw.ui.theme.DawColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A small take on a sampler's channel settings: volume with boost, pan, cut itself, trim and a
 * fade-in / fade-out envelope. Changes apply live.
 */
@Composable
fun ChannelSettingsSheet(vm: DawViewModel, channelId: Long, onChangeSound: () -> Unit, onDismiss: () -> Unit) {
    val channel = vm.project.channels.firstOrNull { it.id == channelId }
    if (channel == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onDismiss() }
        return
    }
    fun set(transform: (Channel) -> Channel) = vm.updateChannel(channelId, transform)
    val isSample = channel.instrument == Instrument.SAMPLE && channel.sample != null

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(DawColors.Paper)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Channel", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink, modifier = Modifier.weight(1f))
                TextAction("Hear it") { vm.preview(channel) }
                Spacer(Modifier.width(8.dp))
                TextAction("Done", strong = true, onClick = onDismiss)
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Card {
                    var name by remember(channelId) { mutableStateOf(channel.name) }
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            if (it.isNotBlank()) set { c -> c.copy(name = it.trim()) }
                        },
                        singleLine = true,
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Sound", fontSize = 12.sp, color = DawColors.Muted)
                            Text(
                                channel.sample?.substringAfterLast('/')?.substringBeforeLast('.') ?: channel.instrument.label,
                                fontSize = 15.sp,
                                color = DawColors.Ink,
                                maxLines = 1,
                            )
                        }
                        TextAction("Change", onClick = onChangeSound)
                    }
                }

                Spacer(Modifier.height(10.dp))
                Card {
                    LabeledSlider(
                        label = "Volume",
                        value = channel.volume,
                        range = 0f..2f,
                        display = "${(channel.volume * 100).roundToInt()}%" + if (channel.volume > 1.001f) " boost" else "",
                        snapTo = 1f,
                    ) { v -> set { it.copy(volume = v) } }
                    LabeledSlider(
                        label = "Pan",
                        value = channel.pan,
                        range = -1f..1f,
                        display = when {
                            abs(channel.pan) < 0.01f -> "Centre"
                            channel.pan < 0 -> "${(-channel.pan * 100).roundToInt()}% L"
                            else -> "${(channel.pan * 100).roundToInt()}% R"
                        },
                        snapTo = 0f,
                    ) { v -> set { it.copy(pan = v) } }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Cut itself", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                            Text("Each new hit stops the one still ringing (great for 808s and open hats).", fontSize = 12.sp, color = DawColors.Muted)
                        }
                        Switch(
                            checked = channel.cutItself,
                            onCheckedChange = { on -> set { it.copy(cutItself = on) } },
                            colors = SwitchDefaults.colors(checkedTrackColor = DawColors.Note, checkedThumbColor = DawColors.OnNote),
                        )
                    }
                }

                if (isSample) {
                    val path = channel.sample!!
                    val peaks = remember(path) { vm.samplePeaks(path, 160) }
                    val seconds = remember(path) { vm.sampleSeconds(path) }
                    Spacer(Modifier.height(10.dp))
                    Card {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Trim", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink, modifier = Modifier.weight(1f))
                            Text(
                                "%.2fs – %.2fs".format(channel.trimStart * seconds, channel.trimEnd * seconds),
                                fontSize = 12.sp,
                                color = DawColors.Muted,
                            )
                        }
                        Text("Drag the handles to cut the start or end of the sound.", fontSize = 12.sp, color = DawColors.Muted)
                        Spacer(Modifier.height(8.dp))
                        TrimWaveform(
                            peaks = peaks,
                            start = channel.trimStart,
                            end = channel.trimEnd,
                            attack = if (seconds > 0) channel.attack / seconds else 0f,
                            release = if (seconds > 0) channel.release / seconds else 0f,
                            onChange = { a, b -> set { it.copy(trimStart = a, trimEnd = b) } },
                            onRelease = { vm.preview(channel) },
                        )
                        Spacer(Modifier.height(4.dp))
                        Row {
                            Text("Reset trim", fontSize = 12.sp, color = DawColors.Accent, modifier = Modifier.pointerInput(Unit) {
                                detectTapGestures { set { it.copy(trimStart = 0f, trimEnd = 1f) } }
                            })
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Card {
                        LabeledSlider(
                            label = "Pitch",
                            value = channel.pitchSemis.toFloat(),
                            range = -12f..12f,
                            display = when {
                                channel.pitchSemis == 0 -> "No change"
                                channel.pitchSemis > 0 -> "+${channel.pitchSemis} st"
                                else -> "${channel.pitchSemis} st"
                            },
                            snapTo = 0f,
                        ) { v -> set { it.copy(pitchSemis = v.roundToInt().coerceIn(-12, 12)) } }
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Reverse", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                                Text("Play the sound backwards.", fontSize = 12.sp, color = DawColors.Muted)
                            }
                            Switch(
                                checked = channel.reverse,
                                onCheckedChange = { on -> set { it.copy(reverse = on) }; vm.preview(channel) },
                                colors = SwitchDefaults.colors(checkedTrackColor = DawColors.Note, checkedThumbColor = DawColors.OnNote),
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Card {
                        Text("Envelope", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                        Spacer(Modifier.height(4.dp))
                        LabeledSlider(
                            label = "Attack (fade in)",
                            value = channel.attack,
                            range = 0f..0.5f,
                            display = "${(channel.attack * 1000).roundToInt()} ms",
                            snapTo = 0f,
                        ) { v -> set { it.copy(attack = v) } }
                        LabeledSlider(
                            label = "Release (fade out)",
                            value = channel.release,
                            range = 0f..2f,
                            display = "${(channel.release * 1000).roundToInt()} ms",
                            snapTo = 0f,
                        ) { v -> set { it.copy(release = v) } }
                    }
                } else {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Trim and envelope appear when this channel plays a sound from your drum kits.",
                        fontSize = 12.sp,
                        color = DawColors.Muted,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(DawColors.Card)
            .border(1.dp, DawColors.Line, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) { content() }
}

/** Slider with a label and value; it sticks to [snapTo] (e.g. 100% or centre) when close. */
@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    snapTo: Float,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = DawColors.Ink, modifier = Modifier.weight(1f))
        Text(display, fontSize = 12.sp, color = DawColors.Muted)
    }
    val span = range.endInclusive - range.start
    Slider(
        value = value,
        onValueChange = { v -> onChange(if (abs(v - snapTo) < span * 0.025f) snapTo else v) },
        valueRange = range,
        colors = inkSliderColors(),
    )
}

/** Waveform with draggable start/end handles; the faded regions show attack and release. */
@Composable
private fun TrimWaveform(
    peaks: FloatArray,
    start: Float,
    end: Float,
    attack: Float,
    release: Float,
    onChange: (Float, Float) -> Unit,
    onRelease: () -> Unit,
) {
    val startState = rememberUpdatedState(start)
    val endState = rememberUpdatedState(end)
    var dragging by remember { mutableStateOf(0) } // 0 none, 1 start, 2 end
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(DawColors.Paper)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val f = pos.x / size.width
                        dragging = if (abs(f - startState.value) <= abs(f - endState.value)) 1 else 2
                    },
                    onDragEnd = { dragging = 0; onRelease() },
                    onDragCancel = { dragging = 0 },
                ) { change, _ ->
                    change.consume()
                    val f = (change.position.x / size.width).coerceIn(0f, 1f)
                    if (dragging == 1) onChange(f.coerceAtMost(endState.value - 0.01f), endState.value)
                    else if (dragging == 2) onChange(startState.value, f.coerceAtLeast(startState.value + 0.01f))
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        val n = peaks.size
        if (n > 0) {
            val step = w / n
            for (i in 0 until n) {
                val f = (i + 0.5f) / n
                val inside = f in start..end
                var gain = 1f
                if (inside && attack > 0f) gain = minOf(gain, (f - start) / attack)
                if (inside && release > 0f) gain = minOf(gain, (end - f) / release)
                val amp = (peaks[i] * 0.9f + 0.02f) * mid
                val color = if (inside) DawColors.Note.copy(alpha = 0.35f + 0.65f * gain.coerceIn(0f, 1f)) else DawColors.LineStrong
                drawLine(color, Offset(i * step + step / 2, mid - amp), Offset(i * step + step / 2, mid + amp), (step * 0.6f).coerceAtLeast(1f))
            }
        }
        // Dim the trimmed-away parts.
        drawRect(DawColors.Paper.copy(alpha = 0.55f), Offset.Zero, Size(start * w, h))
        drawRect(DawColors.Paper.copy(alpha = 0.55f), Offset(end * w, 0f), Size(w - end * w, h))
        // Handles
        val handleW = 3.dp.toPx()
        for (x in listOf(start * w, end * w)) {
            drawRect(DawColors.Accent, Offset(x - handleW / 2, 0f), Size(handleW, h))
            drawRoundRect(DawColors.Accent, Offset(x - 8.dp.toPx(), h / 2 - 14.dp.toPx()), Size(16.dp.toPx(), 28.dp.toPx()), CornerRadius(8.dp.toPx()))
        }
    }
}
