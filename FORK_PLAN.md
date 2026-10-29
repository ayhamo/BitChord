# BitChord fork: plan

Goal: BitChord with my Navidrome server as a library, plus the features from my psysonic PC fork.
Fork: github.com/ayhamo/BitChord (`origin`), upstream kushagrasinghx/BitChord (`upstream`).
Base: upstream `main` at `04a4a838` (2026-10-26).

## Rules (to keep upstream syncs cheap)

- New code goes in new files (`data/navidrome/…`, `fork/…`). Upstream files get only small hook lines.
- Every changed upstream line is listed in `FORK.md` (written once work starts), so conflicts are easy to redo.
- Upstream commits land daily; sync often, not in big jumps.
- Nothing is committed or pushed until I say so.

## What the code already gives us

| Need | Already in BitChord | Where |
|---|---|---|
| Remote library page (Songs / Artists / Albums) | WebDAV and SMB libraries. "A third library adds one line" | `ui/MainViewModel.kt` `remoteLibrary()` ~L2495, `data/webdav/WebDavRepository.kt` |
| Server settings card + Test button | WebDAV card | `MainActivity.kt` ~L5040 |
| Real crossfade (2 ExoPlayers) | `CrossfadeController.kt`, automix too | `playback/` |
| Manual skips **don't** crossfade | `onSkipRequested()` bails on purpose, called 4× | `CrossfadeController.kt:729`, `PlaybackService.kt` 5785/7819/7850/7861 |
| Video backdrops | Canvas chain: Apple → Tidal → Community → Spotify, with cache | `data/canvas/CanvasRepository.kt` `canvasFor()` |
| Synced lyrics | `LyricsRepository` source list (LRCLIB, KuGou, Genius…) + embedded lyrics for local files | `shared/…/lyrics/LyricsRepository.kt` ~L208 |
| Updater | GitHub latest release | `data/AppUpdateChecker.kt:46` (points at upstream) |
| FFmpeg | Prebuilt `libffmpegJNI.so` committed in `app/src/main/jniLibs` (no private parts) | |

## Phase 0: build in Docker (first thing tomorrow)

- Image: `eclipse-temurin:17` + Android SDK `platforms;android-37.0`, build-tools, **NDK + CMake 3.22.1** (native analyzer/DSD libs are built with CMake). Gradle 8.11.1 via wrapper.
- Volumes for Gradle and SDK caches; Docker disk is on D:. APKs go to `D:\Programs\BitChord\out`.
- Build `assembleDevDebug`: dev flavor is `com.dev.bitchord` ("BitChord Dev"), so it installs next to anything else. ABI splits give an x86_64 APK for the LDPlayer emulator.
- Signing: our key in `docker/keys` (gitignored; `*.jks` is already ignored upstream). `local.properties` Last.fm keys can stay empty for now.
- Fix CRLF on `gradlew` inside the container, like before.
- Done when: the dev APK installs on `emulator-5558` and plays a YouTube Music song.

## Phase 1: Navidrome library (core)

New files in `data/navidrome/`:
- `NavidromeConfig`: URL, user, password stored like the WebDAV fields; Subsonic token auth (`t` = md5(password+salt), `s` = salt). `BROWSE_ID`.
- `NavidromeClient`: `ping`, `getOpenSubsonicExtensions`, `search3` paged with an empty query (whole library), `getAlbumList2`/`getAlbum`, `stream` URL with `format=raw`, `getCoverArt`.
- `NavidromeRepository.getSongs()`: maps each song to `Song` with title/artist/album from **tags** (not folders), `albumId`, duration, `localUri` = stream URL, `thumbnailUrl` = the **song's own** cover (`mf-` id): feature 1 for free.

Hooks in upstream files: one line in `remoteLibrary()`, a settings card copied from WebDAV, a few `AppSettings` fields and strings.
Done when: the Navidrome page lists my library, covers are per song, FLAC 24/96 and Opus play raw, and seeking works.

Later in this phase, if wanted: `NavidromeSource : MusicSource` so Navidrome results show up in normal search.

## Phase 2: crossfade on skip (+ lyrics stay in sync)

- Settings switch "Crossfade on skip" (off = upstream behaviour).
- When on, Next/Prev/tap-a-song starts a short blend through the controller instead of `bail()`. Needs a new entry point that arms the standby player on the chosen track immediately (today it's only armed near a track's end). This is the biggest unknown; read the arm/handoff code before estimating.
- Check that lyrics and position follow the incoming track during and after the blend.

## Phase 3: lyrics from Navidrome

- Add a Navidrome lyrics source: `getLyricsBySongId` (synced) when the server reports the `songLyrics` extension, else `getLyrics`. Tried first for Navidrome songs, then the existing chain.
- Done: `NavidromeLyrics`, reached through `EmbeddedLyrics.forUri`. Only timed lyrics are used; plain text from the server (and `getLyrics`, which is plain only) falls through to the online sources so they can find a synced copy. My library's files carry plain lyrics at most (e.g. "Born to Rise": 98 unsynced lines), so on the device the online chain still answers.

## Phase 4: covers

- Animated covers: add Coil's animated decoder (`coil-gif`, `AnimatedImageDecoder` on API 28+, minSdk is 26). Animate at player sizes only; keep list thumbnails still.
- Grey-star artist placeholder: check what BitChord shows for artists with Navidrome's placeholder; use the song cover instead if needed.

## Phase 5: video backdrops from my server

- `NavidromeClipCanvas`: first provider in `canvasFor()` for Navidrome songs. Fetches `/rest/x-clip/<folder>/<file>.background.mp4` with the `X-ND-Authorization` JWT (from Navidrome `/auth/login`).
- Reuse BitChord's canvas cache and player instead of building our own.
- Blocked on the server route (same as the psysonic fork, item 10).

## Phase 6: smaller extras

- Reverse album order (`#` header, remembered per album).
- Scrobble plays to Navidrome (`scrobble` endpoint), star/unstar sync, Navidrome playlists.
- Skipped: Discord covers (BitChord already has its own Discord presence; check later), "big albums open faster" (probably not needed on Android).

## Phase 7: updates + CI

- `AppUpdateChecker` → `ayhamo/BitChord` releases.
- GitHub workflow: daily merge of upstream `main` (or its releases), build, sign with secrets, publish `fork-vX.Y.Z`. Reuse the psysonic fork workflow's approach. On conflict: fail and tell me, I fix it with Claude.

## Open questions for tomorrow

1. Package id for my builds: keep `com.music.bitchord` (can't update an upstream install anyway, signatures differ) or use my own id so both can sit side by side?
2. Follow upstream `main` daily, or only upstream **releases** (calmer)?
3. Is the `x-clip` server route done, or still pending (decides when Phase 5 starts)?
