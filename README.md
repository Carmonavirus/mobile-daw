# mobile-daw

A minimal, portrait-only music maker for Android. The workflow borrows the familiar
playlist / channel rack / piano roll / mixer layout, with its own light, paper-like design.

**Web version:** https://carmonavirus.github.io/mobile-daw/ runs in any modern browser (phone or
computer) with the same sound engine, compiled to WebAssembly. See [web/README.md](web/README.md).

## What's in it

| Screen | What you do there |
| --- | --- |
| **Rack** | One bar of 16 steps per channel; use the arrows (or dots) to move between bars, and `+` on the last bar to make the pattern longer (up to 16 bars). Tap a drum channel's name to pick a sound from your kits, long-press it for more. |
| **Piano** | Edit the selected channel's notes. Tap to draw, tap a note to delete it, drag a note to move it, drag its right end to resize, pinch to zoom, drag empty space to scroll. Other channels show as faint ghost notes. |
| **Playlist** | Arrange patterns. Time runs left to right (one column per bar) across eight tracks. |
| **Mixer** | Volume, pan, mute and solo per channel, plus the master level. |

The header shows the project name (tap it to open, rename or start projects), tempo and key
(tap to change), a `PAT`/`SONG` switch (loop the current pattern or play the arrangement), and play/stop.

Projects are saved automatically as JSON in the app's private storage.

## Instruments

Built in (synthesised, no sample files): kick, snare, hi-hat, clap, bass, lead, pluck and pad.

### Your own drum kits

Open **Settings (≡) → Drum kits** and tap **Import .zip** (or **Import folder** for a kit you
already unzipped). Each import is added as a new kit; existing kits are never replaced. Only WAV
files are imported, keeping their folders (Kicks, Snares, 808s...).

In the **Rack**, tap a drum channel's name to choose its sound: every kit and folder is listed,
with folders matching the channel (e.g. "Kick" → Kicks) already open. Tap a sound to hear it and
**Use** to pick it. **+ Add sound** below the channels adds a kit sound as a new channel. Sample
channels follow the piano roll too: C4 plays the sound as-is, other notes pitch it.

### Pattern templates

Tap the selected pattern chip and choose **Save as template** to keep a pattern (say a basic trap
beat) together with the sounds it uses. Later, in any project, tap **+** next to the patterns and
pick the template: it is added as a new pattern, reusing matching channels or adding missing ones.

## Architecture

```
app/src/main/cpp/          C++ audio engine
  Synth.h                  instrument voices
  DawCore.{h,cpp}          sequencer, voice allocation, mixer, meters (no Android deps)
  AudioEngine.{h,cpp}      Oboe low-latency output stream
  jni_bridge.cpp           JNI entry points
app/src/main/java/com/mobiledaw/
  audio/NativeEngine.kt    Kotlin side of the JNI bridge
  model/                   Project data, demo song, JSON store, sequence compiler
  DawViewModel.kt          app state; pushes changes to the engine
  ui/                      Jetpack Compose screens and components
engine-test/               desktop smoke test for DawCore (renders a WAV)
```

The UI owns the song. On every edit it flattens the current pattern (or the whole playlist in
song mode) into a sorted note list and hands it to the engine, which swaps it in lock-free
on the audio thread.

## Install on your phone

Open this link on the phone (signed in to GitHub, since the repo is private):
https://github.com/Carmonavirus/mobile-daw/releases/download/latest-build/mobile-daw.apk

CI replaces that file on every push, so the link always gives the newest build.

## Building

- Android Studio Ladybug or newer, Android SDK 35, NDK (installed automatically by Gradle).
- `./gradlew assembleDebug`, then install `app/build/outputs/apk/debug/app-debug.apk`.
- Every push builds a debug APK in GitHub Actions; download it from the run's **Artifacts**.

Engine test on any machine with g++:

```
mkdir -p engine-test/out
g++ -std=c++17 -O2 -Iapp/src/main/cpp engine-test/main.cpp app/src/main/cpp/DawCore.cpp -o engine-test/out/enginetest
cd engine-test && ./out/enginetest   # writes out/render.wav
```

## Not yet

Audio recording, effects, WAV export, MP3 samples and MIDI keyboards.
