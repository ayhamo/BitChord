# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## This checkout is a personal fork

`origin` = ayhamo/BitChord (fork), `upstream` = kushagrasinghx/BitChord. The fork adds a Navidrome (Subsonic) library and features ported from the owner's psysonic PC fork; the phased plan is in `FORK_PLAN.md`.

- Upstream commits land daily. Keep fork code in **new files** (e.g. `data/navidrome/…`) and limit edits to upstream files to small hook lines, so `git merge upstream/main` stays cheap. List every touched upstream line in `FORK.md`.
- Commit or push only when the owner says so, and never add Co-Authored-By / "Generated with" lines.
- The upstream `CONTRIBUTING.md` branch rules (branch from the current release branch, open issues first) apply to PRs sent upstream, not to fork work.

## Build and test

Development happens on Windows with no local JDK/SDK; builds run in Docker (`docker/`):

```powershell
.\docker\build.ps1               # builds the image, then assembleDevDebug; APKs land in out\ (repo root, excluded via .git/info/exclude; delete them when a round of work is done)
.\docker\build.ps1 -NoImage      # reuse the existing image
.\docker\build.ps1 -NoImage -Task testDevDebugUnitTest
```

- The image is JDK 17 + SDK `platforms;android-37.0`, build-tools 35, NDK 27, CMake 3.22.1 **and `platform-tools`**. AGP 8.10.1 cannot resolve compileSdk 37 without platform-tools ("Failed to find target with hash string 'android-37'").
- `-Pbitchord.bundleWear=false` is passed so the phone build doesn't also build `:wearos`.
- `gradlew` is checked out with CRLF; the build script strips it inside the container.
- The debug signing key lives in `docker/keys` (gitignored, mounted as `/root/.android`) so reinstalls keep working.
- In PowerShell, avoid passing scripts with embedded double quotes to `docker run` (5.1 strips them); use Git Bash with `MSYS_NO_PATHCONV=1` for one-off container commands.

Plain Gradle (as in upstream CI, `.github/workflows/check.yml`):

```bash
./gradlew assembleDevDebug
./gradlew testDevDebugUnitTest
./gradlew testDevDebugUnitTest --tests "com.music.bitchord.playback.audio.DirectAudioStreamingRegressionTest"
```

`:app`, `:tv` and `:wearos` are only included when an Android SDK is found (`local.properties` `sdk.dir`, or `ANDROID_HOME`); otherwise only the KMP modules configure.

Flavors: `dev` (`com.dev.bitchord`, "BitChord Dev", installs beside prod) and `prod` (`com.music.bitchord`). Release signing reads `keystore.properties` + the `.jks` (both gitignored); Last.fm keys and the Listen Together server come from `local.properties` and may be empty.

Testing on device: LDPlayer emulator `emulator-5558` (Android 14, x86_64), adb at `D:\LDPlayer\LDPlayer14\adb.exe`. ABI splits produce an x86_64 APK for it.

## Architecture

Modules:
- `shared` (Kotlin Multiplatform, android + jvm): models (`data/model/Models.kt`, `Song` is the most connected type in the codebase), lyrics providers and `LyricsRepository`, InnerTube client/parser, automix planning (`playback/smart/TransitionPlanner`).
- `sharedUi` (Compose Multiplatform): the player UI (`ui/player/…`: Now Playing, lyrics, queue, backdrops). It is shared with a desktop target; platform specifics come in through `PlayerHost`/`PlayerPlatform.install`.
- `app`: the Android app. `MainActivity.kt` (~5.6k lines, hosts most screens and settings cards) and `ui/MainViewModel.kt` (~3.4k lines) are the central UI/state hubs; `playback/PlaybackService.kt` (~9k lines) is the Media3 session service.
- `tv`, `wearos`: separate apps. `web/` (React/Vite + extension) and `backend/` (Go Listen Together server) are not part of the Android build.

Key flows to know before changing things:
- **Playback** is Media3/ExoPlayer inside `PlaybackService`. `CrossfadeController` runs **two peer ExoPlayers** that swap roles at each transition: the standby is loaded with the queue positioned on the *incoming* track and the session is handed over mid-blend. Manual skips deliberately cancel crossfade via `onSkipRequested()` (called from 4 places in `PlaybackService`).
- **Sources**: YouTube Music (InnerTube) is the core catalogue (home, radio, related). `data/sources/MusicSource` is a deliberately small interface (search + stream) for extra catalogues (addons, JS "modules", JioSaavn) resolved by `SourceRegistry`/`SourceResolver`, mainly to find better-quality streams for a track.
- **Remote file libraries** (WebDAV, SMB) are separate from `MusicSource`: a repository returns `List<Song>` (with `localUri` = stream URL) and is registered in `MainViewModel.remoteLibrary()`, which drives the shared Songs/Artists/Albums pages. This is the hook the Navidrome library uses.
- **Canvas** (looping video behind the player): `data/canvas/CanvasRepository.canvasFor()` tries providers in order (Apple Music, Tidal, Community, Spotify) with its own cache.
- **Settings** are `StateFlow`s on the `AppSettings` object; UI and service both collect them.
- Native code: `app/src/main/cpp` (CMake: audio analysis, DSD decoders). `libffmpegJNI.so` is prebuilt and committed under `app/src/main/jniLibs` (built by `native/ffmpeg/build.sh` from librempeg).

## Conventions from the codebase

- Code comments explain *why* in long-form KDoc, often recording measured bugs that a line fixes; don't "simplify" code that looks redundant without reading its comment.
- For audio changes, upstream asks that app telemetry not be presented as proof of hardware behaviour; distinguish source format, decoder format, DSP format, AudioTrack output and device capability.
- GPLv3: new dependencies must be GPLv3-compatible.

## Code graph

`graphify-out/` (locally git-excluded) holds a graphify knowledge graph of `app`, `shared` and `sharedUi`. For "what calls X / how does Y flow" questions use `graphify query "<question>"` before grepping widely; rebuild with `/graphify . --update` after large upstream merges.
