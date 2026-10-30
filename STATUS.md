# Fork status

Snapshot for picking the work up again. Plan: `FORK_PLAN.md`. Every touched upstream line: `FORK.md`.

## Now (2026-10-30)

Checkout is on branch `hooks`. PR https://github.com/ayhamo/BitChord/pull/1 (hooks into main) is open. CI's one Android packaging failure passed on re-run (it also passed when reproduced locally).

Next steps, in order:

1. Done: the third emulator round passed. Network return is fixed and tested: with airplane mode on, Home shows the kept page; once the network is back, the Navidrome section loads and a paused song gets its lyrics, with no relaunch. On this emulator `svc wifi disable` alone doesn't count as "no network" (it still reports one, so there's no Offline strip); test with `cmd connectivity airplane-mode enable`.
2. Decided, no change: 4 songs skipped during a Wi-Fi drop with mobile data off. The same emulator effect explains it, since Android still reported a network, and the result is recoverable ("tap to keep the rest"). Not tested on a real phone.
3. Done: PR #1 is merged into main.
4. Start radio (Instant Mix) is built and tested on the emulator (branch `instant-mix`). Same row: "Start radio (Instant Mix)" on a Navidrome song, plain "Start radio" and upstream's path on a YouTube song. It plays the server's `getSimilarSongs2` answer (100 songs, server's order, seed first, seed and second copies dropped, no per-artist cap). The empty-answer toast ("No similar songs found") has a unit test for the list but wasn't seen on a device, because the server answered every song tried. After its PR merges, run `fork-sync-release.yml` by hand with `force=true`, which replaces `fork-v1.8.1` (the daily job skips while that tag exists; the owner okayed the force). Android is then finished.
5. Done: three player switches, independent, in the player menu and Settings: "Fullscreen cover art" (upstream's own setting, now honoured in any portrait window), "Backdrop videos", "Animated covers". A Spotify Canvas on a YouTube song now follows the same rules as a Navidrome backdrop. Open idea from the owner, not decided: with Fullscreen on and no video, pausing shrinks the art to the small cover and resuming makes it big again.
6. Done on the `pc` branch: desktop modules restored; `graphify-out` covers them (`graphify update .`, code only); the Windows portable zip cross-builds in Docker (`eclipse-temurin:21-jdk`, apt `cmake ninja-build g++-mingw-w64-x86-64`, posix alternatives, `-Pbitchord.target=windows :desktopApp:windowsPortable`) and runs on the owner's PC. Test runs live in `out\pc-run` with a portable JRE, with `APPDATA` and `LOCALAPPDATA` and `user.home` pointed inside it so the app can't read real browser profiles. It still writes `HKCU\Software\JavaSoft\Prefs\com\music...` (Java Preferences): remove that when PC testing ends.
7. PC next, on `pc` (`git merge main` first):
   - Add test hooks (for example a loopback-only debug bridge).
   - Move the Navidrome code to `shared` (it uses Android prefs, Context and Uri, so those need abstracting), then retest Android.
   - Connect it to the desktop library, audio engine, covers, clips, lyrics and stars.
   - Pick PC-Continued commits. Start with their memory fixes (`c310d0e3`, about 430 lines). The owner should look at their app before deciding on the redesign.
   - Then the rest of the fork's features: offline, radio, saved queues, Home sections.

Full Android audit (Audit request chat, main at a47f039d): 2 high, 8 medium, 17 low, read from the code only. Fixed on `audit-fixes`: H1, H2, M1-M4, M6-M8, L2-L7, L13-L16.
Tested on the emulator: M1, M2, M6, and a general smoke test. H1 has a unit test. H2, M3, M4, M7, M8 and the lows are fixed by reading only, not seen on a device.
Left on purpose, ask if they bite: M5 (a keep waiting for an allowed network uses up Android 15's 6-hour limit; needs persisted keeps or WorkManager), L1 (Home hides YouTube's error when server shelves are up), L8 (offline manifest read on the main thread at start), L9-L11 (edits and seeks during a skip's arming window; the crossfade area the owner said to leave), L12 (paused cover flickers on a buffer stall), L17.

PC state (branch `pc`, merged with main at 39421bbb): Navidrome lives in `shared`; the desktop has the sidebar page and login (DPAPI), Home shelves, album/artist/playlist pages, playback, stars, scrobbles, AutoPlay. Tested against a throwaway local server (`nd-test` container, `out/pc-run/nd`, generated login in `out/pc-run/nd/test-login.txt`). Remove the container, that folder and `HKCU\Software\JavaSoft\Prefs\com\music` when PC testing ends. Never type the owner's real password: they log in themselves.
Tested against the real server through the test album (see memory `bitchord-test-album`): library, Home, playback, lyrics, backdrops beside the cover, clips, Hi-Res badges, YouTube history sync (`history: entry created`, HTTP 204). The owner logged in to Navidrome and YouTube themselves.
PC, tested on the app with the real server: the three switches in the player menu and Settings, live and saved across a restart (Fullscreen only acts in a narrow or upright window, and its row says so in a wide one; paused, the small cover comes back). Start radio (Instant Mix): 98 songs on Midnight Sun. A song on the Navidrome page now plays the list on screen, so an album's songs and then AutoPlay, not the whole library. A notice (`ForkNotice`) says "No similar songs found"; seen through the bridge, because the server answered every song tried.
Built, not seen working: Spotify's "Made for you" shelf at the end of Home. It needs the owner signed in to Spotify on the PC (Settings, Account & integrations). The fetch moved to `shared` (`ForkSpotifyMadeForYou`); on the emulator it ran and found 1 saved mix, with Spotify's category endpoint answering HTTP 429.
Offline on PC (`ForkOffline`, the phone's `NavidromeOffline` moved to `shared`): tested on the app with the real server: keep from an album page, Offline mode, restart, playback from the kept files with clips and lyrics, Home's "Available offline" shelf, the list, Remove. The app's files are under `~/.local/share/bitchord/navidrome_offline`. Fixed on the way, Windows only: `renameTo` won't replace a file (manifests stopped updating), `:` in a cover id isn't a file name. Not tested: offline by itself when the server stops answering (only the switch). Quitting the app mid-download doesn't record the page (closing the window only hides it, so downloads go on); keeping the page again reuses the finished files, unless another page's keep or remove swept them first. A shutdown hook for this was tried and didn't work in two runs, so it was taken out. A file the player has open can't be deleted on Windows, so it stays until the next keep or remove. On the emulator the moved code loaded the kept album and kept a new one; playing a kept song in airplane mode is still to do: use a detached guest script, since airplane mode cuts adb and survives a restart.
Memory, measured with the packaged flags (`-Xmx512m`, G1) on Midnight Sun with both clips playing: working set about 1020 MB to about 850 MB, private bytes about 1640 MB to about 1200 MB, level over 6 minutes, with rapid skips and switch flips causing no crash. CPU unchanged at about half a core with clips (next to nothing without). Done: one copy per clip frame instead of three, replaced frames closed; from PC-Continued `c310d0e3`, the analyser's 12-entry window; artwork budget a tenth of the heap; Coil cache 32 MB, on the app's HTTP client so kept covers load with no server. Left out of `c310d0e3`: yt-dlp out of process (biggest possible saving, about 815 MB with clips off is mostly the rest of the app, but one process per resolve, no cookies, a frozen binary: the owner's call), PoToken reuse (shared with Android), decode buffer (trailing zeros reach saved analyses), category artwork (their fix looks regressive), preload (the code isn't here). `worthSwapping` is a playback fix, not memory: separate.
PC next: the owner signs in to Spotify to check "Made for you"; the emulator's airplane mode must go off by hand in its window (it cuts adb), then play a kept song in airplane mode from a detached script. Known: an instrumental can get another song's online lyrics (golden hour); Home scrolling through the bridge is untested; in an upright window the player's menu can open away from its button, because the button's place is only reported in the wide layout (upstream).

Facts worth keeping:

- `desktopUi` is upstream's own copy of the player and pages (split on 2026-10-14 "so a desktop redesign can land without moving the phone"); `desktopApp` depends on `shared` + `desktopUi`, not `sharedUi`. Keep them separate. PC-Continued's `sharedUi` edits map onto `desktopUi`, because their fork predates the split. Today's `sharedUi` differs from that copy by little (Now Playing about 213 lines, Lyrics 287, Queue 40).
- The release job merges upstream's release tag in CI only; it doesn't update `main`. After a new upstream release, merge the same tag into `main` by hand. Upstream's latest release is v1.8.1 (2026-10-19), and `main` has all of upstream's commits up to 2026-10-26. PC-Continued's tags (v1.8.3x) are local only; never push tags.
- Local branches: `pc` (still at `00cd6cb1`, restore not committed anywhere), `pc-continued` (mirror of their `main`), `hooks`. Remotes: `origin`, `upstream`, `pc-continued`.
- `svc wifi disable` over adb cuts adb on this emulator; use a detached guest shell that turns it back on. Round 1's emulator freeze was probably not the app, though that isn't proven.
- Test logs from today: `test-round1.md`, `test-round2.md`, `test-round3.md` in the session scratchpad `C:\Users\ayham\AppData\Local\Temp\claude\C--Users-ayham-Desktop-Projects-BitChord\f08252f9-f9aa-4f8c-8ac0-c2e9c79b091f\scratchpad`.

## Older snapshot (2026-10-29)

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

