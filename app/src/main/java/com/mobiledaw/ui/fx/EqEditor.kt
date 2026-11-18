package com.mobiledaw.ui.fx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.DawViewModel
import com.mobiledaw.model.EqBand
import com.mobiledaw.model.EqBandType
import com.mobiledaw.model.EqSettings
import com.mobiledaw.ui.synth.Switch
import com.mobiledaw.ui.synth.SynthCard
import com.mobiledaw.ui.theme.DawColors
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow

private const val MIN_HZ = 20.0
private const val MAX_HZ = 20000.0
private const val RANGE_DB = 18f

/** Each band keeps its own colour, on the graph and in the band list. */
private val BAND_COLORS = listOf(
    Color(0xFFE0565B), Color(0xFFF08A3C), Color(0xFFE2B33B), Color(0xFF5BB36A),
    Color(0xFF3FA7C4), Color(0xFF4F74C9), Color(0xFF8B5CF6), Color(0xFFD0587A),
)

private fun xOf(hz: Double, width: Float) = (ln(hz / MIN_HZ) / ln(MAX_HZ / MIN_HZ)).toFloat() * width
private fun hzOf(x: Float, width: Float) = MIN_HZ * (MAX_HZ / MIN_HZ).pow((x / width).coerceIn(0f, 1f).toDouble())
private fun yOf(db: Double, height: Float) = (height / 2f - (db / RANGE_DB).toFloat() * height / 2f)
private fun dbOf(y: Float, height: Float) = ((height / 2f - y) / (height / 2f) * RANGE_DB)

/**
 * Parametric EQ: drag a band's dot sideways for frequency and up/down for gain, double-tap
 * an empty spot to add a band there. The live spectrum of the track shows behind the curve.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqEditor(vm: DawViewModel, trackId: Long, settings: EqSettings, onChange: (EqSettings) -> Unit) {
    var selected by remember { mutableIntStateOf(if (settings.bands.isNotEmpty()) 0 else -1) }
    if (selected >= settings.bands.size) selected = settings.bands.size - 1
    val latest by rememberUpdatedState(settings)
    val change by rememberUpdatedState(onChange)

    fun setBand(i: Int, transform: (EqBand) -> EqBand) {
        val s = latest
        if (i !in s.bands.indices) return
        change(s.copy(bands = s.bands.mapIndexed { j, b -> if (j == i) transform(b) else b }))
    }

    fun addBand(hz: Double, gain: Float) {
        val s = latest
        if (s.bands.size >= EqSettings.EQ_BANDS) return
        val type = when {
            hz < 60 -> EqBandType.LOW_CUT
            hz > 14000 -> EqBandType.HIGH_CUT
            else -> EqBandType.BELL
        }
        change(s.copy(bands = s.bands + EqBand(type, hz.toFloat(), if (type.hasGain) gain else 0f, if (type == EqBandType.BELL) 1f else 0.71f)))
        selected = s.bands.size
    }

    // Columns the curve and spectrum are evaluated at.
    val columns = 160
    val freqs = remember { DoubleArray(columns) { MIN_HZ * (MAX_HZ / MIN_HZ).pow(it / (columns - 1.0)) } }
    val analyser = remember { SpectrumAnalyser() }
    var spectrum by remember { mutableStateOf(FloatArray(0)) }
    LaunchedEffect(trackId) {
        val buf = FloatArray(2048)
        while (true) {
            vm.readTrackSignal(trackId, buf)
            spectrum = analyser.analyse(buf, freqs)
            delay(40)
        }
    }

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(DawColors.Card)
                .border(1.dp, DawColors.Line, RoundedCornerShape(18.dp)),
        ) {
            var dragging by remember { mutableIntStateOf(-1) }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { pos ->
                                selected = nearestBand(latest, pos, size.width.toFloat(), size.height.toFloat(), 36.dp.toPx())
                                    .takeIf { it >= 0 } ?: selected
                            },
                            onDoubleTap = { pos ->
                                val hit = nearestBand(latest, pos, size.width.toFloat(), size.height.toFloat(), 36.dp.toPx())
                                if (hit >= 0) {
                                    setBand(hit) { it.copy(on = !it.on) }
                                } else {
                                    addBand(hzOf(pos.x, size.width.toFloat()), dbOf(pos.y, size.height.toFloat()).coerceIn(-RANGE_DB, RANGE_DB))
                                }
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                dragging = nearestBand(latest, pos, size.width.toFloat(), size.height.toFloat(), 40.dp.toPx())
                                if (dragging >= 0) selected = dragging
                            },
                            onDragEnd = { dragging = -1 },
                            onDragCancel = { dragging = -1 },
                        ) { change, _ ->
                            if (dragging < 0) return@detectDragGestures
                            change.consume()
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val p = change.position
                            setBand(dragging) { b ->
                                val hz = hzOf(p.x, w).toFloat().coerceIn(20f, 20000f)
                                if (b.type.hasGain) b.copy(freq = hz, gain = dbOf(p.y, h).coerceIn(-RANGE_DB, RANGE_DB)) else b.copy(freq = hz)
                            }
                        }
                    },
            ) {
                drawGrid()
                drawSpectrum(spectrum, freqs)
                drawCurves(settings, selected, freqs)
                settings.bands.forEachIndexed { i, b -> drawHandle(i, b, settings, i == selected) }
            }
            if (settings.bands.isEmpty()) {
                Text(
                    "Double-tap to add a band",
                    fontSize = 13.sp,
                    color = DawColors.Muted,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        BandChips(settings, selected, onSelect = { selected = it }, onAdd = { addBand(1000.0, 0f) })
        val band = settings.bands.getOrNull(selected)
        if (band != null) {
            SynthCard(
                title = "Band ${selected + 1}",
                trailing = {
                    Switch(if (band.on) "On" else "Off", band.on) { on -> setBand(selected) { it.copy(on = on) } }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(DawColors.Chip).clickable {
                            val s = latest
                            change(s.copy(bands = s.bands.filterIndexed { j, _ -> j != selected }))
                            selected = (selected - 1).coerceAtLeast(if (s.bands.size > 1) 0 else -1)
                        }.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text("Remove", fontSize = 12.sp, color = DawColors.Muted) }
                },
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    EqBandType.entries.forEach { t ->
                        Pill(t.label, t == band.type) {
                            setBand(selected) { it.copy(type = t, q = if (t == EqBandType.BELL || t == EqBandType.NOTCH) maxOf(it.q, 0.5f) else it.q) }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    FxKnob("Freq", band.freq, KnobRange(20f, 20000f, 1000f, log = true), ::formatHz, { v -> setBand(selected) { it.copy(freq = v) } })
                    FxKnob(
                        "Gain", band.gain, KnobRange(-RANGE_DB, RANGE_DB, 0f, bipolar = true), ::formatDb,
                        { v -> setBand(selected) { it.copy(gain = if (abs(v) < 0.3f) 0f else v) } },
                        enabled = band.type.hasGain,
                    )
                    FxKnob("Q", band.q, KnobRange(0.1f, 18f, if (band.type == EqBandType.BELL) 1f else 0.71f, log = true), { "%.2f".format(it) }, { v -> setBand(selected) { it.copy(q = v) } })
                }
                if (band.type == EqBandType.LOW_CUT || band.type == EqBandType.HIGH_CUT) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Slope", fontSize = 12.sp, color = DawColors.Muted)
                        Spacer(Modifier.width(10.dp))
                        listOf(1 to "12 dB", 2 to "24 dB", 4 to "48 dB").forEach { (v, l) ->
                            Pill(l, band.slope == v) { setBand(selected) { it.copy(slope = v) } }
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
            }
        }
        SynthCard(title = "Output") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                FxKnob("Gain", settings.output, KnobRange(-18f, 18f, 0f, bipolar = true), ::formatDb, { v -> change(latest.copy(output = if (abs(v) < 0.3f) 0f else v)) })
                Pill("Reset to flat", false) { change(EqSettings.flat()); selected = 0 }
            }
        }
        Text(
            "Drag a dot: sideways for frequency, up and down for gain. Double-tap empty space to add a band, double-tap a dot to switch it off.",
            fontSize = 12.sp,
            color = DawColors.Muted,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
        )
    }
}

private fun handleY(b: EqBand, s: EqSettings, h: Float): Float =
    if (b.type.hasGain) yOf(b.gain.toDouble(), h) else yOf(EqMath.totalDb(s.bands, s.output, b.freq.toDouble()).coerceIn(-RANGE_DB.toDouble(), RANGE_DB.toDouble()), h)

private fun nearestBand(s: EqSettings, pos: Offset, w: Float, h: Float, radius: Float): Int {
    var best = -1
    var bestD = radius
    s.bands.forEachIndexed { i, b ->
        val d = hypot(pos.x - xOf(b.freq.toDouble(), w), pos.y - handleY(b, s, h))
        if (d < bestD) {
            bestD = d
            best = i
        }
    }
    return best
}

private fun DrawScope.drawGrid() {
    val w = size.width
    val h = size.height
    val paint = android.graphics.Paint().apply {
        color = DawColors.Muted.toArgb()
        textSize = 9.dp.toPx()
        isAntiAlias = true
    }
    listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0).forEach { hz ->
        val x = xOf(hz, w)
        drawLine(DawColors.Line, Offset(x, 0f), Offset(x, h), 1.dp.toPx())
        val label = if (hz >= 1000) "${(hz / 1000).toInt()}k" else "${hz.toInt()}"
        drawContext.canvas.nativeCanvas.drawText(label, x + 3.dp.toPx(), h - 5.dp.toPx(), paint)
    }
    listOf(-12f, -6f, 0f, 6f, 12f).forEach { db ->
        val y = yOf(db.toDouble(), h)
        drawLine(if (db == 0f) DawColors.LineStrong else DawColors.Line, Offset(0f, y), Offset(w, y), 1.dp.toPx())
        if (db != 0f) {
            drawContext.canvas.nativeCanvas.drawText((if (db > 0) "+" else "") + db.toInt(), 4.dp.toPx(), y - 3.dp.toPx(), paint)
        }
    }
}

private fun DrawScope.drawSpectrum(levels: FloatArray, freqs: DoubleArray) {
    if (levels.size != freqs.size) return
    val w = size.width
    val h = size.height
    // -84..0 dB fills the bottom of the graph up to the top.
    val path = Path().apply {
        moveTo(0f, h)
        for (i in freqs.indices) {
            val norm = ((levels[i] + 84f) / 84f).coerceIn(0f, 1f)
            lineTo(xOf(freqs[i], w), h - norm * h * 0.92f)
        }
        lineTo(w, h)
        close()
    }
    drawPath(path, DawColors.Accent.copy(alpha = 0.13f))
}

private fun DrawScope.drawCurves(s: EqSettings, selected: Int, freqs: DoubleArray) {
    val w = size.width
    val h = size.height
    val curves = s.bands.map { EqMath.bandCurve(it, freqs) }
    s.bands.getOrNull(selected)?.let { band ->
        val c = curves[selected]
        val fill = Path().apply {
            moveTo(0f, yOf(0.0, h))
            for (i in freqs.indices) lineTo(xOf(freqs[i], w), yOf(c[i].coerceIn(-40.0, 40.0), h))
            lineTo(w, yOf(0.0, h))
            close()
        }
        drawPath(fill, BAND_COLORS[selected % BAND_COLORS.size].copy(alpha = if (band.on) 0.18f else 0.06f))
    }
    val total = Path()
    for (i in freqs.indices) {
        val db = curves.sumOf { it[i] } + s.output
        val x = xOf(freqs[i], w)
        val y = yOf(db.coerceIn(-40.0, 40.0), h)
        if (i == 0) total.moveTo(x, y) else total.lineTo(x, y)
    }
    drawPath(total, DawColors.Ink, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
}

private fun DrawScope.drawHandle(i: Int, b: EqBand, s: EqSettings, selected: Boolean) {
    val c = Offset(xOf(b.freq.toDouble(), size.width), handleY(b, s, size.height))
    val color = BAND_COLORS[i % BAND_COLORS.size]
    val r = if (selected) 12.dp.toPx() else 10.dp.toPx()
    if (selected) drawCircle(color.copy(alpha = 0.22f), r * 1.8f, c)
    drawCircle(if (b.on) color else DawColors.Card, r, c)
    drawCircle(if (b.on) DawColors.Card else color, r, c, style = Stroke(2.dp.toPx()))
    val paint = android.graphics.Paint().apply {
        this.color = (if (b.on) Color.White else color).toArgb()
        textSize = 11.dp.toPx()
        isFakeBoldText = true
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText("${i + 1}", c.x, c.y + 4.dp.toPx(), paint)
}

@Composable
private fun BandChips(s: EqSettings, selected: Int, onSelect: (Int) -> Unit, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        s.bands.forEachIndexed { i, b ->
            val color = BAND_COLORS[i % BAND_COLORS.size]
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (i == selected) color else if (b.on) color.copy(alpha = 0.25f) else DawColors.Chip)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text("${i + 1}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (i == selected) Color.White else DawColors.Ink)
            }
        }
        if (s.bands.size < EqSettings.EQ_BANDS) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).border(1.dp, DawColors.LineStrong, CircleShape).clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) { Text("+", fontSize = 16.sp, color = DawColors.Ink) }
        }
    }
}

@Composable
fun Pill(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) DawColors.Note else DawColors.Chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) DawColors.OnNote else DawColors.Ink)
    }
}
