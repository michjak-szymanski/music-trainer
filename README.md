# Music Trainer

Sight-reading trainer for MIDI keyboards: Windows, Linux and Android (Kotlin Multiplatform + Compose).

- One note at a time on a staff; the clef switches treble ↔ bass every *N* notes (default 10).
- Random notes from a configurable range (default 61 keys, C2–C7), optionally with sharps/flats.
- Waits for a key on your MIDI keyboard. A correct key gets a green pop animation; a wrong key
  turns the note red, shows its name above the staff and shows what you played.
- Score: ✓ correct, ✗ wrong, accuracy, and points (`10 × correct − 5 × wrong`, never negative).
- A quick 1–5 star rating on the results card and in History (95%+ = 5, 85%+ = 4, 70%+ = 3, 50%+ = 2).
- **Restart session** resets the score (an unfinished session with answers is still logged).
- History of every session with date, score and settings, plus trend charts for score, accuracy
  and response time.
- MIDI status bar: connected devices (hot-plug, rescans automatically) and your keyboard's size.
  MIDI doesn't report key count, so choose 25/37/49/61/76/88 or detect it by pressing your lowest
  and highest keys.
- On-screen keyboard (tap or click) that plays real piano samples at the exact pitch. Drag the handle above
  it to resize (double-click the handle for automatic size). When the keys would be too narrow (e.g. phone
  in portrait) it splits into two stacked rows.
  The bottom bar (and Settings) can hide the on-screen keyboard, change its size (Auto = practice range, or
  25–88 keys) and switch note names and octave numbers on the keys (both off by default).
- How long the correct answer stays up after a mistake is adjustable (default 1 s); number settings can be
  typed in directly.
- Animated UI: notes slide in, wrong notes shake, points float up, counters roll, the results card pops in
  with the score counting up, and screens crossfade.
- The staff scrolls right to left: answered notes stay visible (green = correct, red = wrong with its name),
  with clef-change marks where the clef switched.
  A translucent vertical stripe marks the current note (opacity adjustable in Settings, 0 = off).
- Settings → General (both modes): theme (System / Light / Dark), sound output, Claude/MCP connection.
- Notation style per mode: music font (Bravura engraved, Leland, Petaluma handwritten), sheet colours
  (theme, paper, high contrast) and "mix it up" (random look each session) to train reading over pattern
  recognition. On Windows the title bar follows the theme.

## Note player (mode picker in the top-left corner)

- **Library:** MIDI (.mid/.midi/.kar) and MuseScore (.mscz/.mscx, MuseScore 3–4) files found in the folders
  chosen in Settings (scanned automatically, subfolders included) plus built-in demo pieces, with title, file
  type, duration and note count, and a search box.
- **MuseScore files** are read as notation, so the sheet is exact: note values, tuplets, voices, spelling and
  accidentals as engraved, ties, clef/key/time changes, pickup bars. The piano part (or the first two-staff part)
  becomes the grand staff.
- **Piece page:** play with both hands, right hand, left hand, or no hands (the app plays it); tempo defaults to
  the file's and can be changed; Follow tempo (play each chord in time) or Wait for me; past sessions for the
  piece with scores, errors, stars and an accuracy chart.
- **Sheet:** grand staff engraved from the MIDI file: timing quantized to 16ths, notes split into tied values by
  beat (half-bar rule in 4/4, compound meters), rests aligned to the beat, beams per beat (half bar for plain
  eighths in 4/4) with 16th beams and beamlets, stem directions, seconds in chords, accidentals per bar against
  the key signature (key estimated if the file has none), ties across barlines and systems. Hands come from two
  tracks (higher = right hand) or a split at middle C.
- Click anywhere on the sheet to start (or continue) from that bar; "⏮ From the beginning" restarts the piece.
- **Wait for me** (default): the cursor waits for the right keys, and also checks rhythm (time between chords at the
  chosen tempo) and how long notes are held; timing mistakes are shown in amber and counted separately.
  **Follow tempo** moves the cursor by itself. Zoom the sheet with − / +; the zoom is kept for all pieces.
- **Recording:** switch on "● Record" on the sheet to save what you play (plus the notes the app plays); the piece
  page's Recordings tab lists and plays them back.
- Notes of the hand you don't play, and everything in No hands mode, sound through the app piano or a MIDI
  output device (Settings).
- Own History (all pieces, with charts) and Settings, separate from the Note feeder.
- MIDI files: tuplets, several voices in one hand and key changes can't be recovered from MIDI (use MuseScore
  files for those). Not yet: MusicXML import, triplets nested inside triplets.

## Connect Claude (MCP server, desktop)

Settings → "Connect Claude (MCP server)" starts a read-only MCP server on `http://127.0.0.1:7432/mcp`
(local only). Add it to Claude Code with:

```sh
claude mcp add --transport http music-trainer http://127.0.0.1:7432/mcp
```

Tools: `list_pieces`, `get_session_history` (Note player and Note feeder), `list_recordings`, `get_recording`
(events or a base64 MIDI file).

## Install

Installers are built into `dist/`:

| Platform | File | How to install |
|---|---|---|
| Windows 10/11 (x64) | `MusicTrainer-1.9.3.msi` | Double-click. Installs per user (no admin), adds Start-menu and desktop shortcuts; the last page offers "Launch Music Trainer". |
| Debian / Ubuntu / Mint (x64) | `music-trainer_1.9.3_amd64.deb` | `sudo apt install ./music-trainer_1.9.3_amd64.deb`, then start "Music Trainer" from the menu (or `/opt/music-trainer/bin/MusicTrainer`). |
| Other Linux (x64) | `MusicTrainer-1.9.3-linux-x64.tar.gz` | Extract anywhere and run `MusicTrainer/bin/MusicTrainer`. |
| Android 10+ | `MusicTrainer-1.9.3-android.apk` | Copy to the phone and open it (allow "install unknown apps"). |

**Updating:** just install the newer file over the old one. Windows replaces the installed version automatically
(your settings and history are kept), `apt install` upgrades the .deb, and Android updates the app in place.

All desktop packages bundle their own Java runtime. The APK is signed with the debug key, which is fine
for side-loading but not for the Play Store.

`packageMsi` also runs `packaging/windows/add-launch-option.ps1`, which adds the launch checkbox to the MSI.

Rebuilding: bump `app.version` in `gradle.properties` first (Windows and Android only upgrade to a higher version), then `./gradlew :shared:packageMsi` on Windows, `./gradlew :shared:packageDeb` on Linux (jpackage can only
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

- Music fonts (SIL Open Font License): [Bravura](https://github.com/steinbergmedia/bravura) and
  [Petaluma](https://github.com/steinbergmedia/petaluma) by Steinberg, [Leland](https://github.com/MuseScore/MuseScore/tree/master/fonts/leland)
  by MuseScore (`licenses/`).
- Piano samples: [Salamander Grand Piano V3](https://freepats.zenvoid.org/Piano/acoustic-grand-piano.html) by
  Alexander Holm, CC-BY 3.0. One velocity layer (v11) of the original 44.1 kHz / 16-bit recordings, every
  minor third, mixed to mono, trimmed and normalized; other keys are repitched from the nearest sample.
