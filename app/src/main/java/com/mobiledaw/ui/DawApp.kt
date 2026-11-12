package com.mobiledaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.DawViewModel
import com.mobiledaw.PlayMode
import com.mobiledaw.Tab
import com.mobiledaw.ui.screens.ChannelRackScreen
import com.mobiledaw.ui.screens.MixerScreen
import com.mobiledaw.ui.screens.PianoRollScreen
import com.mobiledaw.ui.screens.PlaylistScreen
import com.mobiledaw.ui.theme.DawColors

@Composable
fun DawApp(vm: DawViewModel) {
    var showProjects by remember { mutableStateOf(false) }
    var showTempo by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(DawColors.Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Header(
            vm = vm,
            onTitle = { showProjects = true },
            onTempo = { showTempo = true },
            onMenu = { vm.settingsOpen = true },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (vm.tab) {
                Tab.PLAYLIST -> PlaylistScreen(vm)
                Tab.RACK -> ChannelRackScreen(vm)
                Tab.PIANO -> PianoRollScreen(vm)
                Tab.MIXER -> MixerScreen(vm)
            }
        }
        // Transport sits right above the tabs, within thumb reach.
        Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
            PlayButton(vm.playing, vm::togglePlay, diameter = 38.dp)
        }
        BottomTabs(vm.tab) { vm.tab = it }
    }

    if (showProjects) ProjectsDialog(vm) { showProjects = false }
    if (showTempo) TempoKeyDialog(vm) { showTempo = false }
    if (vm.settingsOpen) {
        SettingsScreen(
            vm = vm,
            onOpenProjects = { vm.settingsOpen = false; showProjects = true },
            onDismiss = { vm.settingsOpen = false },
        )
    }
}

@Composable
private fun Header(vm: DawViewModel, onTitle: () -> Unit, onTempo: () -> Unit, onMenu: () -> Unit) {
    val p = vm.project
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Segmented(
            options = listOf(PlayMode.PATTERN to "PAT", PlayMode.SONG to "SONG"),
            selected = vm.mode,
            onSelect = vm::switchMode,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Column(Modifier.align(Alignment.Center).padding(horizontal = 84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                p.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = DawColors.Ink,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onTitle).padding(horizontal = 8.dp),
            )
            Text(
                "${p.bpm} BPM  ·  ${p.keyLabel.uppercase()}",
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                color = DawColors.Muted,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onTempo).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        MenuGlyph(onMenu, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun BottomTabs(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(DawColors.Paper)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { tab ->
            val on = tab == current
            Column(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    tab.label,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) DawColors.Ink else DawColors.Muted,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .size(width = if (on) 16.dp else 4.dp, height = 3.dp)
                        .clip(CircleShape)
                        .background(if (on) DawColors.Ink else DawColors.Paper),
                )
            }
        }
    }
}
