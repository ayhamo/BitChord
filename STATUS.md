# Fork status (2026-10-29)

Snapshot for picking the work up again. Plan: `FORK_PLAN.md`. Every touched upstream line: `FORK.md`.

## Done (committed locally, nothing pushed)

- Phase 0: Docker build (`docker/build.ps1`); APKs land in `out\` in the repo (git-excluded). Delete `out\` when a round of work is finished.
- Phase 1: Navidrome library (Subsonic), each song's own cover, original files (FLAC/Opus raw).
- Phase 2: crossfade on Next/Previous (the blend lasts as long as the Crossfade slider; switch "Crossfade on skip").
- Phase 3: timed lyrics from Navidrome first, then the online chain.
- Phase 3b: no YouTube lookups for Navidrome songs; AutoPlay from the library (similar songs, else random); Navidrome shelves on top of Home (Recently added, Playlists, Recents, Most played, Random albums).
- Phase 4: covers sized per surface; cover clips (`.cover.mp4`) in the cover square, else Apple/Tidal/Community motion art, else still cover. No Spotify Canvas for Navidrome songs or album pages.
- Phase 5: backdrops (`.background.mp4`) full screen on phones, the cover (or its cover clip) stays on top the whole time. Wide windows/landscape: the sleeve shows the cover clip or still cover, never the backdrop.
- Phase 6: plays + "now playing" reported to Navidrome (switch); stars synced with the server; Navidrome playlists; "Reverse order" in every page's sort menu.
- Album pages from Navidrome: one `getAlbum` instead of the whole library, with the album's own cover and artist (from Home, the library, the player and menus).
- Song Info for Navidrome songs: release date, your plays, rating/star (from the server).
- Settings: every fork switch in its normal place and again in "Fork settings" before Advanced.
- Audio Pipeline: Source says Navidrome; decoder name survives crossfades.
- Phase 7: updater reads `ayhamo/BitChord` releases (`fork-v<version>`); workflow `.github/workflows/fork-sync-release.yml` builds the **prod** flavor (`com.music.bitchord`, "BitChord") signed with the fork's own release key (secrets on GitHub; local copy in `docker/keys/release/`, gitignored, back it up).

## Left

- Push to GitHub (main + workflow), then run the workflow once by hand (`force`) to check it end to end. Needs the owner's go-ahead.
- First release `fork-v1.8.1` comes from that run. Installing it on the phone: a new app next to "BitChord Dev"; an installed official BitChord (same package, other key) has to be uninstalled first.

## Problematic / to watch

- The emulator shows a black screen after every install until relaunched (emulator, not the app).
- Song rows hidden behind the bottom bar ignore taps (upstream layout).
- Artist pages from Navidrome still read the whole library (~2,250 songs, cached 2 min).
- R8 release build takes ~15 min in Docker; fine on GitHub.
- Cover clips were re-encoded to 480 px on the server; clear the app's clip cache once if old 1080 px ones were cached.

## Said we'd do later

- YouTube side: Explore, WebDAV/SMB songs making the same wasted YouTube lookups, YouTube recommendations; matching Navidrome plays to YouTube Music history (see below).
- Switch to hide the Navidrome Home shelves.
- Navidrome results in the normal Search tab.
- Then: BitChord settings / custom changes.

## Idea: Navidrome plays into YouTube Music history

Signed in, a finished Navidrome play could be looked up on YouTube Music (title + artist, strict match) and reported as a watch of that song, so YouTube's recommendations learn from it. Not built yet.
