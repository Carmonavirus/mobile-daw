package com.mobiledaw.ui.fx

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobiledaw.DawViewModel
import com.mobiledaw.model.EffectType
import com.mobiledaw.model.EqSettings
import com.mobiledaw.model.PitchSettings
import com.mobiledaw.ui.TextAction
import com.mobiledaw.ui.synth.Switch
import com.mobiledaw.ui.theme.DawColors

/** Full-screen editor for one effect on a mixer track. */
@Composable
fun EffectEditor(vm: DawViewModel, trackId: Long, effectId: Long) {
    val track = vm.project.mixerTrack(trackId)
    val effect = track?.effects?.firstOrNull { it.id == effectId }
    if (track == null || effect == null) {
        LaunchedEffect(Unit) { vm.closeEffect() }
        return
    }
    Dialog(onDismissRequest = vm::closeEffect, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(DawColors.Paper)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(effect.type.label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("on ${track.name}", fontSize = 12.sp, color = DawColors.Muted)
                }
                Switch(if (effect.bypass) "Off" else "On", !effect.bypass) { on ->
                    vm.updateEffect(trackId, effectId) { it.copy(bypass = !on) }
                }
                Spacer(Modifier.width(10.dp))
                TextAction("Done", strong = true, onClick = vm::closeEffect)
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
            ) {
                when (effect.type) {
                    EffectType.EQ -> EqEditor(vm, trackId, effect.eq ?: EqSettings.flat()) { eq ->
                        vm.updateEffect(trackId, effectId) { it.copy(eq = eq) }
                    }
                    EffectType.PITCH -> PitchEditor(vm, trackId, effectId, vm.project, effect.pitch ?: PitchSettings()) { p ->
                        vm.updateEffect(trackId, effectId) { it.copy(pitch = p) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
