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
            "0.9.2", "2026-11-13T23:50:00Z",
            listOf(
                "Share projects with friends: Projects → Share packs a project and every sound it uses (imported loops, recordings, kit samples, SoundFonts, SFZ) into one .zip. Send it from the share sheet or save it to Download/Mobile DAW/Shared projects.",
                "Projects → Import project opens a .zip a friend sent, on the phone and in the web app. You can also share or open the .zip straight into the app.",
                "Imports never replace anything: a project with a name you already use gets a number (\"Beat 2\"), and a sound that clashes with one of yours is saved under a new name.",
            ),
        ),
        Release(
            "0.9.1", "2026-11-13T23:45:00Z",
            listOf(
                "Web app works offline: after one visit with internet, the whole app (screens, sound engine, built-in drums, synths and GeneralUser GS instruments) is kept on your device, so it opens and plays with no connection. Freesound and the other online sound libraries, and Google Drive sync, still need internet.",
                "Add the web app to your home screen (iPhone: Share → Add to Home Screen; Android and Brave: Install app) and it opens like a normal app. Updates still arrive the next time you open it online.",
            ),
        ),
        Release(
            "0.9.0", "2026-11-13T23:20:00Z",
            listOf(
                "Piano roll: the moving soundwave above the play bar is now hidden by default, so the note grid is bigger. Tap the new Wave chip in the bottom row (next to Resume) to show it again. The app and the web app remember your choice.",
            ),
        ),
        Release(
            "0.8.9", "2026-11-13T22:50:00Z",
            listOf(
                "Web app on phones: pinching the playlist with two fingers zooms in and out again (the browser only passed on the finger that moved, so a pinch never started). Two-finger scrolling and zooming in the piano roll work in the browser too.",
            ),
        ),
        Release(
            "0.8.8", "2026-11-13T22:45:00Z",
            listOf(
                "Piano roll: Play now starts from the beginning every time, and Pause goes back to the start.",
                "Want it to carry on from where you paused instead? Turn on the new Resume chip in the piano roll's bottom bar, next to Grid and Key.",
            ),
        ),
        Release(
            "0.8.7", "2026-11-13T21:00:00Z",
            listOf(
                "Web app on a PC or laptop: Space plays and stops, F5 / F6 / F7 / F9 (or 1–4) switch screens, Ctrl+Z / Ctrl+Y undo and redo, Ctrl+S saves, Ctrl+O opens projects. Cmd works instead of Ctrl on a Mac.",
                "Piano roll with a mouse, FL-style: click to place a note and drag to move it, drag a note's right end to resize, right click deletes (right-drag deletes many), Ctrl or Shift+drag selects with a box. Ctrl+A, Ctrl+C / X / V, Ctrl+B duplicate, Delete, and the arrow keys work on the selection.",
                "Mouse wheel or two fingers scroll, Ctrl+wheel or a trackpad pinch zooms the piano roll and playlist. Right click deletes playlist clips and clears rack steps. Notes and clips light up under the pointer.",
                "Typing keyboard (Ctrl+K): play the selected sound from the Z and Q key rows, and record notes into the pattern while it plays.",
                "F1 or ? shows every shortcut; it's also in Settings. Phones and tablets with touch work as before.",
            ),
        ),
        Release(
            "0.8.6", "2026-11-13T20:50:00Z",
            listOf(
                "Side-by-side view: the two small close arrows now sit at the top of the dividing line and only appear while the mouse is over the line. On touch, tap the line to show them for a few seconds.",
            ),
        ),
        Release(
            "0.8.5", "2026-11-13T20:10:00Z",
            listOf(
                "Side-by-side view: the whole dividing line is now the resize handle, like on a Windows PC. With a mouse, hover anywhere on the line and the cursor turns into the left/right arrows; drag to resize. On touch, press anywhere along the line and drag. The line lights up while you do.",
                "The two small arrows in the middle of the line still close a side, and the strip on the edge still opens it again.",
            ),
        ),
        Release(
            "0.8.4", "2026-11-13T19:55:00Z",
            listOf(
                "Channel rack: a channel's ⋯ menu (or a long-press on it) now has Fill every step, next to Fill every 2, 4 and 8 steps. Fills cover every bar of the pattern.",
                "Fill and Clear steps can now be undone: tap Undo on the bar that pops up at the bottom.",
            ),
        ),
        Release(
            "0.8.3", "2026-11-13T19:50:00Z",
            listOf(
                "The play button turns into a pause button (two bars) while playing. Pause stops right where you are, and play carries on from there.",
                "Stop is still its own button: it stops, cuts every sound and goes back to where play started.",
                "Sideways, the play button now sits in the middle of the screen, and Stop sits where play used to be.",
                "Tablet side-by-side view: drag the handle on the dividing line to make either side bigger. Tap its arrows, or drag it all the way to an edge, to close a side. A closed side leaves a slim strip on the edge: tap it to open it again. Sizes are kept until you close the app.",
            ),
        ),
        Release(
            "0.8.2", "2026-11-13T19:20:00Z",
            listOf(
                "New projects now start empty: no demo beat, no preloaded sounds, no notes and nothing on the playlist. Tap + Add drums or + Add sounds in the Rack to begin.",
                "The first time you open the app you get an empty project too. Your saved projects are not changed.",
            ),
        ),
        Release(
            "0.8.1", "2026-11-13T16:30:00Z",
            listOf(
                "The app now turns sideways. Tablets turn right away, like any app.",
                "Phones turn too, but safely: when you turn the phone, the screen shows a 3, 2, 1 countdown (and ignores touches) before it flips, so a tilt by accident doesn't flip it in the middle of a beat. Turn the phone back before it reaches 0 to cancel. Turning back upright works the same way. If auto-rotate is off on your phone, the app stays put.",
                "Sideways on a phone: one screen at a time, with the tabs and play controls sharing one row so the screen gets as much height as possible.",
                "Tablet held sideways: the Playlist tab shows the playlist with the channel rack beside it, and the Rack tab shows the rack with the piano roll beside it (pick a sound on the left, write its notes on the right). The tabs and play controls share one row to leave more room.",
                "Tablet mixer: the master and mixer tracks sit on the left, and the channel strips fill a grid on the right.",
                "Turning the phone or tablet never stops playback or a recording.",
            ),
        ),
        Release(
            "0.8.0", "2026-11-13T16:00:00Z",
            listOf(
                "Playlist rows: tap + under the row numbers to add a row. Tap a row number to insert an empty row above it or delete the row (Undo brings it back).",
                "Playlist zoom: pinch the playlist with two fingers, or use the − and + buttons in the bottom-right corner. Zoomed in far enough you see every bar number and the beats.",
                "Follow: the playlist no longer jumps to the playhead while you're touching, dragging or have a clip open. It waits 4 seconds after you let go. Turn Follow off in the bottom-right corner to stop it paging along at all.",
                "Play from your spot: tap or drag to a point, press play, then stop, and the playhead goes back to that point instead of the beginning (like FL).",
                "Pattern clips: tap one for Copy (the same pattern again, right after it), Clone (this clip gets its own copy of the pattern, so you can change it alone) and Split by channel (one pattern per sound, each on its own row). Clone pattern is also in the pattern's menu at the top.",
            ),
        ),
        Release(
            "0.7.9", "2026-11-13T15:45:00Z",
            listOf(
                "Fixed: single drums from SoundFont kits (like a closed hi-hat) played much lower than they should in the rack and piano roll, so they sounded like a different drum. They now sound as picked on their own note.",
                "Piano roll: when a whole drum kit is on one sound, each row shows which drum it plays.",
                "Piano roll: bar numbers at the top of each bar line.",
                "Piano roll: \"Loop bars\" repeats your notes in blocks of 4 bars. Notes in bars 1–2 copy bars 1–4 into 5–8; tap again for 9–12. The pattern gets longer if it needs to. Undo takes it back.",
                "Piano roll: \"Copy\" copies the notes of the sound you're on. Pick another sound in the row above and tap \"Paste\" to clone them there. Drum to drum, the beat lands on the new drum's own note. Undo works here too.",
            ),
        ),
        Release(
            "0.7.8", "2026-11-13T15:40:00Z",
            listOf(
                "Export: when Stems or MIDI are on, everything comes as one .zip (the mix, a stems folder and the .mid), saved to Download › Mobile DAW › <song name>. Share .zip sends it in one go.",
                "Projects: each saved project has a Delete button (tap the song name). It asks first. Deleting the open project opens your most recent other one, or a new empty project.",
                "Freesound: the \"Under 30 s\" chip is now a length filter. Tap it to pick Any length, Under 1 s, Under 5 s, Under 30 s, 30 s – 2 min or Over 2 min, or drag the two handles to set your own range.",
            ),
        ),
        Release(
            "0.7.7", "2026-11-13T15:20:00Z",
            listOf(
                "New effect: Soft Clipper. It rounds off loud peaks so a track or the whole song gets louder and punchier without harsh clipping. Three knobs: Gain (how hard the sound hits it), Threshold (where clipping starts) and Post (how loud it comes out).",
                "The Master in the Mixer now has its own effects, so you can put the Soft Clipper (or EQ) on the whole song. Effects on the master are in your exports too.",
                "To clip just your drums: add a mixer track, send the drum channels to it, and add the Soft Clipper there.",
            ),
        ),
        Release(
            "0.7.6", "2026-11-13T12:20:00Z",
            listOf(
                "Piano roll: new Undo button right of the play button. It takes back adding, moving, resizing and deleting notes, and chords you put in. Hold it to redo. It's greyed out when there's nothing to undo.",
            ),
        ),
        Release(
            "0.7.5", "2026-11-13T11:50:00Z",
            listOf(
                "Channel settings are the same for every drum: the built-in Kick, Snare, Hi-hat and Clap and single drums from SoundFont kits now get Trim, Pitch, Reverse, Boost, Normalize and the Envelope, just like sounds from your drum kits.",
                "Synths and instruments (Bass, Lead, Pad, Wavetable, SoundFont instruments...) now get Pitch and Boost in Channel settings too. Trim, Reverse and the Envelope stay for one-shot sounds.",
            ),
        ),
        Release(
            "0.7.4", "2026-11-13T11:25:00Z",
            listOf(
                "Rack: \"+ Add drums\" and \"+ Add sounds\" are now separate buttons. Drums opens on your kits with Kick, Snare, Hi-hat and Clap at the top; Sounds opens on Instruments with Bass, Lead, Pluck, Pad and Wavetable at the top.",
                "Tap a channel's name to open its channel settings. To change its sound, tap the new ⇄ icon next to the ⋯ menu.",
            ),
        ),
        Release(
            "0.7.3", "2026-11-13T11:20:00Z",
            listOf(
                "Pitch audio clips without changing their tempo: in the clip window (Pencil, tap a clip) pick Resample, Stretch or Vocal under Pitch.",
                "Stretch keeps the clip's length and timing at any pitch, for loops, beats or anything else. Vocal also keeps a singer's natural tone, so a lowered voice doesn't sound slowed down or deep.",
                "Resample works like before (lower is slower, like a turntable). The new pitch takes a moment to make on long takes; the clip plays its original sound until then.",
            ),
        ),
        Release(
            "0.7.2", "2026-11-13T11:00:00Z",
            listOf(
                "Drums follow the piano roll: put a hi-hat (or clap, or a single drum from a kit) on any note and it plays at that pitch, like a sample. G3 is an octave under G4: lower and longer, higher and shorter.",
                "Steps from the step sequencer still play the drum as it was (C4 for hi-hat, clap and samples). The channel's Pitch setting and Boost still apply on top.",
            ),
        ),
        Release(
            "0.7.1", "2026-11-13T10:40:00Z",
            listOf(
                "Recording timing: tap a recording on the playlist and use Timing (− / + for 5 ms, « / » for 25 ms) to slide your voice earlier or later until it sits on the beat. The clip stays where it is.",
                "Use for new takes (in that window) saves the fix for your earbuds, so the next takes land right. Also in Settings → Audio sync → Recording timing.",
                "Takes keep the count-in audio hidden before them, so they can be slid later as well as earlier.",
            ),
        ),
        Release(
            "0.7", "2026-11-13T04:00:00Z",
            listOf(
                "Export: Settings → Export (or tap the song name → Export). Pick Song (the whole playlist) or Pattern, then any of WAV (16/24-bit), MP3 (128/192/320 kbps), Stems and MIDI.",
                "Stems: one WAV per channel (recordings grouped per mixer track, e.g. Vocals), all exactly the same length so they line up at bar 1 in any DAW.",
                "Exports are rendered by the engine faster than real time with the same sound you hear (effects included, metronome never), and the last notes ring out at the end.",
                "Files are saved to Download › Mobile DAW › <song name>, and each one (or all of them) can be shared straight to another app.",
                "FL Studio: its .flp project format is closed, so use Stems + MIDI — drag the stems into FL's playlist and drop the .mid on a channel for the notes.",
            ),
        ),
        Release(
            "0.6.6", "2026-11-13T03:45:00Z",
            listOf(
                "Pitch Correct sounds much cleaner: notes are moved without the sped-up, chipmunk tone, so your voice keeps its own sound even on big jumps.",
                "Hard tune is truly hard now: at instant speed each note sits dead flat on the key and snaps to the next one, the classic tuned-vocal sound.",
                "Pick a sound with one tap: Natural, Classic, Hard tune, Double, Deep duet, Harmony and Choir. Harmony and Choir sing extra notes in the song's key.",
                "Simpler screen: Sound, Key and four knobs (Speed, Strength, Voices, Mix). Note switches, transpose, humanize and singer's range are under More options.",
                "New Pitch Correct effects start on Classic (fast, tight tuning).",
            ),
        ),
        Release(
            "0.6.5", "2026-11-12T23:40:00Z",
            listOf(
                "Piano roll: dragging a note now always resizes or moves it — only dragging empty space scrolls.",
                "Piano roll: the grid and note-label controls are now on one line, with the grid as a dropdown, leaving more room for the notes.",
            ),
        ),
        Release(
            "0.6.4", "2026-11-12T23:30:00Z",
            listOf(
                "Metronome: tap the metronome button at the top left to turn the click on or off. It clicks every beat at the project's tempo, with a higher click on beat 1 of each bar. Remembered.",
                "The click is made by the audio engine itself, so it stays exactly on the beat, and it is only in what you hear: it never ends up in your recordings' audio or a future export.",
                "Recording now counts in one full bar (1, 2, 3, 4, GO) with the click, and the take starts exactly on the next beat. With the metronome on, it keeps clicking while you record.",
            ),
        ),
        Release(
            "0.6.3", "2026-11-12T23:00:00Z",
            listOf(
                "Bluetooth sync: the playhead, step lights and meters now wait for the sound, so with earbuds what you see matches what you hear.",
                "Settings → Audio sync: Auto uses the delay the phone measures for your earbuds/speaker; a slider (and − / +) fine-tunes it. Remembered for each output.",
                "Recordings are lined up with that delay too, so a take lands where you actually sang it.",
            ),
        ),
        Release(
            "0.6.2", "2026-11-12T18:00:00Z",
            listOf(
                "Chord generator in the piano roll: tap Chords. It writes a progression in the project's key into the channel you're on (great with the Wavetable synth).",
                "Generate for a new progression; the slider goes from Conventional to Adventurous (borrowed chords, 7ths, sus, add9).",
                "Each chord: tap to hear it, lock it, reroll just that one (↻), or move its voicing up/down (▴ ▾).",
                "Set how many chords, the octave, ½/1/2 bars each, a bass note, and a rhythm (Hold, Beats, Arp). Keep writes them in, Cancel puts back what was there.",
            ),
        ),
        Release(
            "0.6.1", "2026-11-12T17:50:00Z",
            listOf(
                "Channel envelope: a row of knobs under the graph — delay, attack, hold, decay, sustain, release and tension (drag a knob up or down).",
                "Channel envelope: delay (silence before the sound) and tension (curve the attack/decay/release).",
                "Channel settings: the Trim waveform now flips when Reverse is on, matching what you hear.",
            ),
        ),
        Release(
            "0.6", "2026-11-12T17:45:00Z",
            listOf(
                "Mixer tracks: the Mixer now has a Vocals track with its own effects. Recordings play through it; add more tracks with + Track.",
                "Send any channel to a mixer track with its → Master chip in the Mixer.",
                "New Parametric EQ: up to 8 bands you drag on a graph over the live spectrum. Bell, shelves, cuts (12/24/48 dB) and notch.",
                "New Pitch Correct (auto-tune): pulls the voice to the song's key. Retune speed from instant (robotic) to natural, humanize, transpose.",
            ),
        ),
        Release(
            "0.5", "2026-11-12T17:40:00Z",
            listOf(
                "PAT / SONG moved from the top bar to the left of the play button.",
                "Record on the playlist: tap Record (right of play). The bar switches to a big play button and a big red record button.",
                "Tap the red button: 1, 2, 3, GO count-in, then it records the microphone. Tap again to stop. The take lands on the playlist at the playhead, on the first free track, with its waveform.",
                "Recordings play back in SONG mode, also when you start playing in the middle of one. Tap a recording to remove it (Undo is there for a few seconds).",
                "Close goes back to the normal play bar.",
                "Piano roll: fixed one-finger scroll — hold and drag now actually scrolls (Settings → Editing).",
            ),
        ),
        Release(
            "0.4.5", "2026-11-12T17:30:00Z",
            listOf(
                "Channel settings: a graphical envelope — drag the dots for attack, hold, decay, sustain and release.",
            ),
        ),
        Release(
            "0.4.4", "2026-11-12T17:20:00Z",
            listOf(
                "Channel settings: Pitch slider to move a sample up or down 12 semitones.",
                "Channel settings: Reverse toggle to play a sample backwards.",
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
                "Sound library (Settings → Sounds → Sound library): every free source in one place, each on its own page.",
                "General MIDI instruments built in: GeneralUser GS, 261 instruments and 13 drum kits (pianos, strings, brass, guitars, synths, every GM drum). Import your own .sf2/.sf3 too.",
                "Two new synth engines with 60+ presets: Macro (leads, keys, chords, pads, plucks, 808s) and Modeled (FM, plucked strings, bells, flute, sax, brass, bowed strings, sitar, shakers, analog drums).",
                "SFZ instruments: import a .zip with .sfz files and samples.",
                "Freesound search inside the app (CC0 by default), plus Wikimedia Commons and Internet Archive. Tap to preview, Save puts the sound in Drum kits with its credit.",
                "Credits & licences page with a Copy credits button for when you release a beat.",
                "The sound picker (tap a channel's name) has tabs: Kits, Instruments, Synths, SFZ, Freesound, Commons, Archive.",
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
