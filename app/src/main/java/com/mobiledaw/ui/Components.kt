package com.mobiledaw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.ui.theme.DawColors

/** Pill-shaped chip with a leading dot, like the track chips in the piano roll. */
@Composable
fun DawChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    dotColor: Color? = DawColors.Ink,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(if (selected) DawColors.ChipActive else DawColors.Chip)
            .border(1.dp, if (selected) DawColors.InkSoft.copy(alpha = 0.55f) else DawColors.Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dotColor != null) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (selected) dotColor else dotColor.copy(alpha = 0.5f)))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) DawColors.Ink else DawColors.Muted,
        )
    }
}

/** Small segmented control used for modes and lengths. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(DawColors.Chip).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) DawColors.Card else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(label, fontSize = 12.sp, color = if (on) DawColors.Ink else DawColors.Muted, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
fun PlayButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, diameter: androidx.compose.ui.unit.Dp = 44.dp) {
    Box(
        modifier
            .size(diameter)
            .clip(CircleShape)
            .background(DawColors.Note)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(diameter * 0.36f)) {
            if (playing) {
                drawRoundRect(DawColors.OnNote, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f))
            } else {
                val path = Path().apply {
                    moveTo(size.width * 0.12f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(size.width * 0.12f, size.height)
                    close()
                }
                drawPath(path, DawColors.OnNote)
            }
        }
    }
}

/** Two short lines, the "menu" glyph from the reference design. */
@Composable
fun MenuGlyph(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(width = 22.dp, height = 10.dp)) {
            val stroke = 2.dp.toPx()
            drawLine(DawColors.Ink, Offset(0f, stroke), Offset(size.width, stroke), stroke)
            drawLine(DawColors.Ink, Offset(0f, size.height - stroke), Offset(size.width * 0.75f, size.height - stroke), stroke)
        }
    }
}

/** Little round toggle (mute / solo). */
@Composable
fun RoundToggle(label: String, on: Boolean, onColor: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (on) onColor else DawColors.Chip)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else DawColors.Muted)
    }
}

/** Mirrored bar waveform, drawn from engine scope peaks. */
@Composable
fun WaveformStrip(peaks: FloatArray, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val n = peaks.size
        if (n == 0) return@Canvas
        val step = size.width / n
        val bar = (step * 0.45f).coerceAtLeast(1f)
        val mid = size.height / 2f
        for (i in 0 until n) {
            val h = (peaks[i].coerceIn(0f, 1f) * 0.9f + 0.02f) * size.height / 2f
            val x = i * step + step / 2f
            val fade = 0.25f + 0.75f * (i.toFloat() / n)
            drawLine(DawColors.Ink.copy(alpha = fade), Offset(x, mid - h), Offset(x, mid + h), bar)
        }
    }
}
