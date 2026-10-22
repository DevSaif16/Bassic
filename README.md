# Bassic

Bassic is an Android app that lets you feel the bass of whatever your phone is playing. It listens to the device's audio output, detects kick and bass hits in real time, and turns them into vibrations, so music from Spotify, YouTube Music or any other app comes with haptic feedback that follows the beat.

## How it works

1. **Audio tap.** A foreground service (`HapticSyncService`) attaches an `android.media.audiofx.Visualizer` to the global output mix (audio session 0). Unlike MediaProjection playback capture, this needs no screen-sharing consent and also sees apps that opt out of playback capture. The microphone is never used for recording; the `RECORD_AUDIO` permission is what Android requires for the Visualizer.
2. **Bass extraction.** On a dedicated `HandlerThread` the service polls the Visualizer's FFT every 12 ms, using the largest capture size for the finest low-frequency resolution and normalized scaling so detection does not depend on the volume level. It sums the magnitudes of the 30-160 Hz bins into a single bass energy value.
3. **Onset detection.** Energy is compared against an exponential moving average and variance (about one second of memory). A hit fires when the energy is rising and clears both a standard-deviation threshold and a ratio threshold, both tuned by the Sensitivity setting, with a silence floor and a 90 ms minimum gap between hits. Hit strength is measured relative to a slowly decaying peak, so it adapts to each song's dynamics.
4. **Haptic shaping.** `HapticShaper` turns each hit into a vibration, with two strategies depending on the motor:
   - **Motors with amplitude control** get a real envelope: a 10-40 ms solid punch followed by an exponential tail in 10 ms frames, played with `VibrationEffect.createWaveform`. The Flow setting sets the tail length.
   - **On/off-only motors** (many phones expose only these to apps) never get chopped on and off, which feels like a buzz. Each hit is one unbroken 10-50 ms push whose length carries the strength, and sustained bass keeps the motor running in 40 ms steps (up to 350 ms, depending on Flow) for as long as the note holds.

   On Android 13+ vibrations are tagged with `VibrationAttributes.USAGE_MEDIA`.

The service exposes its state (running, live bass level, hit count) as Kotlin `StateFlow`s, which the UI collects.

## Features

- Works with any app that plays audio, with no screen sharing.
- Sensitivity, Strength and Flow (tight / smooth / rolling) controls, saved between sessions.
- Optional auto-play: sends a media Play key on start, so the player in the notification shade resumes.
- Runs in the background as a foreground service, with a Stop action in its notification.
- Jetpack Compose UI: a ring that breathes with the bass and ripples on every hit, a dot-matrix level meter and a live hit counter.

## Tech stack

- Kotlin, Jetpack Compose (Material 3, Compose BOM 2026.02.01)
- Android Gradle Plugin 9.4.1, Gradle 9.6.0 (wrapper), Kotlin 2.2.10
- `minSdk` 31 (Android 12), `targetSdk` / `compileSdk` 37
- Android APIs: `Visualizer`, `Vibrator` / `VibratorManager`, `VibrationEffect`, foreground services (`microphone` type), `AudioManager`

## Permissions

| Permission | Why |
| --- | --- |
| `RECORD_AUDIO` | Required by Android to attach a `Visualizer` to the output mix |
| `MODIFY_AUDIO_SETTINGS` | Required for a `Visualizer` on the global output mix (session 0) |
| `VIBRATE` | Haptic pulses |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | Keeps analysis running in the background |
| `POST_NOTIFICATIONS` | The ongoing notification with the Stop action |

## Building

Requirements:

- Android Studio with Android SDK platform 37 installed
- A JDK for Gradle (the project pins the Gradle daemon toolchain to JDK 25, which Gradle can provision automatically)
- A physical device running Android 12 or later with a vibration motor; emulators do not provide meaningful haptics

Open the project in Android Studio and run the `app` configuration, or from the command line:

```bash
./gradlew assembleDebug        # build the debug APK
./gradlew installDebug         # install it on a connected device
./gradlew test                 # run the HapticShaper unit tests
```

The debug APK is written to `app/build/outputs/apk/debug/`.

## Usage

1. Start music in any app.
2. Open Bassic and tap **START**, granting the audio and notification permissions when asked.
3. Tune Sensitivity, Strength and Flow to taste. Stop it from the app or from its notification.

## Project structure

```
app/src/main/java/com/android/bsmc/bassic/
  MainActivity.kt       Compose UI, permissions, settings persistence
  HapticSyncService.kt  Foreground service: Visualizer capture, bass onset detection, vibration
  HapticShaper.kt       Pure functions that turn hit strength into vibration timings and envelopes
app/src/test/.../HapticShaperTest.kt  Unit tests for the haptic shaping
```

## Author

DevSaif16
