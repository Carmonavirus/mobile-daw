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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A small take on a sampler's channel settings: volume, pan, cut itself, trim, pitch / reverse,
 * Boost / Normalize and an envelope. Changes apply live.
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
                                channel.sample?.substringAfterLast('/')?.substringBeforeLast('.') ?: vm.soundLabel(channel) ?: channel.instrument.label,
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
                    val rawPeak = remember(path) { vm.samplePeak(path) }
                    val boostGain = vm.sampleBoostGain(channel)
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
                            peaks = if (channel.reverse) peaks.reversedArray() else peaks,
                            level = if (rawPeak > 0f) rawPeak * boostGain else 1f,
                            boosted = boostGain > 1f,
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Boost", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink, modifier = Modifier.weight(1f))
                            val total = 20f * kotlin.math.log10(boostGain.coerceAtLeast(1e-4f))
                            Text(
                                if (abs(total) < 0.05f) "Off" else "%+.1f dB".format(total),
                                fontSize = 12.sp,
                                color = DawColors.Muted,
                            )
                        }
                        Text("Makes the sound itself louder and punchier. Peaks are rounded off softly instead of clipping.", fontSize = 12.sp, color = DawColors.Muted)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Knob(
                                "BOOST",
                                channel.boostDb,
                                0f..DawViewModel.BOOST_MAX_DB,
                                if (channel.boostDb < 0.05f) "0 dB" else "+%.1f dB".format(channel.boostDb),
                                { vm.preview(channel) },
                                Modifier.width(76.dp),
                            ) { v -> set { it.copy(boostDb = if (v < 0.25f) 0f else v) } }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Normalize", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                                        Text("Bring the loudest peak up to full level first.", fontSize = 12.sp, color = DawColors.Muted)
                                    }
                                    Switch(
                                        checked = channel.normalize,
                                        onCheckedChange = { on ->
                                            set { it.copy(normalize = on) }
                                            vm.project.channels.firstOrNull { it.id == channelId }?.let { vm.preview(it) }
                                        },
                                        colors = SwitchDefaults.colors(checkedTrackColor = DawColors.Note, checkedThumbColor = DawColors.OnNote),
                                    )
                                }
                                if (channel.boostDb > 0f || channel.normalize) {
                                    Spacer(Modifier.height(6.dp))
                                    Text("Reset boost", fontSize = 12.sp, color = DawColors.Accent, modifier = Modifier.pointerInput(Unit) {
                                        detectTapGestures { set { it.copy(boostDb = 0f, normalize = false) } }
                                    })
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Card {
                        Text("Envelope", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                        Text("Drag the dots, or use the knobs below.", fontSize = 12.sp, color = DawColors.Muted)
                        Spacer(Modifier.height(8.dp))
                        EnvelopeEditor(
                            delay = channel.envDelay,
                            attack = channel.attack,
                            hold = channel.hold,
                            decay = channel.decay,
                            sustain = channel.sustain,
                            release = channel.release,
                            tension = channel.tension,
                            onChange = { a, h, d, s, r -> set { it.copy(attack = a, hold = h, decay = d, sustain = s, release = r) } },
                            onRelease = { vm.preview(channel) },
                        )
                        Spacer(Modifier.height(10.dp))
                        val onLift = { vm.preview(channel) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Knob("DELAY", channel.envDelay, 0f..0.5f, "${(channel.envDelay * 1000).roundToInt()}", onLift, Modifier.weight(1f)) { v -> set { it.copy(envDelay = v) } }
                            Knob("ATT", channel.attack, 0f..0.5f, "${(channel.attack * 1000).roundToInt()}", onLift, Modifier.weight(1f)) { v -> set { it.copy(attack = v) } }
                            Knob("HOLD", channel.hold, 0f..0.5f, "${(channel.hold * 1000).roundToInt()}", onLift, Modifier.weight(1f)) { v -> set { it.copy(hold = v) } }
                            Knob("DEC", channel.decay, 0f..1f, "${(channel.decay * 1000).roundToInt()}", onLift, Modifier.weight(1f)) { v -> set { it.copy(decay = v) } }
                            Knob("SUS", channel.sustain, 0f..1f, "${(channel.sustain * 100).roundToInt()}%", onLift, Modifier.weight(1f)) { v -> set { it.copy(sustain = v) } }
                            Knob("REL", channel.release, 0f..2f, "${(channel.release * 1000).roundToInt()}", onLift, Modifier.weight(1f)) { v -> set { it.copy(release = v) } }
                            Knob("TENS", channel.tension, -1f..1f, "${(channel.tension * 100).roundToInt()}", onLift, Modifier.weight(1f)) { v -> set { it.copy(tension = v) } }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row {
                            Text("Reset envelope", fontSize = 12.sp, color = DawColors.Accent, modifier = Modifier.pointerInput(Unit) {
                                detectTapGestures { set { it.copy(envDelay = 0f, attack = 0f, hold = 0f, decay = 0f, sustain = 1f, release = 0f, tension = 0f) } }
                            })
                        }
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
internal fun LabeledSlider(
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

/** Bends a 0..1 ramp by [tension] in [-1, 1]; matches the native envWarp in DawCore.cpp. */
private fun envWarp(x: Float, tension: Float): Float {
    if (abs(tension) < 1e-4f) return x
    val k = tension * 5f
    return ((exp(k * x) - 1f) / (exp(k) - 1f))
}

/**
 * A graphical delay / attack / hold / decay / sustain / release editor, with tension curving the
 * attack, decay and release segments. Four dots are draggable: attack, hold and release move in
 * time; the decay dot also sets the sustain level by its height. Delay and tension are set by the
 * knobs below. The flat stretch before release is a fixed window so the sustain plateau shows.
 */
@Composable
private fun EnvelopeEditor(
    delay: Float,
    attack: Float,
    hold: Float,
    decay: Float,
    sustain: Float,
    release: Float,
    tension: Float,
    onChange: (Float, Float, Float, Float, Float) -> Unit,
    onRelease: () -> Unit,
) {
    val aMax = 0.5f; val hMax = 0.5f; val dMax = 1.0f; val rMax = 2.0f; val dlyMax = 0.5f
    val plateau = 0.5f // fixed on-screen sustain stretch
    val span = dlyMax + aMax + hMax + dMax + plateau + rMax

    val dlyState = rememberUpdatedState(delay)
    val aState = rememberUpdatedState(attack)
    val hState = rememberUpdatedState(hold)
    val dState = rememberUpdatedState(decay)
    val sState = rememberUpdatedState(sustain)
    val rState = rememberUpdatedState(release)
    var dragging by remember { mutableStateOf(0) } // 1 attack, 2 hold, 3 decay/sustain, 4 release

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(DawColors.Paper)
            .pointerInput(Unit) {
                val pad = 12.dp.toPx()
                fun xOf(sec: Float) = pad + (sec / span) * (size.width - 2 * pad)
                detectDragGestures(
                    onDragStart = { pos ->
                        val dly = dlyState.value; val a = aState.value; val h = hState.value; val d = dState.value
                        val xs = listOf(
                            1 to xOf(dly + a),
                            2 to xOf(dly + a + h),
                            3 to xOf(dly + a + h + d),
                            4 to xOf(dly + a + h + d + plateau + rState.value),
                        )
                        dragging = xs.minByOrNull { abs(it.second - pos.x) }?.first ?: 0
                    },
                    onDragEnd = { dragging = 0; onRelease() },
                    onDragCancel = { dragging = 0 },
                ) { change, _ ->
                    change.consume()
                    val pad2 = 12.dp.toPx()
                    val sec = ((change.position.x - pad2) / (size.width - 2 * pad2) * span).coerceIn(0f, span)
                    val dly = dlyState.value; val a = aState.value; val h = hState.value; val d = dState.value
                    when (dragging) {
                        1 -> onChange((sec - dly).coerceIn(0f, aMax), h, d, sState.value, rState.value)
                        2 -> onChange(a, (sec - dly - a).coerceIn(0f, hMax), d, sState.value, rState.value)
                        3 -> {
                            val lvl = (1f - (change.position.y - pad2) / (size.height - 2 * pad2)).coerceIn(0f, 1f)
                            onChange(a, h, (sec - dly - a - h).coerceIn(0f, dMax), lvl, rState.value)
                        }
                        4 -> onChange(a, h, d, sState.value, (sec - dly - a - h - d - plateau).coerceIn(0f, rMax))
                    }
                }
            },
    ) {
        val pad = 12.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        fun px(sec: Float) = pad + (sec / span) * w
        fun py(level: Float) = pad + (1f - level) * h

        // Build the outline, sampling the curved attack / decay / release segments for tension.
        val steps = 12
        val pts = ArrayList<Offset>()
        pts.add(Offset(px(0f), py(0f)))            // origin
        pts.add(Offset(px(delay), py(0f)))         // end of delay (still silent)
        for (i in 1..steps) { val u = i / steps.toFloat(); pts.add(Offset(px(delay + attack * u), py(envWarp(u, tension)))) }
        val dStart = delay + attack + hold
        pts.add(Offset(px(dStart), py(1f)))        // end of hold
        for (i in 1..steps) { val u = i / steps.toFloat(); pts.add(Offset(px(dStart + decay * u), py(1f - (1f - sustain) * envWarp(u, tension)))) }
        val pEnd = dStart + decay + plateau
        pts.add(Offset(px(pEnd), py(sustain)))     // end of sustain plateau
        for (i in 1..steps) { val u = i / steps.toFloat(); pts.add(Offset(px(pEnd + release * u), py(sustain * envWarp(1f - u, tension)))) }

        drawLine(DawColors.Line, Offset(pad, py(0f)), Offset(size.width - pad, py(0f)), 1.dp.toPx())

        val fill = Path().apply {
            moveTo(pts.first().x, pts.first().y)
            for (p in pts.drop(1)) lineTo(p.x, p.y)
            lineTo(pts.last().x, py(0f))
            close()
        }
        drawPath(fill, DawColors.Note.copy(alpha = 0.18f))

        val line = Path().apply {
            moveTo(pts.first().x, pts.first().y)
            for (p in pts.drop(1)) lineTo(p.x, p.y)
        }
        drawPath(line, DawColors.Note, style = Stroke(width = 2.dp.toPx()))

        // Draggable dots: attack peak, hold end, decay/sustain, release end.
        val dots = listOf(
            Offset(px(delay + attack), py(1f)),
            Offset(px(delay + attack + hold), py(1f)),
            Offset(px(dStart + decay), py(sustain)),
            Offset(px(pEnd + release), py(0f)),
        )
        for (p in dots) {
            drawCircle(DawColors.Paper, 7.dp.toPx(), p)
            drawCircle(DawColors.Accent, 7.dp.toPx(), p, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

/** A small rotary knob. Drag up/down to change the value; shows a label and a compact readout. */
@Composable
private fun Knob(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit,
) {
    val span = range.endInclusive - range.start
    val valState = rememberUpdatedState(value)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(38.dp)
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { onRelease() }) { change, drag ->
                        change.consume()
                        val next = (valState.value - drag.y / 150f * span).coerceIn(range.start, range.endInclusive)
                        if (next != valState.value) onChange(next)
                    }
                },
        ) {
            val f = ((value - range.start) / span).coerceIn(0f, 1f)
            val startAngle = 135f
            val sweep = 270f
            val stroke = 3.dp.toPx()
            val d = size.minDimension - stroke * 2
            val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
            drawArc(DawColors.Line, startAngle, sweep, false, topLeft, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(DawColors.Note, startAngle, sweep * f, false, topLeft, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
            val ang = Math.toRadians((startAngle + sweep * f).toDouble())
            val r = d / 2f
            val c = Offset(size.width / 2f + r * cos(ang).toFloat(), size.height / 2f + r * sin(ang).toFloat())
            drawCircle(DawColors.Accent, 3.dp.toPx(), c)
        }
        Text(label, fontSize = 8.sp, color = DawColors.Muted, maxLines = 1)
        Text(display, fontSize = 9.sp, color = DawColors.Ink, maxLines = 1)
    }
}

private const val BOOST_KNEE = 0.8f

/** Same curve as the engine's boost soft clipper (boostSoftClip in DawCore.cpp). */
private fun softClipShown(x: Float): Float =
    if (x <= BOOST_KNEE) x else BOOST_KNEE + (1f - BOOST_KNEE) * kotlin.math.tanh((x - BOOST_KNEE) / (1f - BOOST_KNEE))

/** Waveform with draggable start/end handles; the faded regions show attack and release. */
@Composable
private fun TrimWaveform(
    peaks: FloatArray,
    /** Loudest peak after Boost / Normalize (1 = full scale); bars above the soft-clip knee are tinted. */
    level: Float = 1f,
    boosted: Boolean = false,
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
                val raw = peaks[i] * level
                val shown = if (boosted) softClipShown(raw) else raw.coerceAtMost(1f)
                val amp = (shown * 0.9f + 0.02f) * mid
                val hot = boosted && raw > BOOST_KNEE
                val base = if (hot) DawColors.Accent else DawColors.Note
                val color = if (inside) base.copy(alpha = 0.35f + 0.65f * gain.coerceIn(0f, 1f)) else DawColors.LineStrong
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
