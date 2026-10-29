# Fork status (2026-10-29)

Snapshot for picking the work up again. Plan: `FORK_PLAN.md`. Every touched upstream line: `FORK.md`.

## Done (committed locally, nothing pushed)

- Phase 0: Docker build (`docker/build.ps1`); APKs land in `out\` in the repo (git-excluded). Delete `out\` when a round of work is finished.
- Phase 1: Navidrome library (Subsonic), each song's own cover, original files (FLAC/Opus raw).
- Phase 2: crossfade on Next/Previous.
- Phase 3: timed lyrics from Navidrome first, then the online chain.
- Phase 3b: no YouTube lookups for Navidrome songs; AutoPlay from the library (similar songs, else random); Navidrome shelves on top of Home (Recently added, Playlists, Recents, Most played, Random albums).
- Phase 4: covers sized per surface; cover clips (`.cover.mp4`) in the cover square, else Apple/Tidal/Community motion art, else still cover. Star placeholder not needed.
- Phase 5: backdrops (`.background.mp4`, 9:16) via upstream's Spotify Canvas presentation on phones; cover comes back on top while paused.
- Phase 6: report plays + "now playing" to Navidrome (switch); stars synced with the server (player + notification); Navidrome playlists (Home shelf, pages). Reverse order: upstream's "Date added" arrow already does it per page.
- Settings: every fork switch in its normal place and again in "Fork settings" before Advanced (same setting, in sync).
- Audio Pipeline: Source says Navidrome; decoder name survives crossfades.
- Phase 7 (partly): updater reads `ayhamo/BitChord` releases (`fork-v<version>`); workflow `.github/workflows/fork-sync-release.yml`; secrets `FORK_KEYSTORE_BASE64`, `FORK_KEYSTORE_PASSWORD`, `FORK_KEY_ALIAS` set on GitHub.

## Left

- Push to GitHub (main + workflow), then run the workflow once by hand (`force`) to check it end to end. Not done: needs the owner's go-ahead.
- First release `fork-v1.8.1` comes from that run.

## Problematic / to watch

- Releases are signed with the debug key from `docker/keys` (so they update over the dev builds). Package is `com.dev.bitchord` ("BitChord Dev"). Fine for personal use; a real release key would mean a fresh install.
- The emulator shows a black screen after every install until relaunched (emulator window manager, not the app).
- Song rows hidden behind the bottom bar ignore taps (upstream layout).
- Opening an album from Home loads the whole library first (~2,250 songs, a few seconds the first time; cached 2 min).
- R8 release build takes ~15 min in Docker; fine on GitHub.
- Cover clips were re-encoded to 480 px on the server; clear the app's clip cache once if old 1080 px ones were cached.

## Said we'd do later

- YouTube side: Explore, WebDAV/SMB songs making the same wasted YouTube lookups, YouTube recommendations.
- Switch to hide the Navidrome Home shelves.
- Faster album open from Home (fetch one album instead of the whole library).
- Backdrops on landscape/tablet (upstream keeps clips in the sleeve there).
- Then: BitChord settings / custom changes.
