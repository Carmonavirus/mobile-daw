package com.mobiledaw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledaw.DawViewModel
import com.mobiledaw.cloud.CloudSync
import com.mobiledaw.cloud.SyncStatus
import com.mobiledaw.ui.theme.DawColors
import kotlinx.coroutines.delay

/** Settings → Sync: Google sign-in, what the sync is doing, Sync now and Sign out. */
@Composable
fun CloudSyncSection(vm: DawViewModel) {
    val cloud = vm.cloud
    Text("Google Drive", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DawColors.Ink)
    Spacer(Modifier.height(4.dp))
    when {
        !cloud.configured -> Text(
            "Google sign-in isn't set up for this version yet.",
            fontSize = 12.sp,
            color = DawColors.Muted,
        )
        !cloud.signedIn -> {
            Text(
                "Sign in to keep your projects and sounds in your own Google Drive, so they show up on your phone, tablet and laptop. They're saved in a \"Mobile DAW\" folder.",
                fontSize = 12.sp,
                color = DawColors.Muted,
            )
            cloud.problem?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, fontSize = 12.sp, color = DawColors.Mute)
            }
            Spacer(Modifier.height(10.dp))
            TextAction(if (cloud.status == SyncStatus.SYNCING) "Signing in…" else "Sign in with Google", strong = true) { cloud.signIn() }
        }
        else -> {
            Text("Signed in as ${cloud.email ?: "your Google account"}", fontSize = 12.sp, color = DawColors.Muted)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CloudGlyph(cloud.status, Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(statusText(cloud), fontSize = 13.sp, color = DawColors.Ink)
            }
            cloud.problem?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, fontSize = 12.sp, color = DawColors.Mute)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cloud.status == SyncStatus.SIGN_IN_AGAIN) {
                    TextAction("Sign in again", strong = true) { cloud.signIn() }
                } else {
                    TextAction("Sync now", strong = true) { cloud.syncSoon(0) }
                }
                TextAction("Sign out") { cloud.signOut() }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Projects sync by themselves when you save or open the app. If a project changed on two devices, the newest one is kept and the other is saved with \"older copy\" added to its name.",
                fontSize = 11.sp,
                color = DawColors.Muted,
            )
        }
    }
}

/** Plain words for the sync status ("Synced 2 min ago"). */
@Composable
fun statusText(cloud: CloudSync): String {
    // Tick once a minute so "x min ago" stays right.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return when (cloud.status) {
        SyncStatus.OFF -> "Not syncing"
        SyncStatus.SYNCING -> "Syncing…"
        SyncStatus.OFFLINE -> "Offline. Your changes are saved here and will sync when you're back online."
        SyncStatus.SIGN_IN_AGAIN -> "Sign in again to keep syncing."
        SyncStatus.PROBLEM -> "Sync is paused."
        SyncStatus.SYNCED -> {
            val last = cloud.lastSynced
            val mins = ((maxOf(now, System.currentTimeMillis()) - last) / 60_000L).toInt()
            when {
                last == 0L -> "Synced"
                mins < 1 -> "Synced just now"
                mins < 60 -> "Synced $mins min ago"
                mins < 60 * 24 -> "Synced ${mins / 60} h ago"
                else -> "Synced ${mins / (60 * 24)} days ago"
            }
        }
    }
}

/**
 * The little cloud in the header, shown while signed in: grey when synced, accent while syncing,
 * red when it needs attention. Tapping it signs in again when that's what's needed.
 */
@Composable
fun CloudStatusButton(vm: DawViewModel, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val cloud = vm.cloud
    if (!cloud.signedIn) return
    val label = when (cloud.status) {
        SyncStatus.SIGN_IN_AGAIN -> "Sign in to Google again"
        else -> "Google Drive sync"
    }
    Box(
        modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = label) {
                if (cloud.status == SyncStatus.SIGN_IN_AGAIN) cloud.signIn() else onOpenSettings()
            },
        contentAlignment = Alignment.Center,
    ) {
        CloudGlyph(cloud.status, Modifier.size(22.dp))
    }
}

@Composable
private fun CloudGlyph(status: SyncStatus, modifier: Modifier = Modifier) {
    val color = when (status) {
        SyncStatus.SYNCING -> DawColors.Accent
        SyncStatus.OFFLINE, SyncStatus.OFF -> DawColors.Muted.copy(alpha = 0.55f)
        SyncStatus.SIGN_IN_AGAIN, SyncStatus.PROBLEM -> DawColors.Mute
        SyncStatus.SYNCED -> DawColors.Muted
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // A cloud: three puffs on a rounded base.
        drawCircle(color, radius = w * 0.2f, center = Offset(w * 0.32f, h * 0.58f))
        drawCircle(color, radius = w * 0.26f, center = Offset(w * 0.55f, h * 0.45f))
        drawCircle(color, radius = w * 0.17f, center = Offset(w * 0.76f, h * 0.6f))
        drawRoundRect(color, topLeft = Offset(w * 0.12f, h * 0.55f), size = Size(w * 0.81f, h * 0.22f), cornerRadius = CornerRadius(h * 0.11f))
        // Offline: a slash through it.
        if (status == SyncStatus.OFFLINE) {
            drawLine(DawColors.Paper, Offset(w * 0.2f, h * 0.2f), Offset(w * 0.85f, h * 0.88f), w * 0.12f)
            drawLine(color, Offset(w * 0.14f, h * 0.14f), Offset(w * 0.86f, h * 0.9f), w * 0.07f)
        }
    }
}
