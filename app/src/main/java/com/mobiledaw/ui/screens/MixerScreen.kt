package com.mobiledaw.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.DawViewModel
import com.mobiledaw.audio.NativeEngine
import com.mobiledaw.ui.RoundToggle
import com.mobiledaw.ui.inkSliderColors
import com.mobiledaw.ui.theme.DawColors
import kotlin.math.abs
import kotlin.math.roundToInt

/** One horizontal strip per channel, stacked vertically to suit a portrait screen. */
@Composable
fun MixerScreen(vm: DawViewModel) {
    val levels = vm.levels
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Strip(
                name = "Master",
                level = levels.getOrElse(NativeEngine.MAX_CHANNELS) { 0f },
                volume = vm.project.masterVolume,
                onVolume = vm::setMasterVolume,
                emphasized = true,
            )
        }
        itemsIndexed(vm.project.channels, key = { _, c -> c.id }) { index, ch ->
            Strip(
                name = ch.name,
                subtitle = ch.instrument.label.lowercase(),
                level = levels.getOrElse(index) { 0f },
                volume = ch.volume,
                onVolume = { v -> vm.updateChannel(ch.id) { it.copy(volume = v) } },
                pan = ch.pan,
                onPan = { v -> vm.updateChannel(ch.id) { it.copy(pan = if (abs(v) < 0.06f) 0f else v) } },
                mute = ch.mute,
                onMute = { vm.updateChannel(ch.id) { it.copy(mute = !it.mute) } },
                solo = ch.solo,
                onSolo = { vm.updateChannel(ch.id) { it.copy(solo = !it.solo) } },
            )
        }
    }
}

@Composable
private fun Strip(
    name: String,
    level: Float,
    volume: Float,
    onVolume: (Float) -> Unit,
    subtitle: String? = null,
    pan: Float? = null,
    onPan: (Float) -> Unit = {},
    mute: Boolean = false,
    onMute: (() -> Unit)? = null,
    solo: Boolean = false,
    onSolo: (() -> Unit)? = null,
    emphasized: Boolean = false,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (emphasized) DawColors.Chip else DawColors.Card)
            .border(1.dp, DawColors.Line, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
            if (subtitle != null) {
                Spacer(Modifier.width(6.dp))
                Text(subtitle, fontSize = 12.sp, color = DawColors.Muted)
            }
            Spacer(Modifier.weight(1f))
            Text("${(volume * 100).roundToInt()}%", fontSize = 12.sp, color = DawColors.Muted)
            if (onMute != null) {
                Spacer(Modifier.width(8.dp))
                RoundToggle("M", mute, DawColors.Mute, onMute)
            }
            if (onSolo != null) {
                Spacer(Modifier.width(6.dp))
                RoundToggle("S", solo, DawColors.Solo, onSolo)
            }
        }
        Spacer(Modifier.height(6.dp))
        Meter(level)
        Slider(value = volume, onValueChange = onVolume, valueRange = 0f..1f, colors = inkSliderColors())
        if (pan != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("L", fontSize = 11.sp, color = DawColors.Muted)
                Slider(
                    value = pan,
                    onValueChange = onPan,
                    valueRange = -1f..1f,
                    colors = inkSliderColors(),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text("R", fontSize = 11.sp, color = DawColors.Muted)
            }
        }
    }
}

@Composable
private fun Meter(level: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(50))
            .background(DawColors.Line),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(50))
                .background(if (level > 0.95f) DawColors.Mute else DawColors.Accent),
        )
    }
}
