# mobile-daw

A minimal, portrait-only music maker for Android. The workflow borrows the familiar
playlist / channel rack / piano roll / mixer layout, with its own light, paper-like design.

## What's in it

| Screen | What you do there |
| --- | --- |
| **Rack** | Each channel gets a 16-step row per bar. Tap steps to toggle them, tap a channel name to hear it, long-press it for more. |
| **Piano** | Edit the selected channel's notes. Tap to draw, tap a note to delete it, drag a note to move it, drag its right end to resize, pinch to zoom, drag empty space to scroll. Other channels show as faint ghost notes. |
| **Playlist** | Arrange patterns. Time runs down the screen (one row per bar), four lanes across. |
| **Mixer** | Volume, pan, mute and solo per channel, plus the master level. |

The header shows the project name (tap it to open, rename or start projects), tempo and key
(tap to change), a `PAT`/`SONG` switch (loop the current pattern or play the arrangement), and play/stop.

Projects are saved automatically as JSON in the app's private storage.

## Instruments

All sounds are synthesised, so there are no sample files: kick, snare, hi-hat, clap,
bass, lead, pluck and pad.

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

Audio recording, effects, WAV export, sample import and MIDI keyboards.
