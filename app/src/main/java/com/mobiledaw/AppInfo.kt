package com.mobiledaw

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Version info and the in-app changelog shown in Settings → Versions. */
object AppInfo {
    /** Always points at the newest build CI published. */
    const val LATEST_APK_URL = "https://github.com/Carmonavirus/mobile-daw/releases/download/latest-build/mobile-daw.apk"

    /** [utc] is when the update was made, e.g. "2026-11-12T11:20:00Z"; shown in the phone's time zone. */
    class Release(val version: String, val utc: String, val changes: List<String>) {
        val localTime: String
            get() = runCatching { formatLocal(java.time.Instant.parse(utc).toEpochMilli()) }.getOrDefault(utc)
    }

    /** When this APK was built (stamped by Gradle), in the phone's time zone. */
    val buildTime: String get() = formatLocal(BuildConfig.BUILD_TIME)

    private fun formatLocal(epochMillis: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(epochMillis))

    /** Newest first. Add an entry here with every update. */
    val releases = listOf(
        Release(
            "0.5", "2026-11-12T13:30:00Z",
            listOf(
                "Mixer tracks: the Mixer now has a Vocals track with its own effects. Recordings play through it; add more tracks with + Track.",
                "Send any channel to a mixer track with its → Master chip in the Mixer.",
                "New Parametric EQ: up to 8 bands you drag on a graph over the live spectrum. Bell, shelves, cuts (12/24/48 dB) and notch.",
                "New Pitch Correct (auto-tune): pulls the voice to the song's key. Retune speed from instant (robotic) to natural, humanize, transpose.",
            ),
        ),
        Release(
            "0.4.3", "2026-11-12T13:15:00Z",
            listOf(
                "Piano roll: FL-style scale colouring — in Key mode, notes in your scale are light and the rest are dark.",
                "Piano roll: optional one-finger scroll (Settings → Editing); tap still places notes.",
                "Piano roll: the track chips no longer overlap the note grid.",
            ),
        ),
        Release(
            "0.4.2", "2026-11-12T13:05:00Z",
            listOf(
                "Rack: the ♪ / ⋯ / M buttons always stay visible; long names now shorten with …",
                "Rack: swipe anywhere (not just the steps) to change bars.",
                "Rack: Delete and a new Rename moved into the ⋯ menu; added sounds get a short name.",
                "Audio: the sound engine now re-opens itself if the system interrupts it, so playback doesn't go quiet.",
            ),
        ),
        Release(
            "0.4.1", "2026-11-12T12:20:00Z",
            listOf("Fix: the app crashed on launch after the last update. Fixed."),
        ),
        Release(
            "0.4", "2026-11-12T11:35:00Z",
            listOf(
                "Progress bar above the play button: shows where the pattern or song is (bar.beat) and its length.",
                "Tap or drag the progress bar to jump; while stopped, play starts from there.",
                "Piano roll NOTES switch: label only the notes in the project key, or every note.",
                "Rack: pinch to show 1, 2, 3 or 4 bars at once.",
                "Rack: swipe across the steps to go to the next/previous bar. Swipe a channel's name row right to delete.",
                "New Wavetable synth: + Add channel → Wavetable. Two wavetable oscillators you can morph, unison, sub and noise, a filter, two envelopes and an LFO that can follow the tempo.",
                "28 built-in presets: 808, bass, pluck, pad, lead, bells and keys. Save your own with Save in the synth.",
                "Tap a synth channel's name to open it. Play it with the keyboard at the bottom, or from the piano roll like any channel.",
                "808s and basses slide: in Mono mode, overlap two notes in the piano roll to glide between them.",
            ),
        ),
        Release(
            "0.3", "2026-11-12T11:20:00Z",
            listOf(
                "New Settings menu (the ≡ button, top right).",
                "Themes: pick a preset or your own background, note/step and accent colours.",
                "Versions page with this list of what changed.",
                "Update button to get the newest build.",
                "Drum kits now live in Settings → Drum kits. Each import adds a kit, nothing gets replaced.",
                "Channel settings (⋯ on each channel, or long-press its name): volume with boost, pan, Cut itself, trim start/end on the waveform, attack and release.",
                "Versions show the time of each update, and the exact build time of the app you have.",
                "Rack: swipe a channel right to delete it (with Undo). Tap any channel's name to pick a kit sound; your name stays, the sound's file name shows small beside it.",
                "Playlist now runs left to right like the rest of the app, with 8 tracks. Swipe sideways to move through the song.",
                "Piano roll: two fingers now scroll freely (left/right and up/down); a clear pinch zooms.",
                "Settings → How to get around: a guide to every gesture.",
                "Long-press a channel name: Fill every 2 / 4 / 8 steps, Clear steps, or Loop the pattern (copies its bars to double the length).",
                "Piano roll grid: vertical lines follow the Grid buttons (1/32 to 1/2), notes snap to it and new notes take its length.",
                "No more double hits: placing steps or notes is silent while the beat plays (Settings → Editing to silence it always).",
                "Pattern templates: tap the selected pattern, Save as template, then load it from + in any project.",
                "Tapping a drum shows all your kits and their folders, with matching folders (e.g. Kicks) opened.",
                "Smaller play button, now just above the tabs.",
            ),
        ),
        Release(
            "0.2", "2026-11-12T10:37:00Z",
            listOf(
                "Import drum kits from a .zip or a folder (WAV sounds).",
                "Tap a drum channel's name to choose a sound from your kits.",
                "The rack shows one bar at a time, with arrows to move between bars and + to add one (up to 16).",
            ),
        ),
        Release(
            "0.1", "2026-11-09T21:40:00Z",
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
