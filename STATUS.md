# Fork status (2026-10-29)

Snapshot for picking the work up again. Plan: `FORK_PLAN.md`. Every touched upstream line: `FORK.md`.

## Done (the last two rounds are not committed yet; nothing pushed)

- Phase 0: Docker build (`docker/build.ps1`); APKs land in `out\` in the repo (git-excluded). Delete `out\` when a round of work is finished.
- Phase 1: Navidrome library (Subsonic), each song's own cover, original files (FLAC/Opus raw).
- Phase 2: crossfade on Next/Previous (the blend lasts as long as the Crossfade slider; switch "Crossfade on skip").
- Phase 3: timed lyrics from Navidrome first, then the online chain.
- Phase 3b: no YouTube lookups for Navidrome songs; AutoPlay from the library (similar songs, else random); Navidrome shelves on top of Home (Recently added, Playlists, Recents, Most played, Random albums).
- Phase 4: covers sized per surface; cover clips (`.cover.mp4`) in the cover square, else Apple/Tidal/Community motion art, else still cover. Spotify Canvas applies to Navidrome songs too (the server's own clips are tried first).
- Phase 5: backdrops (`.background.mp4`) like Spotify Canvas: full screen on any portrait screen, video sharp (no blur/dim under the controls), controls stay until tapped away (auto-hide off by default), cover back while paused. "Backdrop videos" in the player menu (same switch as Settings) flips video / cover at once. Landscape/wide: cover clip or still cover in the sleeve.
- Phase 6: plays + "now playing" reported to Navidrome (switch); stars synced with the server; Navidrome playlists; "Reverse order" in every page's sort menu.
- Album pages from Navidrome: one `getAlbum` instead of the whole library, with the album's own cover and artist (from Home, the library, the player and menus).
- Song Info for Navidrome songs: release date, your plays, rating/star (from the server).
- Navidrome in the Library: the Navidrome page opens on Albums; a "Navidrome playlists" row; the Search tab's "Library" source searches the Navidrome songs too.
- "Navidrome on Home" switch (normal place + Fork settings).
- No black player at a track change: the next two covers are fetched ahead.
- Skips: the screen switches to the new song at once (no flash back), the audio still crossfades.
- "Available offline" for Navidrome albums/playlists (original files, never evicted, play with no server); Library row.
- Caches: Navidrome audio is kept in the song cache (size in Settings); clip cache size + clear backdrops / clear cover clips; "Clear image cache" is upstream.
- YouTube history for Navidrome plays: tested signed in (match, history entry, watch time reported); switch off by default.
- Navidrome artist pages from the server (one search, ~2 s), Explore with collapsible "YouTube" and "Navidrome" (genres) panels, genre pages.
- Backdrops: on a new track the cover stays 2.5 s, then the video fades in (1.2 s).
- Offline (Phase 8): background downloads with a notification and Cancel, Wi-Fi always + "Download over mobile data" switch, covers + clips + lyrics kept with the songs, sizes before/after, "Available offline storage" list with Remove, offline mode (switch or no network), lyrics cache + clear button, "Pre-load next songs".
- Offline mode strip above the mini player ("Go online"); Internet radio (server stations, with their logos) in the Library; Next follows "Reverse order"; kept playlists follow the server; saved queues (try-out) in the player menu.
- Deep audit (3 reviewers) done; every finding fixed or noted.
- Settings: every fork switch in its normal place and again in "Fork settings" before Advanced.
- Audio Pipeline: Source says Navidrome; decoder name survives crossfades.
- Phase 7: updater reads `ayhamo/BitChord` releases (`fork-v<version>`); workflow `.github/workflows/fork-sync-release.yml` builds the **prod** flavor (`com.ayhamo.bitchord`, "BitChord Fork", installs beside the official app) signed with the fork's own release key (secrets on GitHub; local copy in `docker/keys/release/`, gitignored, back it up).

## Left

- Push to GitHub and the first release: only when the owner says so (don't ask).
- Package `com.ayhamo.bitchord` ("BitChord Fork") sits beside the official app; the first fork release is a fresh install (its own data).

## Problematic / to watch

- The emulator shows a black screen after every install until relaunched (emulator, not the app).
- Turning the emulator's Wi-Fi off with `svc wifi disable` cuts adb (LDPlayer); turn it back on from the emulator itself.
- Song rows hidden behind the bottom bar ignore taps (upstream layout).
- Navidrome audio stays in the song cache (LRU, 512 MB default) but is not listed in "Cached songs" (that folder is YouTube/JioSaavn only).
- R8 release build takes ~15 min in Docker; fine on GitHub.
- On Android 13+ the download notification needs the app's notification permission (the download runs without it, just unseen). The dev app on the emulator had it off; granted there with `pm grant`.
- Not yet verified: keeping clips (the test album had none).
- Cover clips were re-encoded to 480 px on the server; clear the app's clip cache once if old 1080 px ones were cached.

## Said we'd do later

- See `FORK_PLAN.md` "Phase 8" (offline ideas) and "Later".
- Discord covers: skipped (not used).
- Your genre tags are mostly YouTube categories ("People & Blogs", "NA"); retag on the server for better Explore tiles.
- Then: BitChord settings / custom changes.

