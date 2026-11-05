# Music Trainer

Sight-reading trainer for MIDI keyboards: Windows, Linux and Android (Kotlin Multiplatform + Compose).

- One note at a time on a staff; the clef switches treble ↔ bass every *N* notes (default 10).
- Random notes from a configurable range (default 61 keys, C2–C7), optionally with sharps/flats.
- Waits for a key on your MIDI keyboard. A correct key gets a green pop animation; a wrong key
  turns the note red, shows its name above the staff and shows what you played.
- Score: ✓ correct, ✗ wrong, accuracy, and points (`10 × correct − 5 × wrong`, never negative).
- **Restart session** resets the score (an unfinished session with answers is still logged).
- History of every session with date, score and settings, plus trend charts for score, accuracy
  and response time.
- MIDI status bar: connected devices (hot-plug, rescans automatically) and your keyboard's size.
  MIDI doesn't report key count, so choose 25/37/49/61/76/88 or detect it by pressing your lowest
  and highest keys.
- On-screen keyboard (tap or click) that plays real piano samples at the exact pitch. When the keys
  would be too narrow (e.g. phone in portrait) it splits into two stacked rows.

## Install

Installers are built into `dist/`:

| Platform | File | How to install |
|---|---|---|
| Windows 10/11 (x64) | `MusicTrainer-1.0.0.msi` | Double-click. Installs per user (no admin), adds Start-menu and desktop shortcuts. |
| Debian / Ubuntu / Mint (x64) | `music-trainer_1.0.0_amd64.deb` | `sudo apt install ./music-trainer_1.0.0_amd64.deb`, then start "Music Trainer" from the menu (or `/opt/music-trainer/bin/MusicTrainer`). |
| Other Linux (x64) | `MusicTrainer-1.0.0-linux-x64.tar.gz` | Extract anywhere and run `MusicTrainer/bin/MusicTrainer`. |
| Android 10+ | `MusicTrainer-1.0.0-android.apk` | Copy to the phone and open it (allow "install unknown apps"). |

All desktop packages bundle their own Java runtime. The APK is signed with the debug key, which is fine
for side-loading but not for the Play Store.

Rebuilding: `./gradlew :shared:packageMsi` on Windows, `./gradlew :shared:packageDeb` on Linux (jpackage can only
build installers for the OS it runs on), `./gradlew :androidApp:assembleRelease` for the APK.

## Run

Requires JDK 17+ (the Gradle wrapper is included).

```sh
./gradlew :shared:run                     # desktop app
./gradlew :shared:desktopTest             # unit tests
./gradlew :androidApp:assembleDebug       # Android APK -> androidApp/build/outputs/apk/debug/
./gradlew :shared:packageDistributionForCurrentOS   # installer: .msi on Windows, .deb/.rpm on Linux
```

Android needs the Android SDK (set `sdk.dir` in `local.properties` or `ANDROID_HOME`). Connect a USB MIDI
keyboard through an OTG adapter.

Data is stored as JSON in `~/.music-trainer/` on desktop (override with the `MUSIC_TRAINER_HOME`
environment variable) and in the app's private storage on Android.

## Project layout

- `shared/src/commonMain` – UI, staff rendering, session logic, storage, piano sample player
- `shared/src/desktopMain` – desktop entry point, `javax.sound.midi` input, Java Sound output
- `androidApp` – Android activity, `android.media.midi` input, AudioTrack output

## Credits

- Music font: [Bravura](https://github.com/steinbergmedia/bravura) by Steinberg, SIL Open Font License
  (`licenses/Bravura-OFL.txt`).
- Piano samples: [Salamander Grand Piano](https://archive.org/details/SalamanderGrandPianoV3) by
  Alexander Holm, CC-BY 3.0 (via the Tone.js sample set), converted to mono WAV.
