package com.mobiledaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.DawViewModel
import com.mobiledaw.ui.theme.DawColors

/**
 * A full-screen sound browser with the preview player pinned to the bottom. Leaving the screen
 * stops whatever preview is still playing.
 */
@Composable
internal fun PreviewScreen(vm: DawViewModel, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DisposableEffect(Unit) { onDispose { vm.stopAudition() } }
    FullScreen(onDismiss) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize(), content = content)
            AddedNotice(vm, Modifier.align(Alignment.BottomCenter))
        }
        PreviewPlayerBar(vm)
    }
}

/** The sound being previewed, how far it has played, and a square stop button. */
@Composable
fun PreviewPlayerBar(vm: DawViewModel) {
    val preview = vm.libraryPreview
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(preview) {
        progress = 0f
        val p = preview ?: return@LaunchedEffect
        while (progress < 1f) {
            withFrameMillis { }
            progress = ((android.os.SystemClock.uptimeMillis() - p.startedAt).toFloat() / p.durationMs).coerceIn(0f, 1f)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(DawColors.Card)
            .border(1.dp, DawColors.Line)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                preview?.name ?: "Tap a sound to hear it",
                fontSize = 13.sp,
                color = if (preview != null) DawColors.Ink else DawColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(DawColors.Chip),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(if (preview != null) progress else 0f)
                        .background(DawColors.Accent),
                )
            }
            if (preview != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "${formatSeconds(progress * preview.durationMs)} / ${formatSeconds(preview.durationMs.toFloat())}",
                    fontSize = 10.sp,
                    color = DawColors.Muted,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        // Stop: a filled square in a round button, live only while something plays.
        val live = preview != null
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (live) DawColors.Note else DawColors.Chip)
                .clickable(enabled = live) { vm.stopAudition() }
                .semantics { contentDescription = "Stop preview" },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (live) DawColors.OnNote else DawColors.Muted),
            )
        }
    }
}

private fun formatSeconds(ms: Float): String {
    val total = (ms / 1000f).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
