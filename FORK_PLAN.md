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
- Volumes for Gradle and SDK caches; Docker disk is on D:. APKs go to `out\` in the repo (locally git-excluded).
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

## Phase 3b: Navidrome without YouTube side effects, Home shelves

- Done: no YouTube read-ahead, links or radio for Navidrome songs; AutoPlay after one comes from the library (`getSimilarSongs2`, else `getRandomSongs`).
- Done: Navidrome album shelves on top of Home, YouTube's feed below. Not done: a setting to hide them.
- Later (YouTube side, after Navidrome is solid): Explore, and the same fixes for WebDAV/SMB songs if wanted.

## Phase 4: covers

- Animated covers: add Coil's animated decoder (`coil-gif`, `AnimatedImageDecoder` on API 28+, minSdk is 26). Animate at player sizes only; keep list thumbnails still.
- Grey-star artist placeholder: check what BitChord shows for artists with Navidrome's placeholder; use the song cover instead if needed.
- Changed: animated covers are now the server's square `.cover.mp4` clips (as in psysonic), not WebP inside the songs. The cover square shows the cover clip, else Apple/Tidal/Community motion art, else the still cover. The WebP decoder and `coil-gif` were removed again; Navidrome cover URLs stay sized per surface through `artworkAt`.
- Star: not needed. BitChord never loads Navidrome artist images (artist pages show its own person placeholder, the backdrop uses the song cover), so the star can't appear.

## Phase 5: video backdrops from my server

- `NavidromeClipCanvas`: first provider in `canvasFor()` for Navidrome songs. Fetches `/rest/x-clip/<folder>/<file>.background.mp4` with the `X-ND-Authorization` JWT (from Navidrome `/auth/login`).
- Reuse BitChord's canvas cache and player instead of building our own.
- `NavidromeClips` (login, path lookup, x-clip URLs, miss notes) is done and serves the cover clips.
- Done: `.background.mp4` backdrops (9:16) use upstream's Spotify Canvas presentation on phones (full screen, controls fade after 5 s), with the cover drawn back on top while paused. A song with a backdrop shows it instead of its cover clip (one clip plays at a time). On wide windows (tablets, the emulator at 320 dpi) upstream keeps clips in the sleeve.

## Phase 6: smaller extras

- Reverse album order (`#` header, remembered per album).
- Scrobble plays to Navidrome (`scrobble` endpoint), star/unstar sync, Navidrome playlists.
- Done: scrobbling ("Report plays to Navidrome", on by default). All fork switches are also in a "Fork settings" group before Advanced.
- Done: star sync (the player's and the notification's star are the server's star), Navidrome playlists (Home shelf, pages in the playlist's order).
- Reverse album: already upstream. Every album/playlist page's sort menu has "Date added" with an arrow that flips the running order, remembered per page, and Play follows it. No fork code.
- Skipped: Discord covers (BitChord already has its own Discord presence; check later), "big albums open faster" (probably not needed on Android).

## Phase 7: updates + CI

- `AppUpdateChecker` → `ayhamo/BitChord` releases.
- GitHub workflow: daily merge of upstream `main` (or its releases), build, sign with secrets, publish `fork-vX.Y.Z`. Reuse the psysonic fork workflow's approach. On conflict: fail and tell me, I fix it with Claude.

- Done: own package `com.ayhamo.bitchord` ("BitChord Fork"), own release key; the workflow builds `assembleProdRelease`. Push / first release only when I say so.

## Phase 8: offline, caches, YouTube

- Done: "Available offline" for Navidrome albums and playlists (whole pages, original files in `files/navidrome_offline`, never evicted, play and open with no server; Library row).
- Done: Navidrome audio stays in the song cache after play (it was never dropped: only addon audio is); clip cache size + "clear backdrops" / "clear cover clips"; upstream already has the song cache size and "clear image cache".
- Done and tested: Navidrome plays into the YouTube Music history ("Navidrome plays in YouTube history", off by default, needs a YouTube sign-in). Strict match per song, then upstream's PlaybackTracker.
- Done and tested: offline downloads in a foreground service with a notification (2 files at a time), Wi-Fi always used, "Download over mobile data" switch (also for pre-loading).
- Done and tested: offline pages keep covers (1200 px) and timed lyrics (server's, else online) on disk; clips are kept too (not yet tried on an album that has clips). Size shown before keeping ("803 MB to download") and after ("Kept · 145 MB").
- Done: lyrics cache for Navidrome songs (what showed last time, server's or online) + "Clear lyrics cache".
- Done and tested: "Pre-load next songs" (Off/1/2/3/5, Navidrome only); seen completing the next FLAC song in Youtube FLAC.
- Done and tested: "Offline mode" (switch, or no network): Library, Home, search, artist/album pages show only kept music; server rows, Explore genres and Internet radio hidden; a strip above the mini player says so, with "Go online".
- Done and tested: "Available offline storage" in Settings: total size, list with Remove / Remove all.
- Done: the full-screen clip waits 2.5 s on a new track, then fades in over 1.2 s.
- Next ideas:
  - Done: kept playlists follow the server when Home loads (changed: kept again with the new songs, dropped songs deleted; deleted on the server: kept, marked "Deleted on server" until removed; tested with a simulated deleted playlist, the "changed" path not yet). Starred songs are not auto-kept (not wanted).
  - Dropped: promoting cached songs into offline (cache and offline stay separate).
  - Not wanted: smaller offline copies (offline stays original).

## Later

- Done: Explore panels (YouTube / Navidrome genres). Left: WebDAV/SMB songs' wasted YouTube lookups.
- Done and tested: Internet radio (the server's stations) as a Library link; Next/Previous move between stations; never cached.
- Play-queue sync with the server (`savePlayQueue` / `getPlayQueue`): stop on the phone, continue in psysonic on the PC.
- Done and tested (try-out): saved queues in the player menu (save, play back from the same song and second, delete).
- Not wanted: listening stats page (BitChord's cards are enough), waveform seek bar.
- Maybe: bookmarks (resume long tracks where they were left).
- Downloads (single songs) for Navidrome, if offline pages aren't enough.
- Then: BitChord settings / custom changes.
