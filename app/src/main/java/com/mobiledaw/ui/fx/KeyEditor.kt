package com.mobiledaw.ui.fx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.DawViewModel
import com.mobiledaw.model.DetectedKey
import com.mobiledaw.model.KeyMemory
import com.mobiledaw.model.KeyReading
import com.mobiledaw.model.KeySettings
import com.mobiledaw.model.NOTE_NAMES
import com.mobiledaw.ui.synth.SynthCard
import com.mobiledaw.ui.theme.DawColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Key Detector: listens to whatever plays through the track and shows the key it hears, with
 * how sure it is. One tap makes it the song's key (Pitch Correct and the piano roll follow).
 */
@Composable
fun KeyEditor(vm: DawViewModel, trackId: Long, effectId: Long, settings: KeySettings, bypassed: Boolean, onChange: (KeySettings) -> Unit) {
    var reading by remember { mutableStateOf(KeyReading(FloatArray(KeyReading.SIZE) { if (it == 0 || it == 2) -1f else 0f })) }
    LaunchedEffect(trackId, effectId) {
        val out = FloatArray(KeyReading.SIZE)
        while (true) {
            vm.readKey(trackId, effectId, out)
            reading = KeyReading(out.copyOf())
            delay(150)
        }
    }
    val p = vm.project
    val key = reading.key
    val songKey = DetectedKey(p.key, p.minor)

    Column {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(DawColors.Card)
                .border(1.dp, DawColors.Line, RoundedCornerShape(18.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                key?.label ?: if (bypassed) "Turned off" else "Listening…",
                fontSize = 40.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (key != null) DawColors.Ink else DawColors.Muted,
            )
            Text(
                when {
                    key == null && bypassed -> "Turn it on to listen to this track"
                    key == null -> "Play something through this track"
                    else -> "${sureness(reading.confidence)} · ${(reading.confidence * 100).roundToInt()}%"
                },
                fontSize = 12.sp,
                color = DawColors.Muted,
            )
            Spacer(Modifier.height(10.dp))
            ConfidenceBar(if (key != null) reading.confidence else 0f)
            if (key != null) {
                val other = reading.second?.takeIf { it != key }
                Spacer(Modifier.height(8.dp))
                Text(
                    buildString {
                        append("Same notes as ${key.relative.label}")
                        if (other != null && other != key.relative && reading.confidence < 0.6f) append(" · could also be ${other.label}")
                    },
                    fontSize = 12.sp,
                    color = DawColors.Muted,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(14.dp))
            val isSong = key == songKey
            BigButton(
                when {
                    key == null -> "Use as song key"
                    isSong -> "Song key is ${key.label} ✓"
                    else -> "Use ${key.label} as song key"
                },
                enabled = key != null && !isSong,
            ) { key?.let { vm.setKey(it.root, it.minor) } }
            Spacer(Modifier.height(6.dp))
            Text(
                "Song key now: ${songKey.label}. Pitch Correct and the piano roll's key follow it.",
                fontSize = 11.sp,
                color = DawColors.Muted,
                textAlign = TextAlign.Center,
            )
        }

        SynthCard(title = "Notes heard") {
            NoteBars(reading.chroma, key)
            Spacer(Modifier.height(6.dp))
            Text(
                if (key != null) "Taller bars were heard more. Filled bars are the notes of ${key.label}." else "Taller bars were heard more.",
                fontSize = 11.sp,
                color = DawColors.Muted,
            )
        }

        SynthCard(
            title = "Listen to",
            trailing = { Pill("Start over", false) { onChange(settings.copy(restarts = settings.restarts + 1)) } },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                KeyMemory.entries.forEach { m -> Pill(m.label, m == settings.memory) { onChange(settings.copy(memory = m)) } }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Short memory follows key changes quickly; Everything is steadiest for a whole song or a sample. " +
                    "Works best on chords, melodies, bass or vocals. Drums alone have no key. " +
                    "Put it on the Master to hear the whole song.",
                fontSize = 11.sp,
                color = DawColors.Muted,
            )
        }
    }
}

private fun sureness(c: Float) = when {
    c >= 0.75f -> "Very sure"
    c >= 0.5f -> "Fairly sure"
    c >= 0.25f -> "Not sure yet"
    else -> "Guessing"
}

@Composable
private fun ConfidenceBar(value: Float) {
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(DawColors.Chip, size = size, cornerRadius = r)
        val w = size.width * value.coerceIn(0f, 1f)
        if (w > 0f) drawRoundRect(DawColors.Accent, size = Size(w.coerceAtLeast(size.height), size.height), cornerRadius = r)
    }
}

/** One bar per note name, C to B. */
@Composable
private fun NoteBars(chroma: FloatArray, key: DetectedKey?) {
    val inKey = key?.notes.orEmpty()
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val gap = 4.dp.toPx()
        val w = (size.width - gap * 11) / 12f
        for (i in 0 until 12) {
            val x = i * (w + gap)
            drawRoundRect(DawColors.Chip, Offset(x, 0f), Size(w, size.height), CornerRadius(6.dp.toPx()))
            val h = size.height * chroma[i].coerceIn(0f, 1f)
            if (h > 1f) {
                val color = if (i in inKey) DawColors.Note else DawColors.LineStrong
                drawRoundRect(color, Offset(x, size.height - h), Size(w, h), CornerRadius(6.dp.toPx()))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        NOTE_NAMES.forEachIndexed { i, n ->
            Text(
                n,
                fontSize = 10.sp,
                fontWeight = if (key?.root == i) FontWeight.SemiBold else FontWeight.Normal,
                color = if (i in inKey) DawColors.Ink else DawColors.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BigButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(if (enabled) DawColors.Note else DawColors.Chip)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) DawColors.OnNote else DawColors.Muted)
    }
}
