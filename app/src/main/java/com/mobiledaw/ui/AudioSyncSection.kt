package com.mobiledaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.DawViewModel
import com.mobiledaw.audio.VisualDelay
import com.mobiledaw.ui.theme.DawColors
import kotlin.math.roundToInt

/**
 * Settings → Audio sync: delays the playhead, step lights and meters so they match what you hear
 * (Bluetooth earbuds lag the screen). Auto follows the delay the phone reports; the slider
 * fine-tunes it. Both are remembered for each output (speaker, wired, each pair of earbuds).
 */
@Composable
fun AudioSyncSection(vm: DawViewModel) {
    val device = vm.outputDevice
    Text("Playing through", fontSize = 12.sp, color = DawColors.Muted)
    Text(device.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
    val measured = vm.measuredLatencyMs
    Text(
        if (measured != null) "Measured delay: $measured ms" else "The phone hasn't reported a delay for this output yet.",
        fontSize = 12.sp,
        color = DawColors.Muted,
    )
    Spacer(Modifier.height(12.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Auto", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
            Text(
                "Delay the playhead and step lights by the measured delay, so they match what you hear.",
                fontSize = 12.sp,
                color = DawColors.Muted,
            )
        }
        Switch(
            checked = vm.latencyAuto,
            onCheckedChange = vm::changeLatencyAuto,
            colors = SwitchDefaults.colors(checkedTrackColor = DawColors.Note, checkedThumbColor = DawColors.OnNote),
        )
    }
    Spacer(Modifier.height(12.dp))

    val auto = vm.latencyAuto
    val min = if (auto) DawViewModel.LATENCY_OFFSET_MIN else 0
    val max = if (auto) DawViewModel.LATENCY_OFFSET_MAX else VisualDelay.MAX_DELAY_MS
    val offset = vm.latencyOffsetMs
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (auto) "Fine-tune" else "Screen delay", fontSize = 14.sp, color = DawColors.Ink, modifier = Modifier.weight(1f))
        Text(if (auto && offset > 0) "+$offset ms" else "$offset ms", fontSize = 13.sp, color = DawColors.InkSoft)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("−") { vm.changeLatencyOffset(offset - 5) }
        Slider(
            value = offset.toFloat(),
            onValueChange = { v ->
                val ms = v.roundToInt()
                // Sticks to 0 when close, so "no extra" is easy to find.
                vm.changeLatencyOffset(if (auto && kotlin.math.abs(ms) < (max - min) / 60) 0 else ms)
            },
            valueRange = min.toFloat()..max.toFloat(),
            colors = inkSliderColors(),
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
        )
        StepButton("+") { vm.changeLatencyOffset(offset + 5) }
    }
    Text(
        "Screen runs ${vm.uiDelayMs} ms behind the engine" +
            (if (vm.latencyEstimated) " (estimate)" else "") +
            ". If the playhead is still early, slide right; if it's late, slide left. Saved for ${device.label}.",
        fontSize = 12.sp,
        color = DawColors.Muted,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Recordings are lined up with the same delay, so takes sit where you sang them.",
        fontSize = 12.sp,
        color = DawColors.Muted,
    )
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(DawColors.Card)
            .border(1.dp, DawColors.Line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 18.sp, color = DawColors.Ink) }
}
