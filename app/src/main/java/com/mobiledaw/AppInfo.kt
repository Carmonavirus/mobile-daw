package com.mobiledaw

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Version info and the in-app changelog shown in Settings → Versions. */
object AppInfo {
    /** Always points at the newest build CI published. */
    const val LATEST_APK_URL = "https://github.com/Carmonavirus/mobile-daw/releases/download/latest-build/mobile-daw.apk"

    class Release(val version: String, val date: String, val changes: List<String>)

    /** Newest first. Add an entry here with every update. */
    val releases = listOf(
        Release(
            "0.3", "Nov 12, 2026",
            listOf(
                "New Settings menu (the ≡ button, top right).",
                "Themes: pick a preset or your own background, note/step and accent colours.",
                "Versions page with this list of what changed.",
                "Update button to get the newest build.",
                "Smaller play button, now just above the tabs.",
            ),
        ),
        Release(
            "0.2", "Nov 12, 2026",
            listOf(
                "Import drum kits from a .zip or a folder (WAV sounds).",
                "Tap a drum channel's name to choose a sound from your kits.",
                "The rack shows one bar at a time, with arrows to move between bars and + to add one (up to 16).",
            ),
        ),
        Release(
            "0.1", "Nov 9, 2026",
            listOf(
                "First version: playlist, channel rack, piano roll and mixer.",
                "Eight built-in instruments: kick, snare, hi-hat, clap, bass, lead, pluck and pad.",
                "Projects save automatically.",
            ),
        ),
    )

    fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

    fun openLatestDownload(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(LATEST_APK_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
