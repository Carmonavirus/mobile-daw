package com.mobiledaw.ui.synth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobiledaw.DawViewModel
import com.mobiledaw.audio.SynthEngine
import com.mobiledaw.model.SynthParams
import com.mobiledaw.model.SynthPatch
import com.mobiledaw.model.SynthPreset
import com.mobiledaw.model.SynthPresetLibrary
import com.mobiledaw.model.isBlackKey
import com.mobiledaw.model.noteName
import com.mobiledaw.ui.TextAction
import com.mobiledaw.ui.theme.DawColors
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private enum class Page(val label: String) { OSC("Oscillators"), FILTER("Filter"), MOD("Movement"), VOICE("Voice") }

/** Full-screen editor for a wavetable-synth channel, with presets and a keyboard. */
@Composable
fun SynthEditor(vm: DawViewModel, channelId: Long) {
    val channel = vm.project.channels.firstOrNull { it.id == channelId }
    if (channel == null) {
        LaunchedEffect(Unit) { vm.closeSynth() }
        return
    }
    val patch = channel.synth ?: SynthPatch()
    val set: (String, Float) -> Unit = { name, v -> vm.setSynthParam(channelId, name, v) }
    var page by remember { mutableStateOf(Page.OSC) }
    var browsing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val allPresets = vm.synthPresets.factory + vm.userSynthPresets

    Dialog(onDismissRequest = vm::closeSynth, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(DawColors.Paper)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(channel.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Wavetable synth", fontSize = 12.sp, color = DawColors.Muted)
                }
                TextAction("Done", strong = true, onClick = vm::closeSynth)
            }
            PresetBar(
                patch = patch,
                onPrev = { step(allPresets, patch, -1)?.let { vm.loadSynthPreset(channelId, it) } },
                onNext = { step(allPresets, patch, +1)?.let { vm.loadSynthPreset(channelId, it) } },
                onBrowse = { browsing = true },
                onSave = { saving = true },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Choices(Page.entries.map { it.label }, page.ordinal, { page = Page.entries[it] }, Modifier.fillMaxWidth())
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
            ) {
                when (page) {
                    Page.OSC -> OscPage(patch, set)
                    Page.FILTER -> FilterPage(patch, set)
                    Page.MOD -> MovementPage(patch, set)
                    Page.VOICE -> VoicePage(patch, set)
                }
                Spacer(Modifier.height(8.dp))
            }
            Keyboard(
                startOctave = if (patch.category == "808" || patch.category == "Bass") 2 else 4,
                onNote = { pitch, on -> vm.synthNote(channelId, pitch, on) },
            )
        }
    }

    if (browsing) {
        PresetBrowser(
            vm = vm,
            current = patch,
            onPick = { vm.loadSynthPreset(channelId, it) },
            onDismiss = { browsing = false },
        )
    }
    if (saving) SaveDialog(patch, onSave = { name, cat -> vm.saveSynthPreset(channelId, name, cat) }, onDismiss = { saving = false })
}

private fun step(list: List<SynthPreset>, patch: SynthPatch, by: Int): SynthPreset? {
    if (list.isEmpty()) return null
    val i = list.indexOfFirst { it.name == patch.preset && it.category == patch.category }
    val next = if (i < 0) 0 else (i + by).mod(list.size)
    return list[next]
}

@Composable
private fun PresetBar(patch: SynthPatch, onPrev: () -> Unit, onNext: () -> Unit, onBrowse: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton("‹", onPrev)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(DawColors.Card)
                .border(1.dp, DawColors.Line, RoundedCornerShape(16.dp))
                .clickable(onClick = onBrowse)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                patch.preset + if (patch.edited) " (edited)" else "",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = DawColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (patch.category.ifEmpty { "Preset" }) + "  ·  tap to browse",
                fontSize = 11.sp,
                color = DawColors.Muted,
            )
        }
        RoundButton("›", onNext)
        Spacer(Modifier.width(8.dp))
        TextAction("Save", onClick = onSave)
    }
}

@Composable
private fun RoundButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(DawColors.Card)
            .border(1.dp, DawColors.Line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 20.sp, color = DawColors.Ink)
    }
}

// ------------------------------------------------------------------------------- pages

@Composable
private fun OscPage(patch: SynthPatch, set: (String, Float) -> Unit) {
    for (osc in listOf("a", "b")) {
        val on = patch.value("${osc}_on") > 0.5f
        SynthCard(
            title = "Oscillator ${osc.uppercase()}",
            trailing = { Switch(if (on) "On" else "Off", on) { set("${osc}_on", if (it) 1f else 0f) } },
        ) {
            TablePicker(patch.value("${osc}_table").roundToInt()) { set("${osc}_table", it.toFloat()) }
            Spacer(Modifier.height(8.dp))
            WavetableView(
                table = patch.value("${osc}_table").roundToInt(),
                position = patch.value("${osc}_pos"),
                enabled = on,
                onPosition = { set("${osc}_pos", it) },
            )
            Spacer(Modifier.height(10.dp))
            KnobRow {
                Knob("${osc}_pos", "Position", patch.value("${osc}_pos"), { set("${osc}_pos", it) }, enabled = on)
                Knob("${osc}_level", "Level", patch.value("${osc}_level"), { set("${osc}_level", it) }, enabled = on)
                Knob("${osc}_fine", "Fine", patch.value("${osc}_fine"), { set("${osc}_fine", it) }, enabled = on)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                Stepper("${osc}_oct", "Octave", patch.value("${osc}_oct"), { set("${osc}_oct", it) })
                Stepper("${osc}_semi", "Semitones", patch.value("${osc}_semi"), { set("${osc}_semi", it) })
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                Stepper("${osc}_unison", "Unison", patch.value("${osc}_unison"), { set("${osc}_unison", it) })
                Knob("${osc}_detune", "Detune", patch.value("${osc}_detune"), { set("${osc}_detune", it) }, enabled = on)
                Knob("${osc}_spread", "Width", patch.value("${osc}_spread"), { set("${osc}_spread", it) }, enabled = on)
            }
        }
    }
    SynthCard("Sub and noise") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            Knob("sub_level", "Sub", patch.value("sub_level"), { set("sub_level", it) })
            Stepper("sub_oct", "Octaves down", patch.value("sub_oct"), { set("sub_oct", it) })
            Knob("noise_level", "Noise", patch.value("noise_level"), { set("noise_level", it) })
        }
    }
}

@Composable
private fun FilterPage(patch: SynthPatch, set: (String, Float) -> Unit) {
    val on = patch.value("flt_on") > 0.5f
    SynthCard("Filter", trailing = { Switch(if (on) "On" else "Off", on) { set("flt_on", if (it) 1f else 0f) } }) {
        Choices(
            listOf("Low 12", "Low 24", "High", "Band", "Notch"),
            patch.value("flt_type").roundToInt(),
            { set("flt_type", it.toFloat()) },
            Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        FilterGraph(
            type = patch.value("flt_type").roundToInt(),
            cutoff = patch.value("flt_cutoff"),
            resonance = patch.value("flt_res"),
            enabled = on,
            onCutoff = { set("flt_cutoff", it) },
        )
        Spacer(Modifier.height(10.dp))
        KnobRow {
            Knob("flt_cutoff", "Cutoff", patch.value("flt_cutoff"), { set("flt_cutoff", it) }, curve = Curve.LOG, enabled = on)
            Knob("flt_res", "Resonance", patch.value("flt_res"), { set("flt_res", it) }, enabled = on)
            Knob("flt_drive", "Drive", patch.value("flt_drive"), { set("flt_drive", it) }, enabled = on)
            Knob("flt_key", "Key follow", patch.value("flt_key"), { set("flt_key", it) }, enabled = on)
            Knob("flt_env", "Envelope", patch.value("flt_env"), { set("flt_env", it) }, enabled = on)
            Knob("lfo_cutoff", "LFO", patch.value("lfo_cutoff"), { set("lfo_cutoff", it) }, enabled = on)
        }
    }
}

@Composable
private fun MovementPage(patch: SynthPatch, set: (String, Float) -> Unit) {
    SynthCard("Volume envelope") {
        EnvelopeGraph(patch, "amp")
        Spacer(Modifier.height(8.dp))
        EnvelopeKnobs(patch, "amp", set)
    }
    SynthCard("Mod envelope") {
        EnvelopeGraph(patch, "mod")
        Spacer(Modifier.height(8.dp))
        EnvelopeKnobs(patch, "mod", set)
        Spacer(Modifier.height(10.dp))
        Text("Sends to", fontSize = 11.sp, color = DawColors.Muted)
        Spacer(Modifier.height(6.dp))
        KnobRow {
            Knob("flt_env", "Cutoff", patch.value("flt_env"), { set("flt_env", it) })
            Knob("mod_pitch", "Pitch", patch.value("mod_pitch"), { set("mod_pitch", it) })
            Knob("mod_pos", "Position", patch.value("mod_pos"), { set("mod_pos", it) })
        }
    }
    val sync = patch.value("lfo_sync") > 0.5f
    val retrig = patch.value("lfo_retrig") > 0.5f
    SynthCard("LFO", trailing = {
        Switch("Sync", sync) { set("lfo_sync", if (it) 1f else 0f) }
        Spacer(Modifier.width(6.dp))
        Switch("Restart", retrig) { set("lfo_retrig", if (it) 1f else 0f) }
    }) {
        Choices(
            listOf("Sine", "Tri", "Saw", "Square", "Random"),
            patch.value("lfo_shape").roundToInt(),
            { set("lfo_shape", it.toFloat()) },
            Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        if (sync) {
            val divs = listOf("1/1", "1/2", "1/4", "1/8", "1/16", "1/32", "1/4T", "1/8T", "1/16T", "2/1")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val current = patch.value("lfo_div").roundToInt()
                divs.forEachIndexed { i, label ->
                    com.mobiledaw.ui.DawChip(label, selected = i == current, dotColor = null) { set("lfo_div", i.toFloat()) }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        KnobRow {
            if (!sync) Knob("lfo_rate", "Rate", patch.value("lfo_rate"), { set("lfo_rate", it) }, curve = Curve.LOG)
            Knob("lfo_cutoff", "Cutoff", patch.value("lfo_cutoff"), { set("lfo_cutoff", it) })
            Knob("lfo_pos", "Position", patch.value("lfo_pos"), { set("lfo_pos", it) })
            Knob("lfo_pitch", "Vibrato", patch.value("lfo_pitch"), { set("lfo_pitch", it) }, curve = Curve.TIME)
            Knob("lfo_amp", "Tremolo", patch.value("lfo_amp"), { set("lfo_amp", it) })
        }
    }
}

@Composable
private fun EnvelopeKnobs(patch: SynthPatch, prefix: String, set: (String, Float) -> Unit) {
    KnobRow {
        Knob("${prefix}_a", "Attack", patch.value("${prefix}_a"), { set("${prefix}_a", it) }, curve = Curve.TIME)
        Knob("${prefix}_d", "Decay", patch.value("${prefix}_d"), { set("${prefix}_d", it) }, curve = Curve.TIME)
        Knob("${prefix}_s", "Sustain", patch.value("${prefix}_s"), { set("${prefix}_s", it) })
        Knob("${prefix}_r", "Release", patch.value("${prefix}_r"), { set("${prefix}_r", it) }, curve = Curve.TIME)
    }
}

@Composable
private fun VoicePage(patch: SynthPatch, set: (String, Float) -> Unit) {
    val mono = patch.value("mono") > 0.5f
    SynthCard("Playing", trailing = { Switch(if (mono) "Mono" else "Poly", mono) { set("mono", if (it) 1f else 0f) } }) {
        Text(
            if (mono) "One note at a time. Overlap notes in the piano roll to slide between them, like an 808."
            else "Up to 8 notes at once. Switch to Mono for slides.",
            fontSize = 12.sp,
            color = DawColors.Muted,
        )
        Spacer(Modifier.height(10.dp))
        KnobRow {
            Knob("glide", "Glide", patch.value("glide"), { set("glide", it) }, curve = Curve.TIME, enabled = mono)
            Knob("vel_amt", "Velocity", patch.value("vel_amt"), { set("vel_amt", it) })
        }
    }
    SynthCard("Output") {
        KnobRow {
            Knob("drive", "Distortion", patch.value("drive"), { set("drive", it) })
            Knob("volume", "Volume", patch.value("volume"), { set("volume", it) })
        }
    }
}

// ------------------------------------------------------------------------------- pictures

@Composable
private fun TablePicker(table: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(DawColors.Chip)
                .clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(SynthParams.tables.getOrElse(table) { "?" }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
            Spacer(Modifier.width(6.dp))
            Text("▾", fontSize = 12.sp, color = DawColors.Muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SynthParams.tables.forEachIndexed { i, name ->
                DropdownMenuItem(
                    text = { Text(name, fontWeight = if (i == table) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { open = false; onPick(i) },
                )
            }
        }
    }
}

/**
 * The wavetable as a stack of cycles, front to back, with the current position drawn on top.
 * Drag sideways across it to move the position.
 */
@Composable
private fun WavetableView(table: Int, position: Float, enabled: Boolean, onPosition: (Float) -> Unit) {
    val points = 96
    val layers = 9
    val stack = remember(table) {
        List(layers) { i -> FloatArray(points).also { SynthEngine.tableShape(table, i / (layers - 1f), it) } }
    }
    val current = remember(table, position) { FloatArray(points).also { SynthEngine.tableShape(table, position, it) } }
    val move by rememberUpdatedState(onPosition)
    val currentPos by rememberUpdatedState(position)
    var dragPos by remember { mutableStateOf(position) }
    val ink = DawColors.Ink
    val accent = if (enabled) DawColors.Accent else DawColors.LineStrong
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(DawColors.Paper)
            .pointerInput(table) {
                detectHorizontalDragGestures(onDragStart = { dragPos = currentPos }) { change, dx ->
                    change.consume()
                    dragPos = (dragPos + dx / size.width).coerceIn(0f, 1f)
                    move(dragPos)
                }
            },
    ) {
        val depthX = size.width * 0.16f
        val depthY = size.height * 0.30f
        val w = size.width - depthX - 16.dp.toPx()
        val h = (size.height - depthY) * 0.42f
        fun layer(shape: FloatArray, t: Float, color: Color, width: Float) {
            val ox = 8.dp.toPx() + depthX * (1f - t)
            val oy = depthY * t + h + 6.dp.toPx()
            val path = Path()
            shape.forEachIndexed { i, v ->
                val x = ox + w * i / (points - 1)
                val y = oy - v * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round))
        }
        // Back (last frame) to front (first frame).
        for (i in layers - 1 downTo 0) layer(stack[i], 1f - i / (layers - 1f), ink.copy(alpha = 0.12f), 1.dp.toPx())
        layer(current, 1f - position, accent, 2.5.dp.toPx())
    }
}

/** Approximate response of the filter, on a log-frequency axis. Drag sideways to set the cutoff. */
@Composable
private fun FilterGraph(type: Int, cutoff: Float, resonance: Float, enabled: Boolean, onCutoff: (Float) -> Unit) {
    val move by rememberUpdatedState(onCutoff)
    var dragX by remember { mutableStateOf(0f) }
    val accent = if (enabled) DawColors.Accent else DawColors.LineStrong
    val grid = DawColors.Line
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(DawColors.Paper)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragStart = { dragX = it.x }) { change, dx ->
                    change.consume()
                    dragX = (dragX + dx).coerceIn(0f, size.width.toFloat())
                    move(20f * 1000f.pow(dragX / size.width))
                }
            },
    ) {
        val k = 2f - 1.96f * resonance
        fun mag(f: Float): Float {
            val wr = f / cutoff
            fun stage(kk: Float): Float {
                val den = sqrt((1 - wr * wr) * (1 - wr * wr) + (kk * wr) * (kk * wr))
                return when (type) {
                    2 -> wr * wr / den
                    3 -> kk * wr / den
                    4 -> abs(1 - wr * wr) / den
                    else -> 1f / den
                }
            }
            return if (type == 1) stage(k) * stage(1.414f) else stage(k)
        }
        for (hz in listOf(100f, 1000f, 10000f)) {
            val x = size.width * log10(hz / 20f) / 3f
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        }
        val zeroDb = size.height * 0.4f
        drawLine(grid, Offset(0f, zeroDb), Offset(size.width, zeroDb), 1.dp.toPx())
        val path = Path()
        val fill = Path()
        val n = 120
        for (i in 0..n) {
            val x = size.width * i / n
            val f = 20f * 1000f.pow(i.toFloat() / n)
            val db = (20f * log10(mag(f).coerceAtLeast(1e-4f))).coerceIn(-48f, 24f)
            val y = zeroDb - db / 48f * (size.height - zeroDb) * 0.95f
            if (i == 0) { path.moveTo(x, y); fill.moveTo(x, size.height); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(size.width, size.height)
        fill.close()
        drawPath(fill, accent.copy(alpha = 0.12f))
        drawPath(path, accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun EnvelopeGraph(patch: SynthPatch, prefix: String) {
    val a = patch.value("${prefix}_a")
    val d = patch.value("${prefix}_d")
    val s = patch.value("${prefix}_s")
    val r = patch.value("${prefix}_r")
    val accent = DawColors.Accent
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(70.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(DawColors.Paper),
    ) {
        // Segment widths follow the cube root of time, like the knobs.
        val wa = cbrt(a / 10f) + 0.02f
        val wd = cbrt(d / 10f) + 0.02f
        val wsus = 0.35f
        val wr = cbrt(r / 10f) + 0.02f
        val total = wa + wd + wsus + wr
        val pad = 8.dp.toPx()
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        fun x(t: Float) = pad + w * t / total
        fun y(v: Float) = pad + h * (1f - v)
        val path = Path().apply {
            moveTo(x(0f), y(0f))
            lineTo(x(wa), y(1f))
            // Exponential-looking decay drawn as a curve.
            quadraticBezierTo(x(wa + wd * 0.15f), y(s), x(wa + wd), y(s))
            lineTo(x(wa + wd + wsus), y(s))
            quadraticBezierTo(x(wa + wd + wsus + wr * 0.15f), y(0f), x(total), y(0f))
        }
        drawPath(path, accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

// ------------------------------------------------------------------------------- keyboard

/** Two octaves of keys; press and hold to play. */
@Composable
private fun Keyboard(startOctave: Int, onNote: (Int, Boolean) -> Unit) {
    var octave by remember(startOctave) { mutableIntStateOf(startOctave) }
    val base = (octave + 1) * 12
    val whites = (base..base + 24).filter { !isBlackKey(it) }
    Column(Modifier.fillMaxWidth().background(DawColors.Card).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 22.sp, color = if (octave > 0) DawColors.Ink else DawColors.LineStrong, modifier = Modifier.clip(CircleShape).clickable(enabled = octave > 0) { octave-- }.padding(horizontal = 14.dp))
            Text("${noteName(base)} – ${noteName(base + 24)}", fontSize = 12.sp, color = DawColors.Muted, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("›", fontSize = 22.sp, color = if (octave < 7) DawColors.Ink else DawColors.LineStrong, modifier = Modifier.clip(CircleShape).clickable(enabled = octave < 7) { octave++ }.padding(horizontal = 14.dp))
        }
        Spacer(Modifier.height(4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(96.dp)) {
            val keyW = maxWidth / whites.size
            Row(Modifier.fillMaxSize()) {
                whites.forEach { pitch -> Key(pitch, black = false, onNote = onNote, modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 1.dp)) }
            }
            whites.forEachIndexed { i, pitch ->
                if (i < whites.size - 1 && isBlackKey(pitch + 1)) {
                    Key(
                        pitch + 1,
                        black = true,
                        onNote = onNote,
                        modifier = Modifier
                            .offset(x = keyW * (i + 1) - keyW * 0.32f)
                            .width(keyW * 0.64f)
                            .height(58.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Key(pitch: Int, black: Boolean, onNote: (Int, Boolean) -> Unit, modifier: Modifier) {
    var down by remember { mutableStateOf(false) }
    val play by rememberUpdatedState(onNote)
    val color = when {
        down -> DawColors.Accent
        black -> DawColors.Note
        else -> DawColors.Paper
    }
    Box(
        modifier
            .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
            .background(color)
            .border(1.dp, DawColors.Line, RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
            .pointerInput(pitch) {
                detectTapGestures(onPress = {
                    down = true
                    play(pitch, true)
                    tryAwaitRelease()
                    play(pitch, false)
                    down = false
                })
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (!black && pitch % 12 == 0) {
            Text(noteName(pitch), fontSize = 9.sp, color = DawColors.Muted, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

// ------------------------------------------------------------------------------- presets

@Composable
private fun PresetBrowser(vm: DawViewModel, current: SynthPatch, onPick: (SynthPreset) -> Unit, onDismiss: () -> Unit) {
    val categories = listOf("All") + SynthPresetLibrary.CATEGORIES + SynthPresetLibrary.USER
    var filter by remember { mutableStateOf(current.category.takeIf { it in SynthPresetLibrary.CATEGORIES } ?: "All") }
    val user = vm.userSynthPresets
    val shown: List<Pair<SynthPreset, Boolean>> = when (filter) {
        "All" -> vm.synthPresets.factory.map { it to false } + user.map { it to true }
        SynthPresetLibrary.USER -> user.map { it to true }
        else -> vm.synthPresets.factory.filter { it.category == filter }.map { it to false } +
            user.filter { it.category == filter }.map { it to true }
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
                    Text("Presets", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
                    Text("Tap one to load and hear it.", fontSize = 12.sp, color = DawColors.Muted)
                }
                TextAction("Done", strong = true, onClick = onDismiss)
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                categories.forEach { c -> com.mobiledaw.ui.DawChip(c, selected = c == filter, dotColor = null) { filter = c } }
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (shown.isEmpty()) {
                    item {
                        Text(
                            "Nothing here yet. Shape a sound, then tap Save in the synth to keep it.",
                            fontSize = 13.sp,
                            color = DawColors.Muted,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                items(shown, key = { (p, mine) -> (if (mine) "u:" else "f:") + p.category + "/" + p.name }) { (preset, mine) ->
                    val selected = preset.name == current.preset && preset.category == current.category
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) DawColors.AccentSoft else DawColors.Card)
                            .border(1.dp, DawColors.Line, RoundedCornerShape(14.dp))
                            .clickable { onPick(preset) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(preset.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink, modifier = Modifier.weight(1f))
                        Text(if (mine) "${preset.category} · yours" else preset.category, fontSize = 12.sp, color = DawColors.Muted)
                        if (mine) {
                            Text(
                                "×",
                                fontSize = 18.sp,
                                color = DawColors.Muted,
                                modifier = Modifier.clip(CircleShape).clickable { vm.deleteSynthPreset(preset.name) }.padding(start = 12.dp, end = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveDialog(patch: SynthPatch, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(if (patch.preset == "Init") "" else patch.preset) }
    var category by remember { mutableStateOf(patch.category.takeIf { it in SynthPresetLibrary.CATEGORIES } ?: SynthPresetLibrary.CATEGORIES.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save preset") },
        text = {
            Column {
                Text("Keeps this sound in Presets → Yours, ready for any project. A preset of yours with the same name is replaced.", fontSize = 13.sp, color = DawColors.InkSoft)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SynthPresetLibrary.CATEGORIES.forEach { c -> com.mobiledaw.ui.DawChip(c, selected = c == category, dotColor = null) { category = c } }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name, category); onDismiss() }) { Text("Save", color = DawColors.Ink) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = DawColors.Muted) } },
        containerColor = DawColors.Card,
    )
}
