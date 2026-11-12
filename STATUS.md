# Fork status

Snapshot for picking the work up again. Plan: `FORK_PLAN.md`. Every touched upstream line: `FORK.md`.

## Where it stands (2026-11-12, night): Automix analysis fixes, tested on the emulator, not committed

From the owner's 30 minutes of use ("songs not analysed" after a video-to-music swap, Play next, reorders). Phone only; the PC has neither the analyzer queue nor this cache.

- **Analysis focus** (`ForkAnalysisFocus.kt`, hooks in `TrackAnalyzer`, `CrossfadeController`): passes, queued or running, for songs the listener moved past are dropped; the old pair took about 30-60 s first. An abandoned decode is not read as a broken copy (`structure` returns early when `stopped`), and gives back its head claim and tried marks.
- **Analysis copy** (`ForkAnalysisCopy.kt` in shared, hooks in `AudioCache.requestAnalysisHead`, `AutomixAnalysisSource`): when the player already holds the next song's cache entry, the analysis head downloaded and saved nothing (held=0) and the song was never analysed. It now goes under `<id>#automix`. Costs one extra download of the song's Opus (3-4 MB) in that case only.
- Tests: shared 346 + app 1407 pass (new: `ForkAnalysisFocusTest` 4, `ForkAnalysisCopyTest` 1). A JVM test of the cache lock itself is not possible: Media3's `SimpleCache` needs Android's `SparseArray` (no Robolectric here).
- Emulator, 21:41-21:46: Play next of "Espresso" while its entry was held → `#automix` fetched in 0.25 s, analysed in 29 s, marker shown, DJ filter blend played; two quick skips → the new current song analysed at once; skip during a running pass → the next song analysed 32 s later, nothing marked bad. Not tried: the music-only video→audio swap, queue reorder by drag.
- Audited twice; the first audit's bug (an abandoned decode marked the copy bad or deleted it) and three minor points are fixed.

## Earlier: 2026-11-12, evening: four fixes built, tested, audited, committed and pushed

On top of 3fe06464 (pushed). Tests: phone shared 346 + app 1402, PC 883, all pass. Builds: the phone APK (`assembleDevDebug`) and the PC Portable zip.

**Done**
- **12 dB filters for Web/Advanced AutoDJ (both apps):** `ForkMixShelf` now has its own 12 dB/oct low- and high-pass (one Butterworth section each, as in the extension), gliding in log frequency. The upstream 24 dB transition filters are held open during these mixes. On the PC, the playing deck starts open and glides in (`startOpen`), so an Advanced filter mix doesn't click at its start.
- **Lyrics: old lines stayed under a new song** when neither the player nor the catalogue gave a length. `MainViewModel.forkDropStaleLyrics` clears them as a new lookup would.
- **Automix style, key-aware, phrase length and the pair picks are in the backup**, both ways between the phone and the PC (`ForkSettings.BACKED_UP`, PC `ForkBackup.FORK`).
- **Phone phrase length setting** (Auto / 8 bars / 16 bars), as on the PC. A fixed length is kept to the last/first 30 s, and it refuses the loop mix. That check now runs before the pick check (both apps), so after a Blend/Filter pick the overview keeps Loop greyed with the true reason.
- **Overview kept a stale "no loop mix" reason** after its cause was undone (a fixed length back to Auto, a pick cleared), because the cached loop plan was reused silently. Both apps now note the cached plan's verdict again on reuse (fall-back reason included). Audited clean; checked on the phone that the verdict holds across ticks.

**Tested in the apps**
- **Phone (emulator):**
  - The phrase row and its subtitles.
  - The overview shows "8 Bars" with Loop greyed, also after a Filter pick.
  - A Web AutoDJ Filter preview ran from Romeo into XOXO, and the next pair logged "no loop mix (a fixed 8-bar overlap is chosen)".
  - Put back afterwards: phrase length Auto, style Web AutoDJ, test picks removed.
- **PC (bridge 8775, muted):**
  - A Filter pick + Preview ran "DJ_FILTER, 4 bars" from Dusk Till Dawn into Starboy, with no errors.
  - The test pick was removed afterwards.

**Not tested:** the sound of the new filters (not by ear), and the lyrics case (needs a song with no length, which the emulator can't reproduce on demand).

## Earlier: 2026-11-12, committed and pushed (fdb88601, 3fe06464)

Builds: Android `assembleDevDebug` + all unit tests (app, shared, sharedUi) pass; PC tests and the Portable zip pass (`out/BitChord Portable.zip`).

**Fixed today**
- **Home chips hidden on the PC:** the shelves arrived first and kept the top, so the chip row came in above the view (seen only by scrolling up). The empty chip item is now 1 dp (`ForkHomeFeedUi.kt`), and the row shows at the top.
- **Audit, 7 fixes (fresh reviewer):**
  - **Key-aware AutoDJ got wrong keys for 10 of 24:** the JNI writer dropped the analyser's ♯/♭, so "F♯ major" arrived as "F major" (both apps). `analysis_jni.cpp` now passes UTF-8. Analyses saved before this read their key as unknown, with no re-analysis (`AnalysisStore` `forkKeyText`, `ForkAnalysisKeyTest`).
  - **Phone Home:** a plain-feed page or pull-refresh landing after a chip tap mixed both feeds. Both now check the load generation.
  - **PC Home:** a stale page could reset paging, so a page was appended twice or paging stopped. Same generation check.
  - **The picked chip outlived sign-out and account switches:** `ForkHomeChips.reset()` now runs on both.
  - **Top Picks could be built from a chip's feed:** both apps now skip it.
  - **PC Play all did nothing on album or playlist shelves:** it now opens the shelf's playlist, as on the phone.
  - **The mix shelves kept filter history across a seek:** they now flush on seek and go flat on reset.
- **Key-clash subtitle:** now starts "Automix, Advanced AutoDJ style:", so a settings search for "automix" finds it.

**Tested in the apps**
- **PC (bridge 8775):**
  - **Home:** chips, select/deselect (a chip hides Top Picks and Navidrome, deselect brings them back), and "Listen again" as a 2-row grid.
  - **Settings:** the Automix style row and the key-clash toggle (greyed unless Advanced).
  - **Artist pages:** the Navidrome artist's "Starting The Weeknd radio…" notice shows at once and the radio plays; Similar artists = Lady Gaga, Ariana Grande, YOUHA (no collabs).
- **Phone (emulator):**
  - **Home:** chips, select (Relax) and deselect, and the Speed dial grid.
  - **Settings:** the Automix style row, and the key-clash row greyed under BitChord (style put back to BitChord after the check).

**Not tested**
- **Tidotido:** not installed on the portable PC profile or on the emulator (only Qobuz [Heisenbergg], JioSaavn, YouTube). Adding it needs the owner's token URL.
- **PC Local Music tags:** the portable profile has no music files (none copied without approval). Unit tests only.
- **Audio:** the shelves, key-clash blends and the 16-bit path are untested by ear.
- **Doubled songs:** some songs show twice in the PC Navidrome Songs tab (e.g. Daredevil by Zaptroxix; Wicked Games shows once). They look like two files on the server, not a client bug; not checked on the server.

Cleaned up: helper worktrees and branches, `bitchord-base`, the APKs in `out/`.

## Earlier: 2026-11-11, end of day

### Asked for today (owner)
Seven items:
1. PC Local Music: a basic fix (tags and formats).
2. Apple-style AutoMix presets: on hold.
3. The YT extension's automixer as a setting.
4. Shrink the fork lines inside upstream files.
5. Fix Similar artists, and any bug found.
6. Then build, audit, fix, unit test, build and test.
7. Later in the day: Home 1:1 with YouTube Music, with the chips, keeping only Top Picks, Find Your Mood and Navidrome as our own.

**Answered:** the phone's Local Music is not basic. It already reads MediaStore tags and has LocalGroups. Only the PC's was basic.

### Done and tested
**PC Local Music tags** (`desktopApp/…/ForkLocalTags.kt`, `ForkLocalTagsTest`, hooks in `DesktopLocalMusic.kt`):
- Title, artist, album and length are read from the file.

  | Format | Tags from | Length from |
  |---|---|---|
  | FLAC | Vorbis comments | STREAMINFO |
  | Ogg / Opus | comment packet | last page's position |
  | MP3 | ID3v2 | TLEN, Xing frame count or bitrate |
  | M4A | ilst | mvhd |
  | WAV | LIST/INFO | data size |
  | AIFF | NAME and `ID3 ` chunks | COMM |
- `.opus`, `.aiff` and `.aif` are now listed.
- Unit-tested. Not yet seen in the PC app.

**Similar artists** (`shared/…/navidrome/NavidromeArtistInfo.kt`, `NavidromeArtistPictures.kt`, `NavidromeSimilarArtistsTest`):
- AudioMuse's "similar" list was mostly the page artist's own collaborations ("Metro Boomin, The Weeknd & 21 Savage"). Credits that include the page artist are now left out.
- A collaboration's picture comes from its lead artist, only when at least two of its parts are known artists. That keeps bands such as "Earth, Wind & Fire" whole, and fixes the wrong photo on the Metro Boomin collab.
- Unit-tested. Not yet seen in the app.

**Artist radio feedback:** it took about 8 s with no sign it had started. Now "Starting X radio…" shows at once: a toast on the phone (`ForkArtistRadio.kt`, `fork_starting_artist_radio`), a notice on the PC with a guard against a second press.

**Automix style** (helper, merged; files `shared/…/smart/ForkAutoDj.kt`, `ForkAutoDjCurves.kt`, `jvmSharedMain/…/ForkMixShelf.kt`, `app/…/ForkAutoDjRide.kt`, `ui/screens/ForkAutomixStyleRow.kt`, `desktopApp/…/ForkDesktopAutoDj.kt`, `ForkAutomixStyleSetting.kt`; upstream hooks listed in FORK.md):
- Settings › Playback › Automix style offers BitChord (default, unchanged), Web AutoDJ and Advanced AutoDJ: the YT Music extension's planner and curves, on the phone and the PC.
- On the emulator, the row shows under Automix, switches, and its subtitle follows the choice. It was left on BitChord.
- **My fix in the audit:** the bass swap and highs shelves were not applied on 16-bit output (float off, which is most Bluetooth routes). Added `ForkMixShelf.process16` and a hook after both `flip()`s in `TransitionFilterProcessor.queueInput`, with a test.
- **Not judged by ear:** the shelves, the speed-up and the 8 × 3 s glide back, Advanced's vocal re-route, and the level mid-blend (the 1/√ headroom trim stays on).
- **Automix overview picks** (2026-11-12; `ForkMixPicks.kt`, `ForkMixPicksPanel.kt`, one hook in `MixViewPopup.kt`): under Web/Advanced AutoDJ only, the overview takes a style (Auto, Loop, Blend, Filter, Fade) and a length (Auto, 4–32 bars) per song pair, saved, with Preview. Locked once the mix is armed or the popup still shows a finished pair. 14 unit tests; tested on the PC and the emulator (lock, Loop verdict, 16 → 8 fit, Filter capped at 8, Preview).
- **The helper's open points:**
  - The setting is not in backup.
  - The phone has no phrase-length setting, so it is always Auto.
  - The cut filters are 24 dB/oct; the extension's are 12.
  - On the PC, a loop mix that takes over after the speed-up leaves the outgoing deck fast until it ends.

**Fork lines moved out of upstream files** (helper, merged):
- New files: `ui/screens/ForkHomeRoutes.kt`, `data/canvas/ForkSpotifyHarvestBackoff.kt`, `download/ForkDownloadHelpers.kt`, `shared/…/canvas/ForkCanvasTitles.kt`.
- Upstream files trimmed: `MainActivity.kt`, `MainViewModel.kt`, `DownloadStore.kt`, `Downloads.kt`, `SpotifyToken.kt`, `CanvasArtwork.kt`, and `NowPlayingScreen.kt` (into `ForkSleeveClip.kt`).
- FORK.md was merged by hand with no conflicts.

**Builds:**

| Build | Result |
|---|---|
| Android `assembleDevDebug` | passed |
| App unit tests | 1398, 0 failures |
| shared | 308, 0 failures |
| sharedUi | 63, 0 failures |
| PC tests | 881, 0 failures |
| PC portable build | passed |

These runs started before the key-clash toggle (below) and may have missed the 16-bit fix. **Rebuild first thing.**

### Done, not built yet
**"Shorter blend when keys clash"** (the extension's `automixKeyAware`):
- Advanced AutoDJ only, off by default.
- Camelot rule: same number, or one step on the same letter. Keys under 0.5 confidence don't count.
- A clash gets 2 bars, or 4 when 2 are under 4 s (under 6 s from 140 BPM).
- Where it lives:
  - Shared: `ForkAutoDj.keyAware`, `keysClash`, `camelot`, and 3 new tests in `ForkAutoDjTest`.
  - Phone: `ForkSettings.automixKeyAware` (key `automix_key_aware`) and a switch under Automix style, greyed unless Advanced.
  - PC: `ForkDesktopAutomixStyle.keyAware` and a `SettingsToggle` under the style chips.
- PC settings search now also finds the toggle on its own.

### In progress: Home 1:1 with YouTube Music (helper stopped mid-way tonight; its edits are left in the tree)
**Why shelves are missing today:**
- `InnertubeParser.parseHome` keeps only `musicCarouselShelfRenderer` and `musicShelfRenderer`. Immersive carousels, grids, navigation buttons, card shelves and multi-row (podcast) items are dropped silently.
- The chip cloud in `sectionListRenderer.header.chipCloudRenderer` is never read.
- `MainViewModel` adds its own Recents (history), New releases and Explore browses, and drops repeated titles. The PC's `DesktopApp.kt` Home has its own loader.

**YT Music Home on the emulator today (same account), top to bottom:**
- Chips: Podcasts, Relax, Sleep, Energize, Sad, Romance, Feel good, Workout.
- Shelves:
  1. Speed dial (2-row paged grid)
  2. Quick picks (list, Play all)
  3. Music videos for you
  4. New releases
  5. Your daily discover (large cards)
  6. Trending songs for you (list)
  7. Fresh finds, old favorites
  8. From your library
  9. Mixed for you
  10. Long listens (list)
  11. "KPop Demon Hunters Cast" (artist header)
  12. From the community
  13. Explore (New releases / Charts / Moods & genres buttons)
- It changes every hour or so, so this must be parsed live, never hard-coded.

**The owner's rules:**
- Everything YT's Home sends, 1:1, Speed dial included, each drawn the way YT draws it, in YT's order and titles.
- Selecting a chip reloads Home with that chip's browse params and shows only YT's feed, as YT does.
- Keep only Top Picks for You, Find Your Mood and Navidrome as our own. Drop Recents, the extra New releases / Explore browses, and the title de-duplication.
- Phone and PC.

**Where the helper stopped (edits left in the tree, not built from the real checkout yet):**

New files:
- `shared/…/data/model/ForkHomeFeed.kt`: shelf kinds (CAROUSEL, VIDEO_CAROUSEL, LIST, GRID, LARGE_CARDS, EPISODES, BUTTONS, CARD), each shelf's styling, `ForkHomeChips`, and `FORK_HOME_UPSTREAM_EXTRAS = false`, which turns off Recents, the supplements and the title de-dup.
- `shared/…/innertube/ForkHomeFeedParser.kt`: first page, continuations and chips. Unknown shelves are logged once under "ForkHomeFeed".
- `sharedUi/…/ui/screens/ForkHomeFeedUi.kt`: the chip row, and one drawing per kind.
- `app/…/ui/screens/ForkHomeFeedPhone.kt`, `desktopApp/…/ForkDesktopHomeFeed.kt`: the actions. A chip toggles and reloads; Explore buttons, Play all and the header picture open their pages.
- `ForkHomeFeedParserTest`: 12 tests, with fixtures in `shared/src/jvmTest/resources/forkhome/`.

Hooks, listed in FORK.md: `Models.kt`, `InnertubeParser.kt` (4 wrappers), `YtMusicRepository.kt` (`home(params)`), `HomeScreen.kt`, `MainViewModel.kt`, `MainActivity.kt` (1 line), `DesktopApp.kt`, `DesktopSearchClient.kt`, `NavidromeHome.kt` and `ForkDesktopHome.kt`. On the PC, the Spotify mixes moved off Home; they stay on Explore.

Tested so far:
- A build from a patched copy passed.
- On the emulator, Home showed:
  - the chips
  - Top Picks
  - Navidrome
  - "Listen again" with the avatar
  - Find Your Mood
  - Music videos with Play all
  - Quick picks as a list
  - continuation shelves while scrolling.

Fixed tonight: my key-clash string's unescaped apostrophe (strings_fork.xml line 123) broke every Android build of the real checkout. It's escaped now. The helper removed its temporary `// TEMPDUMP` lines and dump files. The dev app on the emulator still has them until the next install.

Half-done:
- **Speed dial is one row, not the 2-row paged grid.** The web feed calls it "Listen again" and sends no rows hint. Fix: give a carousel whose header points to `FEmusic_listen_again` a 2-row grid, and add a test.
- **"Your daily discover" doesn't get large cards.** It comes as MEDIUM square items with Play all, and no other signal says it should look bigger. Ask the owner whether it should be large cards.
- **Not checked yet:**
  - On the emulator: chip select and deselect, with Top Picks, Find Your Mood and Navidrome hidden while a chip is picked.
  - A side-by-side comparison with the official app.
  - The PC tests and the PC build.
  - Explore buttons, card shelves and podcast episodes: they're not in this account's feed today, so only the tests cover them.
- **Order and titles differ from the YT Music Android app.** BitChord signs in with the browser cookie, so it gets the web client's Home (WEB_REMIX), and the web Home is ordered and titled differently: "Listen again" instead of "Speed dial", for example.

What YT's Home JSON looks like:
- **Chips:** `sectionListRenderer.header.chipCloudRenderer.chips[].chipCloudChipRenderer`, with `text`, `isSelected`, `navigationEndpoint.browseEndpoint {FEmusic_home, params}` and `onDeselectedCommand`.
- **Paging:** `continuations[].nextContinuationData`, and the next page in `continuationContents.sectionListContinuation`.
- **Shelf types:**
  - `musicCarouselShelfRenderer` and `musicImmersiveCarouselShelfRenderer`. Header: `musicCarouselShelfBasicHeaderRenderer`, with title, strapline, thumbnail, and a `moreContentButton` that is either a browse (More) or a watch (Play all).
  - `musicShelfRenderer`
  - `gridRenderer`
  - `musicCardShelfRenderer`
  - Shelves also carry `numItemsPerColumn` and `itemSize`.
- **Item types:** `musicTwoRowItemRenderer` (`aspectRatio`), `musicResponsiveListItemRenderer`, `musicMultiRowListItemRenderer` and `musicNavigationButtonRenderer`.

### Left, in order
1. Finish Home 1:1 (above), with parser tests from InnerTube-shaped fixtures.
2. Full rebuild under the lock: Android `assembleDevDebug testDevDebugUnitTest :shared:jvmTest :sharedUi:jvmTest`, PC tests, then the PC portable build.
3. Audit the combined round, mainly Home and the key-clash toggle.
4. **Test on the emulator:** Home vs YT Music shelf by shelf, the chips, the Automix style and key-clash rows, the artist radio toast, Similar artists without collabs, the Home See all, the player clip and paused cover, downloads.
5. **Test the PC app** (PORTABLE=1, bridge 8775, quit by POST `/quit`): Local Music tags, Similar artists, the radio notice, the Automix style and key-clash settings, Home and chips.
6. **Clean up:**
   - Helper worktrees `.claude/worktrees/agent-acea69d9028b760db` and `agent-a6a526af004a7ac75` (both merged into the main checkout).
   - `/d/Programs/Projects/bitchord-base`.
   - The APKs in `out/`.
   - The scratch screenshots.
7. Noticed, not fixed: the Navidrome Songs tab shows some songs twice. These may be real duplicate files on the server.
8. On hold: Apple-style AutoMix presets.

## Later: one list (owner, 2026-11-04)

The single list of what is left, ticked when done. Everything not here is either done or dropped.

- [x] (done 2026-11-06, seen greyed in the app with no server set) PC: grey the Navidrome settings rows while no server is set, as on the phone (offered 2026-11-05)
- [x] (done 2026-11-06) PC: faster start-up with AppCDS. It was never on: the Windows runtime keeps its base archive in `bin/server`, not `lib/server`. Measured first frame: 1.65–1.8 s with the archive, 2.8–3.0 s without (base CDS only), 17.5–20.6 s on the one start that records it (first start after install or update; written at a normal quit). The JVM never replaced an out-of-date archive after an update, so the app keeps a class-path stamp and deletes it (`ForkAppCdsStamp`). Test-only bridge `/quit`
- [x] Bass-synced glow/flash behind the player (the Auris Reddit post's look), PC only (owner, 2026-11-07): "Bass glow" in Appearance, off by default. Seen 2026-11-07 in the PC app over "Starboy": a red halo around the cover on kicks (about 2 a second), gone between them and when paused. The first version read the pulse's meter, which a loud master holds at 1, and showed nothing; it reads its own unclipped one now. How strong it should be is for the owner to judge.
- [x] (built and tested 2026-11-06 on the emulator, which has no navigation bar: each mode's status bar state, the mode kept after a restart, a swipe shows it for a moment, the player, its ⋯ sheet and closing them keep it hidden, upstream's player row greyed under All. Found and fixed: opening the player showed the bar again, because setting the status-bar icon colour on Android 11+ also writes the old system-UI flags; `reapply` now follows both icon-colour calls. Not tried: a real phone's navigation bar, gesture navigation, a cutout) Next release, phone: "Hide system bars" in Settings > Appearance (owner, 2026-11-06), four modes: None (default), All, Top only (status bar), Bottom only (navigation bar); hidden bars apply app-wide, swipe from an edge shows them for a moment (`WindowInsetsControllerCompat.hide(systemBars())`, `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`), applied in `MainActivity` on the setting and on window focus; a fork file plus small hooks. Upstream's "Hide status bar in player" (`NowPlayingScreen.HideStatusBar`) stays; greyed while All or Top only is picked. Check: edge-to-edge insets (Home, mini player, sheets, keyboard), cutouts, gesture navigation, the player's own switch
- [x] (built and tested 2026-11-06 with the v1.9.1 merge: phone emulator, a picked chip stays picked, tops up with the next page of the same chip, survives a skip, the row hides under repeat-all; PC tested 2026-11-06 too: chips show, Deep cuts replaced the AutoPlay tail, a skip (crossfade on skip) kept it picked and continued it, "continued … with 43 songs") Next release, phone and PC: YouTube Music's own queue chips (owner, 2026-11-06; upstream may add them first, so check after merging). The radio `next` request we already make (`Innertube.kt`, `YtMusicRepository.radio()`, playlist `RDAMVM<id>`) carries `musicQueueRenderer.subHeaderChipCloud.chipCloudRenderer.chips[]`: All, Popular, Discover, Deep cuts, plus per-song moods, decades and genres; each chip's `queueUpdateCommand.fetchContentsCommand.watchEndpoint` has its own `playlistId` (`RDAT…`) and `params`. Parse them in shared code, show a chip row above the AutoPlay part of the queue, and on tap fetch `next` with that chip's endpoint and replace only the AutoPlay tail after the current song, never songs the user queued (Sekai Tune's `setQueueFilter` does the replace with a generation counter; its chips themselves are local guesses, not YouTube's)
- [x] Songs no decoder here can play (owner's call 2026-11-06: skip with a toast; `ForkUnplayableSkip`). Was (seen 2026-11-06, upstream behaviour): a queue of songs no decoder on the device can play (the Navidrome Starboy album is Dolby Atmos `audio/eac3-joc`; on the x86_64 emulator `libffmpegJNI.so` fails to load, "cannot locate symbol ff_psdsp_init_x86", and Android has no E-AC3 decoder) makes Media3 enable no track, a period with no track counts as fully buffered, so the player prepares every queued song at once; each holds its `moov` and cover art (~5.7 MB), and 17 of them ran the phone out of memory (192 MB). Not seen with playable songs. Check on the real phone whether the arm64 FFmpeg decodes these files. Now: the Starboy album (21 Atmos tracks) on the emulator skips all in about a second, one toast "Nothing left in the queue that this device can play (Dolby Atmos (E-AC-3))", pauses on the last song, heap stays ~92 MB. Trilogy is Atmos too on this server; a playable album was not re-tried after the run fix.
- [x] Multi-select in Downloads / Local Music (PC; the phone's hold-to-select, Select all, Delete / Download / Remove from cache): `ForkSongSelection`, 2026-11-07
- [ ] Local Music Playlists and Folders tabs, with the PC Local Music port (after upstream v1.9.1 is merged)
- [ ] Vocals-only (bundled vocal model; `vocals-only` branch; phone speed first)
- (dropped, owner 2026-11-07) Kawarp fluid background
- [ ] Picture-in-picture
- [ ] PC-Continued's Automix planner changes (8498b497/18b6b3ae: blend in a vocal gap, auto 8/16 bars, end where the sound stops; shared, changes the phone): look again at their next major release, maybe as an off-by-default experimental switch
- [x] (done 2026-11-06: a shelf without a link and 6+ cards gets the arrow and opens its own cards, phone and PC; the PC arrow on a linked shelf still opens YouTube's page. A link-less shelf not found on Home that day to click) Home "See all" for shelves without a YouTube link (owner, 2026-11-05): open the shelf's own items in the grid; signed-out Home shelves carry no link
- [x] (built and tested 2026-11-07 in the portable app: build 1 offered build 2 from a local release list, Later, About's Check for updates, Download Now with progress, SHA-256 checked, Install Now; the updater swapped the files in about a second and the app came back as build 2, signed in, data folder intact, old and staged removed after its first frame, About said up to date; a build 3 with a wrong checksum was refused and its files removed. Not tried: Cancel mid-download (the local download took about a second), the two-minute wait, a failed move and its roll-back, a folder name outside the ANSI code page, a real GitHub release; `ForkUpdater`, `BitChordUpdate.c`; a stalled download gives up after 30 s and Cancel ends it at once, 2026-11-07, unit tests only) PC updater (owner, 2026-11-05): a second small exe beside BitChord.exe, started by BitChord: downloads the release zip, extracts it over the app without touching `data/`, then opens BitChord; with a nice update window. Revived 441cdf9f's updater is the reference, not the code. Widened to both apps 2026-11-07: see "Updater plan" below
- [x] Cover fade (cover-to-cover transition, from the Octave video), phone and PC, a switch in Appearance on both, off by default (owner, 2026-11-07). Dissolve only, 750 ms, no Y-flip. Seen 2026-11-07 on the emulator (old cover held, then dissolving into the new one in place); on the PC the switch and a skip were checked, but a skip between two different covers was not looked at there.
- [ ] (later, owner 2026-11-07) Two or three Apple AutoMix presets on our own analysis
- [ ] "My devices" and remote control (via Listen Together, ~1 day). Plan only (owner, 2026-11-11), from Lokal Music's take: the phone drives the PC's playback (play/pause/skip/seek/volume, the queue), also from the phone's media notification while the PC plays; pairing on the same Wi-Fi or Tailscale. Lokal also merges likes, playlists and history both ways and copies missing files, with a preview: not part of this item, decide separately
- [ ] Suggested songs under playlists: not shown on the PC. Fixed 2026-11-06 (untested): the PC's load-more stopped when a page brought only suggestions; it now also waits on their count. Check with a signed-in PC and an owned playlist
- [x] PC behind the phone (audit 2026-11-10, owner: fix from the phone's code, not the forks; old upstream-desktop `Desktop*.kt` copies nobody updated):
  1. Matcher: use the shared `TrackMatcher` (`targetOf(song)`) instead of `DesktopTrackMatcher`, which misses 8e9e70fe (non-Latin titles give no search at all), b46d6d9c, 6180e649, fe87ddcb, 3a29c3b2, d3314587, 38535595, ec04ec26. Feeds addons, JioSaavn, modules, upgrades, AutoPlay seed
  2. Addon timeouts as the phone (38535595): 45 s call, 60 s read; PC is 20 s (`DesktopAddon.kt:453`)
  3. Long playlists: Play/Shuffle use only the loaded rows and drop duplicates; use the shared `ContextRegistry`/`QueueWindow` as the phone does
  4. Downloads as the phone (be8c5d66): sources asked in parallel within a time budget, file named from its first bytes, not the source's label
  5. Discord: keep the Navidrome cover address out (our phone fix c36684d1, `DiscordRPC.kt`; PC `DesktopDiscordRpc.kt:228`), and on both apps send a public cover for a server song instead (its YouTube match's or iTunes' art), so Discord shows the song's art, not the app icon
  6. Alongside, from the forks: Revived 2961b748 lyrics first scroll (shared, phone too); Revived 8bb8152d download race (with 4); Revived #116 lyrics stop when the window isn't in front (PC, by hand, as a setting)
  7. Phone, Dolby songs' Automix analysis runs out of memory (`AudioDecoder.flatten` OOM, ~104 MB peak on a 256 MB heap; the song is then written off for the session): resample each decoded chunk to the analysis rate as it comes (~12 MB peak), and try again later after an OOM instead of giving up; compare analyses before and after on the emulator (upstream code: owner said yes 2026-11-10)
  8. PC, Dolby Atmos for Headphones: a "Multichannel output" option that sends 5.1/7.1 to Windows instead of folding to stereo (`DesktopAudioEngine.kt:373`), so Windows' Atmos for Headphones can render it; bigger job (M-L), last
  Done 2026-11-10, commit 50159740, released as fork-v1.9.1 build 33 (the phone took it through its in-app update, owner) and fork-pc-v1.9.1 build 22 (the owner's Utilities copy predates the PC updater, so that one is unzipped by hand; later builds come through About > Check for updates). Built and checked: items 1–8 by seven agents in worktrees, merged, reviewed (9 findings; fixed: WebDAV/SMB covers never to Discord, a party stops the playlist walk, the converter rebuilt on a channel change, phone cancel race, download legs and the handoff note; left: the WASAPI probe holds the audio lock as device listing does; addon-owned rows now wait up to the phone's 45 s); also Discord's title/button link opens a YouTube Music search for a non-YouTube id, and the three sync/watch workflows are gone. Tests: app 1398, shared 258, sharedUi 63, PC 874, no failures. Emulator: dev build installed, a YouTube song's whole-track analysis ran with the chunked resampler (no OOM); the emulator has no E-AC-3 decoder, so Dolby songs can't be analysed there (owner's phone needed). Lyrics setting on by default (owner). PC multichannel on the Astro A50 with Spatial sound, the output opens at 8 ch WASAPI shared once `mix_format` probes 7.1 (Windows keeps a stereo mix format); a Dolby Atmos song plays (sound judged by the owner). Also tried in the PC app: Play on a 145-song playlist queued all 145 ("context: following … past 100 rows"); a download saved as `.webm` with WebM's first bytes; lyrics kept moving with another window in front. Not tried: the Discord card (Discord status off in the test copy; the owner checks it), the matcher (no addon/module song played). Phone: Dolby songs (Sidewalks, Party Monster, Americano, Scheiße 9.5 min) analysed whole with no OOM.
- [x] (done 2026-11-06; seen greyed while signed out, F5 did nothing then; the signed-in refresh not run: log line "library: refresh asked for (sidebar or key)") Sidebar ⟳ beside the playlists, F5 / Ctrl+R (offered; the Library page button and focus refresh are done)

### Updater plan, phone and PC (owner asked 2026-11-07: plan only, nothing built)

Owner's decision (2026-11-07): the phone keeps upstream's update logic (it follows BitChord Android) but also gets the build number (same scheme as the PC: a same-version rebuild with a higher build is offered; `ForkAppBuild`). The PC gets the build number (the version stays the same, as the PC follows three forks, but each build is offered by its build number) and the in-app updater (6-8); started in a subagent the same day, with multi-select in Downloads / Local Music. Dropped from the list: youtube.com playlists in the app.

Already there:
- Phone (`AppUpdateChecker`, `UpdateAvailableDialog`): asks GitHub for this fork's "Latest" release (`fork-v<version>`) at every cold start; a dialog with the notes as Markdown; Download now with progress and Cancel; Install now hands the APK to Android's installer (one confirm tap); the universal APK first, else this phone's ABI split; no APK in the release → the releases page. Releases come from CI only, signed with the fork key, certificate checked, SHA256SUMS and an attestation published.
- PC (`DesktopUpdateChecker`, `ForkUpdateNotes`): reads the last 20 releases at start for the newest `fork-pc-v<version>` with the portable zip; a dialog with the full notes (Markdown since 2026-11-07); its button opens the zip in the browser, and the owner unzips it over the folder by hand. The zip has no `data\`, so settings and logins already survive that; `ForkAppCdsStamp` drops the stale start-up archive after new jars; `ForkSingleInstance` stops a second copy.

Gaps, both apps:
1. A rebuild under the same version is never offered. Today's http fix replaced `fork-v1.9.1`, so a phone on the earlier 1.9.1 is not told (the outside user's case). Fix: CI stamps a build number (`github.run_number`) into the app (phone `BuildConfig`, PC version resource) and into the release (a `Build N` line the checker reads, or a `build.txt` asset); offer when the version is higher, or the same with a higher build. Local builds are build 0. S, both.
2. Checked only at cold start; the phone stays alive and the PC sits in the tray for days. Add a check every 12 h while running, a "Check for updates" row in Settings > About, and "Skip this version". S, both.
3. Nothing is verified before install. The phone is covered by Android's signature check (another key fails to install); the PC zip is not. Check the download against the release's `SHA256SUMS.txt` on both. S.

Phone only:
4. Install without the tap (optional, owner's call): a `PackageInstaller` session with `USER_ACTION_NOT_REQUIRED` (Android 12+) installs silently only when BitChord is the installer of record of itself: true after its first in-app update, not after a browser or file-manager install, so the first update still asks. Optional "Install updates automatically": download on Wi-Fi in the background, install only while nothing plays (installing kills the app). M, needs a real phone as well as the emulator.
5. Smaller download: the ABI split before the universal APK (~2–3× smaller). It's upstream's order in `apkAssetUrl`, so ask first (upstream behaviour). S.

PC only (the item above):
6. Download in the app (progress in the dialog) into `data\update\`, check SHA-256, unzip there into `staged\` and check the files (`BitChord.exe`, `lib\bitchord-app.jar`, `runtime\bin\javaw.exe`): all the work done in Java, while the app still runs.
7. "Restart to update": the app copies `BitChordUpdate.exe` (new, small C built with the launcher in `desktopApp/native`, its own progress window) to `data\update\`, starts it with the folder, the staged path and its own PID, and quits through the normal quit (queue, handoff, AppCDS written).
8. The updater waits for that PID to exit (timeout, then says so), moves the old `BitChord.exe`, `lib`, `runtime`, `BitChord.args`, `BitChord (log).bat` into `data\update\old\`, moves the staged ones in, never touches `data\`, puts the old ones back if any move fails, and starts BitChord.exe. Once the new app shows its first frame (`ForkStartup.awaitFirstFrame`) it deletes `old\` and `staged\`; a start that never arrives leaves `old\` for a manual roll-back. Taken from Revived 1.9.1 #106 as ideas only (PID wait, backup and roll-back, deleting the backup only after a confirmed start); not their PowerShell host or folder swap (our `data\` lives in the folder).
9. Optional "Update automatically": download in the background, apply at the next quit.
10. Limit: the first version with the updater still has to be unzipped by hand once.

Order: 1 (fixes today's missed update), 3, 2, then 6–8 (about a day plus a hand test on Windows), then 4 and 5 if wanted.

### Next release: PC fixes after the owner's first real PC use (owner, 2026-11-06)

1. [x] (done 2026-11-06, seen in the app) PC sidebar: the small "Playlists" label above the list (`ForkSidebarPlaylistsRefresh`, `ForkLibraryRefresh.kt`) reads "YT Playlists". Label only; the list already is the YouTube library's playlists
2. [x] (done 2026-11-06, seen in the app: the rows match the phone, Internet radio opens the Navidrome page on its stations; `ForkLibraryLinks`) PC Library page like the phone's: listening cards on top, then Downloads / Local Music / Navidrome / Internet radio / Spotify rows, then Navidrome albums
3. [x] (done 2026-11-06, seen in the app: 8 cards, the Avicii card started a 58-song station; Find Your Mood under Recents, Show all grid; Hide switches in Content Preferences; `ForkTopPicks`. A second click while the first loads now drops the first, untested after that fix) PC Home: "Top Picks for You" big cards, missing on the PC. The builder (`shared/.../data/TopPicks.kt`) and the row (`sharedUi/.../TopPicksShelf.kt`) are shared; the phone's loader is `MainViewModel.loadTopPicks`/`buildTopPicks`
4. [x] (done 2026-11-06: the PC Home had no Find Your Mood; it now plays the phone's mood stations through `playlistAsAudio`, song versions. Relax: 60 songs, no video titles) PC "Find your mood" plays video versions, not the music versions: find where the mood playlists start and ask for the audio versions
5. (question only, answered) Stats don't sync: each device keeps its own; Export / Import data copies them and replaces, no merge
6. (dropped, owner 2026-11-06: leave as is, upstream's) PC: the "Outdated addon" line. The phone (upstream's rule, `SourceConfig.needsAddonUpdate`) flags an addon whose manifest has no `bitchordAuth`; both of the owner's addons (Heisenberg Qobuz, hires addon) lack it (checked 2026-11-06: keys `allowDownloads, checkValidLossless, id, name, resources, settings, version`), so the phone is right. The PC reads `bitchordAuth` (`DesktopAddon.kt`) but never shows the line: add it, with the phone's explanation on tap. They still work: flagged, never refused
7. [x] (done 2026-11-06, seen in the app; `ForkSettingsGroupTitle`) PC Settings: section headers (ACCOUNT, NAVIDROME, LISTEN TOGETHER …) are smaller than the rows under them and hard to read; make them big and clear like the phone's grouping

2026-11-07 (PR ayhamo/BitChord#9, squash-merged as 7fee454f; `pc` = `main`): the items above, an audit of them (6 fixes), fork unit tests (PC `ForkTestedDecisionsTest`, phone `ForkUnplayableSkipTest`, `ForkLocaleGaps`, `ForkAutoNavidromeTest`; phone 1376 pass, PC 719 pass via `docker/pc-test.sh`), both release workflows run the full suites first, and an Android Auto "Navidrome" folder (`ForkAutoNavidrome`; checked on the emulator through an in-app MediaBrowser probe, not in a car). The owner's Utilities copy (Desktop/Utilities/BitChord Portable) was updated to this build at their word, `data` kept. No phone release was dispatched: the owner builds Android themself. (Superseded the same night: "Android Release APK" is now the fork's phone release, see below.)
2026-11-07 later (`main` = `pc` = a1e8c521): workflows reworked: android.yml publishes `fork-v<version>` (Latest) on every push to main with the fork key, fork-pc-release.yml publishes `fork-pc-v<version>` (normal release, not Latest), fork sync only merges, tests and starts both. Both stop before deleting a release they couldn't recreate (GitHub refuses a tag on a commit whose workflow files differ from main's), and both release notes carry a changelog (`.github/scripts/fork-changelog.sh`, commit subjects per app). First outside user's report, ayhamo/BitChord#10 (issues now on): Navidrome over http on a LAN / Tailscale address failed in the release (prod manifest had cleartext off, only dev allowed it); fixed with `app/src/prod/AndroidManifest.xml`, a bare private IP now gets http://, checked on the emulator end to end (login, library, playback over http). Navidrome connection errors are plain words, same on phone and PC (`NavidromeErrors`). Utilities PC copy updated to a1e8c521 at the owner's word, `data` kept; the owner installs the phone build.
2026-11-07 evening: Desktop Revived published v1.9.1 (its watcher failed on purpose). The tag is 7d151b2d, where our review had stopped; the 7 PRs merged after it (#106–#115) were reviewed. Ported to the PC (owner-approved): the phone's blurred caption backdrop under release cards (`ForkBlurredBackdrop`, #115), update notes rendered as Markdown with the phone's renderer (`ForkUpdateNotes`; compose-richtext's Linux Compose dependency excluded, or the Windows zip check refused it), a `cover.jpg` added or replaced after a scan now shows (watcher + cache key, #107), FLAC front cover chosen and pictures read within their block (#107). Not affected here: AutoMix analysis published before it was saved (ours saves first), ID3 UTF-16 descriptions (ours steps two bytes), sidebar playlists not loading (Revived's own gating). Not taken: pitch-black theme (no phone equivalent), Linux scaling, Bintang About font / type scale, Spotify import tile (the PC already imports), Revived's PowerShell updater (ideas only, see the plan). The Bold SF Pro cut declares weight 600 here too, but lyrics' ExtraBold gets the Heavy cut; not checked on screen. Tests: PC 725/725, sharedUi jvm 61/61. Seen in the PC app (portable): the Ava Max release card's caption sits on the blurred cover; the update dialog (run as 1.9) shows headings, bullets and code. Release notes get a blank line between Commit/certificate and Verify (they ran together as Markdown, phone too). `desktop-revived-seen.txt` = v1.9.1.

2026-11-07 night (subagent, later): PC multi-select on Downloads, Cached songs and Local Music (`ForkSongSelection`): hold half a second, Ctrl+click or the Select button (greyed on an empty page) starts it; click toggles, Shift+click a range, Ctrl+A all, Esc leaves; the phone's bar and wording (Downloads: Delete, asks on a second click; Cached: Download, Remove; Local Music: Play next, Add to queue, which the phone doesn't have). Tested in the app with four silent WAVs in the test profile's Music folder (removed): Select, click, Shift range, Ctrl+A, hold, Ctrl+click, Esc, Add to queue (49 to 53 songs); Downloads / Cached actions not tried in the app (no downloads or cached songs in the test profile). Right-click stays the song menu. The test profile's saved queue keeps those four (now missing) songs after Better Mistakes.
2026-11-07 night (subagent): build numbers and the PC updater. Both release workflows pass `github.run_number` into the build and write a `Build: N` line into the notes; both apps offer a release whose version is higher or whose version is the same with a higher build (PC `ForkUpdater`, phone `ForkAppBuild`, upstream's phone dialog and download unchanged). Settings shows the build (PC: About group with Check for updates; phone: the version footer). PC: checks every 12 h too, downloads and checks the zip in the app and installs through `BitChordUpdate.exe` (new, built with the launcher). Tests: PC 751/751 (725 + 26 new), phone 1380/1380 + TV 16/16, `assembleDevDebug` builds. The first release with the updater still has to be unzipped by hand once.
2026-11-07 late night (51ac6e48, pushed with the round's other commits): review fixes for the updater and multi-select. Ctrl+A selects all songs only when no text field took it (a bubbling `onKeyEvent` in DesktopApp.kt; in a search box it selects the text); Cached songs' Remove greyed until the window is up; a late progress step no longer revives a cancelled or dismissed update dialog; Install now still running 20 s later shows Close now (hard exit) / Later instead of a dead end; the updater gets `--started=<ms>` and skips a PID that started at another time; update.log holds the last update only; the updater copy is deleted after a confirmed start; a failed download closes its connection. Tests: PC 806/806. Updater exe run on Windows on throwaway folders (reused PID: went on in 0.9 s; live PID: waited 6 s; old log replaced, data untouched). In the app: Ctrl+A with nothing focused selected all three test songs, in the page search it selected only the text, Esc left. Bridge note: /key reaches Compose only once the window is active (F11 twice activates it); before that keys silently do nothing. Not tried: Close now itself, Downloads / Cached actions in the app (test profile has none). Agent worktree from the cover-fade round removed.\n
Also 2026-11-06: PC audio thread spun on "forkSilence is null" at every start (719 log lines; `forkSilence`/`forkPulse` declared after the thread that reads them), moved up, gone. Navidrome bit depth not checkable: the test library has no lossless file (the code shows it only for lossless). Full-bleed album cover on the PC: skipped (the phone turns it off in any landscape window). `pc` branch fast-forwarded to main (471a53ef).

## PC fork survey: everything still takeable (2026-11-05, two agents, read-only; no SponsorBlock; keyboard shortcuts skipped by the owner)

S ~1-2 agent-h, M ~4-8, L ~10-20. "shared" = the phone runs it too (test both; upstream behaviour → ask first).

**Desktop Revived** (485 commits to revived/1.9.1). Quick: 1 locale-safe analysis JSON (9dcd4818, `analysis_jni.cpp:67` `%.6g` writes "," on a comma-decimal Windows locale → every native analysis rejected; S, highest value); 2 Trending keeps official videos (855e855f, shared); 3 duplicate new-release/video shelves (bbfb5a78, shared); 4 Spotify Canvas hardening (1432c5af, ad164a5d, f432c745, ccdc961a/750c2098; partly ours); 5 Spotify library fixes (a62df09d, 8b757325/32e61148); 6 web sign-in "Use this profile" again + blocked-window help (2bed5513); 7 full update notes (fd4e5680; ours cut at 600 chars); 8 cover colours from Canvas frames (bbb5f682); 9 Korean strings; 10 Linux bits. Medium/large: 11 Home "See all" pages (fbd0a9a0 family, shared, ask); 12 Apple moving artist video (port the phone's `AppleArtistArt`); 13 faster start-up (3d73286b family); 14 true borderless full screen (491a2bc9); 15 Linux window fixes; 16 Spotify account manage/switch; 17 Spotify token via Chromium (6bf7b579, risky); 18 playlist overflow menus; 19 song menu quick-action row (phone's wins); 20 hover effects; 21 list edge fades; 22 Now Playing details panel; 23 "New" page; 24 narrow-window icon rail; 25 in-app updater (L); 26 light theme, PiP (L, later list); 27 cosmetics. ~95 h, ~55 h without cosmetics/Linux/26.

**PC-Continued** (20 commits to v1.9 8f03de8f; 370ab56c is already ours via upstream PR #795; BBC radio is dead code in their v1.9). Quick: 1 PoToken re-mint (c310d0e3, shared, test phone too); 3 caps: lyrics LRU 300 (ours unbounded), Coil/artwork 12 MB, cover disk cache (288m heap: measure first); 4 Karaoke (e16ed1f8); 5 canvas HLS master first (5e9dbcbd); 6 typed lyric search waits for all providers (18b6b3ae, shared opt-in); 10 Win11 snap layouts (5b1b855c, native); 16 Listen Together error after 3 reconnects (aaaaee54); 17 Atmos leftovers (18b6b3ae); 18 output device glyphs. Medium: 8 Automix FX polish (8498b497, by ear); 9 backdrop pulse (sharedUi); 11 per-track source pin (5b1b855c); 12 accent colour (be257dad); 15 live radio now-playing (ICY/ID3, could title Navidrome radio). Owner calls: 2 yt-dlp out of process (bundled 23.5 MB exe goes stale, no cookies); 7 planner: vocal-gap blend, auto 8/16 bars (shared, changes phone Automix); 13 settings sub-pages; 14 turntable cover; 19 Chrome sign-in. Their look: 20 Apple Music catalogue/charts (L, scrapes apple.com), 21 their player look (L). ~125 h, ~75 h without 20-21.

## Final area audit (2026-11-05): all fixed, not committed, not tried in the apps

Eight Opus reviewers (one per area), each finding re-checked by a second agent. The owner said fix all: five fixers fixed every confirmed finding with tests, three reviewers then found and fixed 16 problems in the fixes. Built and tested: phone 1312/1312, shared + sharedUi + PC 959 tests with only the 16 FFmpeg-less failures. Native window fixes need a hand check on Windows (maximize→F11→Esc, Alt+Tab in full screen, snap→F11→Esc, caption double-click, non-ANSI folder, Quit then start). Left on purpose (low): a ms-wide name-claim window on PC downloads; records kept when every downloaded file is deleted by hand; accented 100-120 char names renamed by the byte cut; tracks past the 2 h sync deadline not cancellable; the PC download .part sidecar untested; quit save may time out if a tick waits for the UI thread; Karaoke keeps the old stereo verdict ~90 ms after a mix; PC liked list ignores server-side unstars.

```
PLAYLIST SYNC (verified)
1 MEDIUM fork PC: quiet focus refresh after error -> stuck skeleton (Refresh button still visible) DesktopApp.kt:2939/2974
2 LOW PC: focus refresh re-walks all Liked pages DesktopApp.kt:2960-2971
3 LOW upstream phone: one always-failing playlist -> every app open re-reads all followed playlists PlaylistSync.kt:106/152/180/184
4 MEDIUM upstream phone: tapping one "New songs in A" approves every playlist's big batch PlaylistSync.kt:330-333/377/117-123/197
5 LOW fork PC: page with no new rows ends walk as complete -> deleted tracks re-downloaded ForkPlaylistSync.kt:478/481/382
6 LOW fork PC: "Download" approval waits silently behind a running check ForkPlaylistSync.kt:300/412
7 LOW fork PC: lastAttempt stamped before reads -> failed check skips 12 h ForkPlaylistSync.kt:194/302
8 LOW both: failed read clears unanswered ask ForkPlaylistSync.kt:340-343; PlaylistSync.kt:193
9 LOW fork PC: Delete download during sync download leaves new tracks ForkCollectionActions.kt:217-237

HANDOFF (verified, all fork)
1 HIGH both: backgrounding a paused device overwrites the other device's newer note ForkHandoff.kt:617-619
2 MEDIUM PC mainly: "Not now" doesn't stick while the other device plays ForkHandoff.kt:164/436-442
3 LOW both: offer not withdrawn when this device starts playing
4 MEDIUM both: write back-off (to 30 min) drops pause/background saves, not reset on reconnect ForkHandoff.kt:643/665
5 LOW both: no final save on PC quit / within 1.5 s of skip
6 LOW both: rebuffer/seek extra writes; full note every 30 s (~5-6 MB/h)
7 LOW both: one transient refusal caps notes at 4 KB for the run ForkHandoffStore.kt:71-74
8 LOW phone: restore briefly writes position ~0
9 LOW phone: failed restore uses up the chip
10 LOW both: read failure says "Nothing to continue"

COVER CLIPS (verified)
1 HIGH upstream both: Spotify stale query hash + failed scrape -> every song re-downloads open.spotify.com + up to 8 MB bundles (Spotify signed-in only) SpotifyCanvasQuery.kt:126-138
2 HIGH fork PC: Apple 401 -> re-scrape picks the same refused JWT, no backoff -> per-song HTML+bundle GETs DesktopCanvasSources.kt:365-410
3 MEDIUM both: Spotify client-token failures not cached, ~5 POSTs per song; PC new HttpClient per call
4 HIGH fork PC: clip decoder leaks FFmpeg state + file handle when cancelled while opening DesktopCanvasVideo.kt:73-81, DesktopCanvas.kt:285-295
5 MEDIUM fork PC: album-header clip never pauses, 3 unclosed copies per frame DesktopCanvas.kt:271-307
6 MEDIUM upstream both: Latin artist + non-Latin title -> another song's clip CanvasArtwork.kt:79
7 LOW fork phone: Spotify backdrop 429 cached 10 min ForkCanvas.kt:117-145
8 LOW both: failed Pathfinder not reported (mostly harmless)
9 LOW both: iTunes rate limit cached as "no artist art" until restart AppleCatalogue.kt:56-64
10 LOW upstream both: community manifest re-fetched per lookup while host down

PLAYBACK (verified)
1 MEDIUM fork PC (today): Karaoke + EQ on -> mono source (or identical L/R) plays as silence; Skip silence races through ForkKaraoke.kt:65-79
2 MEDIUM fork PC (today): yt-dlp URL refused mid-track stays cached -> recovery reuses it, song ends early; replay blocks YouTube for the session ForkYtDlp.kt:153-197
3 LOW fork PC: crossfade-on-skip ticket race (microseconds at 3 s) -> blend cut, song restarts DesktopAudioEngine.kt:2331-2398
4 REJECTED: saved-queues first-read race (no save can happen that early)
Phone skip/mask/queue/AutoPlay incl. today's fix: clean.

DOWNLOADS (verified; desktopApp = upstream's old desktop app the fork owns)
1 HIGH PC: cancelling one running download leaves the rest of the queue stuck "Running 0%" DesktopDownloadQueue.kt:144-182
2 MEDIUM PC: same "Artist - Title" -> shared/corrupt file DesktopDownloadManager.kt:32-89
3 MEDIUM PC: cancel leaves .part; complete .part -> HTTP 416 forever; resume across sources corrupts
4 MEDIUM PC: menu "Remove download" off the Downloads page drops record, file stays DesktopApp.kt:1228-1234
5 MEDIUM fork PC: Delete download on a playlist deletes tracks a downloaded album also holds ForkPlaylistSync.kt:259-264
6 LOW fork PC: cancel during tagging swallowed -> still recorded ForkDownloadTags.kt:61-63
7 MEDIUM phone (Android 15+): after the data-sync time limit, queued tracks stuck "Queued", re-tap does nothing Downloads.kt:920/199
8 MEDIUM fork phone (Android 15+): "Available offline" waits in foreground for an allowed network, burning the 6 h budget ForkOfflineService.kt:60-63
9 MEDIUM upstream phone: Delete download on a release doesn't stop its running downloads Downloads.kt:516-522
10 LOW phone: same-named tracks on two workers overwrite each other
11 LOW phone: long CJK titles exceed 255-byte filename -> download fails
12 LOW PC: downloads list capped at 200
13 LOW PC: startup check forgets all records when the Music drive is missing

SETTINGS & BACKUP (verified, all fork)
1 HIGH PC: failed portable settings write (OneDrive/AV lock) never retried -> changes or a whole import lost at exit ForkFilePreferences.kt:105-191
2 LOW PC: backup drops NetEase lyrics tick/order
3 MEDIUM both: 4 shared settings (artist signatures on/URL, smart version alignment, direct source stream) missing from the mapping -> PC->phone restore resets them ForkBackup.kt:166-207
4 LOW phone: Fork settings page rows not greyed (Crossfade on skip, Animated covers, Backdrop videos; Navidrome rows with no server) ForkSettingsRows.kt:307-384
5 LOW both: defaults not carried (full-bleed artwork flips)
6 LOW PC: yt-dlp path travels between PCs, blocks PATH fallback
7 LOW PC: import failing late leaves half-applied data
8 LOW PC: old audio-quality value exported unmigrated
9 LOW PC: export overwrites the target in place

NAVIDROME (verified)
1 CRITICAL fork PC: PC sends Navidrome "finished" scrobbles + ListenBrainz listens only for the queue's last song (normal advances go through onCrossfaded) DesktopApp.kt:1609-1638, DesktopAudioEngine.kt:1217-1221
2 MEDIUM fork phone: offline switch on + network up -> AutoPlay/Start radio on a Navidrome song queue server songs (PC checks); LOW: handoff + lyrics still ask the server Autoplay.kt:105-111
3 MEDIUM fork phone: server down on working network -> library pages error instead of kept songs NavidromeRepository.kt:56-75
4 LOW both: kept playlist listed ≥50% during a rescan -> local copies swept, re-downloaded later NavidromeOffline.kt:479-487
5 LOW phone: fast star/unstar race
6 LOW both: dormant /auth/login + /api/ interceptor still installed (rule violation in code) NavidromeClips.kt:136-217
7 LOW both: genre tiles 31 requests at once (within burst)
8 LOW both: server-side unstars/account switch not reflected until restart
9 LOW PC: star failure not rolled back (same as upstream YouTube)
10 LOW both: instantMix swallows cancellation

PC WINDOW & NATIVE (verified, all fork, PC)
1 HIGH: F11 from a maximized window -> stuck fullscreen + topmost, no way out but quitting; saves monitor-size bounds window_jni.cpp:314-320, DesktopWindowMode.kt:55-58
2 MEDIUM: fullscreen forces topmost -> Alt+Tab apps stay under BitChord window_jni.cpp:121-133
3 MEDIUM: double-click player caption in fullscreen maximizes the popup (taskbar band unpainted) Main.kt:178-196
4 LOW: monitor unplug/resolution change during fullscreen
5 MEDIUM: portable folder path with characters outside the ANSI code page -> Java fails to start BitChord.c:221-237
6 LOW: Quit then immediate restart -> start lost
7 LOW: maximized window moved to another monitor clamped to the wrong work area window_jni.cpp:99-107
8 LOW: install_frame message gap (negligible)
9 LOW: analysis_jni.cpp comment inaccurate on MinGW (no real race)
```

## Fork picks round (owner, 2026-11-05, running)

Priority from now on: upstream Android first, then PC-Continued; Desktop Revived is cherry-picked only when it publishes a major release. Light theme dropped (not doing). Not doing: Korean text, true borderless full screen, Spotify account switching, SponsorBlock; keyboard shortcuts skipped for now.

Doing now (subagents, then one audit, build and test):
- Revived: analysis JSON on a comma-decimal Windows locale (9dcd4818; check, fix if affected); Spotify Canvas lookup hardening (1432c5af, ad164a5d, f432c745, ccdc961a/750c2098); Spotify library fixes (a62df09d, 8b757325/32e61148); web sign-in "Use this profile" again (2bed5513); full update notes (fd4e5680).
- PC-Continued: Karaoke (e16ed1f8); canvas HLS master first (5e9dbcbd); Atmos leftovers (18b6b3ae); Win11 snap layouts (5b1b855c); Listen Together error after 3 failed reconnects (aaaaee54); PoToken re-mint (c310d0e3, shared, test phone too); Automix FX polish (8498b497); accent colour from the song (be257dad); backdrop pulse (8498b497, PC only); per-track source pin (5b1b855c).
Second batch (owner, 2026-11-05, after the first): faster start-up (Revived 3d73286b family: lazy Local Music scan, analyser/download check after the window shows, Replay stats off the UI thread, Library on demand) + AppCDS in the launcher; Trending keeps official videos (855e855f, shared); duplicate new-release/video shelves (bbfb5a78, shared); cover colours from Canvas frames (bbb5f682); Home "See all" pages (fbd0a9a0 family, shared, owner-approved); Apple moving artist video on the PC (port the phone's `AppleArtistArt`); live now-playing titles for internet radio incl. Navidrome stations (PC-Continued 5b1b855c/e16ed1f8 `DesktopRadioNowPlaying`); yt-dlp as a resolver (PC-Continued c310d0e3 idea; uses the owner's own yt-dlp, no bundled exe). Planner changes: later list, at their next major release.
Undecided (explained to the owner): Revived Trending videos, duplicate shelves, Canvas cover colours, Home "See all", Apple artist video, hover/fades/menus, faster start-up; PC-Continued radio now-playing, planner, turntable, Chrome sign-in, yt-dlp.

## Where it stands (2026-11-04, end of day)

- Last round (2026-11-04): PC Library refresh (start, window focus every 5 min, Refresh button); Windows' own caption glyphs gone (no WS_CAPTION, re-stripped on style changes; owner checked taskbar minimise and Win+Down); native libraries unpacked by CRC (a same-size rebuilt DLL stayed stale); small fixes: "1 song", one lit sidebar row, Automix log bars match the popup, Replay backdrop off under Settings, playlist ⋯ (Downloaded row, divider before Delete), wheel scrolls Settings from the margins; phone: Settings search shows each row once, lyrics hold pauses the follow only; audit fixes: handoff keeps downloaded YouTube rows and shrinks notes on refusal, Delete download cancels running downloads and reaches older files in one save, Keep playlists updated approves one playlist and keeps records with existing files, Last.fm temporary errors not filed. Tests: phone 1300/1300, shared/sharedUi pass, PC only the 16 FFmpeg-only failures. Upstream behaviour changes listed at the top of FORK.md.
- Late night (2026-11-04): PC parity round from the audit: whole album/playlist Delete download (page ⋯ and Home card), Suggested under playlists, About the album, Recent release card on artist pages, Library/Downloads group menu; Prioritize Spotify Canvas, Sources status hints and footer, Cached songs (index next to the song cache, offline play of whole copies); "Playing from Navidrome" opens the Navidrome page; Wide player layout setting (Classic / Halves / Halves smaller cover; one hook line in sharedUi LandscapePlayer.kt). Combined audit, 4 fixes; tested in the app (Delete download confirm, Suggested and Downloads group delete untested: no downloads / no own playlists). Later list: multi-select in Downloads/Local, Local Music Playlists/Folders tabs; skipped: Played-by caption, WebDAV/SMB. Open: Windows' native caption buttons show for ~10 s after start (native frame, Mica).
- Later still (2026-11-04): the PC album/playlist page has the phone's song sort (Default, Reverse, Title, Date added; per page; remaining pages load first; track numbers kept), tested in the app (Reverse on Youtube FLAC, row 2 plays song 629, the queue follows); the PC pipeline panel gained the phone's EQ preset, loudness gain / measured-from and PCM data rate rows (tested: FLAC 16-bit, 1411 kbps) and scrolls when the window is short. A phone-vs-PC audit found the remaining gaps: multi-select in Downloads/Local, Delete download for a whole album/playlist, Suggested songs under playlists, group long-press menus, About the album, Recent release card, Local Music Playlists/Folders tabs, artist card menu, "Played by" in a party, WebDAV/SMB, Prioritize Spotify Canvas.
- Night round (2026-11-04): a canvas provider in its own backoff (Apple's 30 min token retry, Spotify's token backoffs) holds a clip-less song back until it can answer again instead of re-asking every provider each minute (`ForkCanvasLookup.coolingDown`; a new Spotify sign-in clears the notes); the Navidrome artist-picture pass reads every artist the songs credit (`search3` pages, OpenSubsonic `artists` ids), not only `getArtists`' ~13 album artists, and the PC's Navidrome Artists tab shows the kept pictures; the PC Audio Pipeline panel shows the decoder's measured format for plain-address songs (the engine's `pipeline()` gave the stream's blank copy; fixed there, `desktopApp` being ours since upstream dropped it) and names Navidrome as the source; the PC audio thread no longer trips on a null Automix reporter at startup; the PC's "Work out genres" is greyed with a note while there is no Last.fm key. Audited once, 4 fixes.
- Late round (2026-11-04, committed locally, not pushed): settings rows that depend on another setting are greyed, never hidden (phone + PC, owner rule); cover-clip lookups that fail are no longer cached as "no clip" (60 s note), the extension's Spotify web-player token as a fallback (newest ThetaDev secret), the last good Apple token kept as a backup; "YouTube playlists" shelf name; Navidrome artist pictures resolved and stored (server picture unless it is the placeholder, then YouTube, then Apple; a slow pass over the album artists); Continue on the other device (a note in a hidden `~bitchord-handoff` playlist's comment on the Navidrome server); PC cover clips (Apple HLS) downloaded before playing; PC Audio Pipeline like the phone; the PC Navidrome header wraps. Audited twice (phone/shared, PC), 7 fixes; tests: phone 1297/1297, shared jvmTest pass, PC 616 with the usual 16 FFmpeg-only failures.
- Tested in the apps: handoff probe (server keeps a 256 KB comment intact via POST; probe playlist deleted); PC -> phone (chip "Continue from PC: Diamonds, 2:53", the phone played from 2:53) and phone -> PC (player-menu row, 22 songs from 3:19); PC cover clip still animating after 90 s with no decoder errors; Crossfade greyed at 5 s with Automix on (PC); the Navidrome header wraps; phone artist pass found 9 pictures of 13 album artists. Added after testing: the PC looks for a note every 2 min while open and idle (it only looked at start / back from the tray).
- Not tried: the Audio Pipeline panel, the phone's greyed rows, the phone's handoff chip dismissal, a lossless-copy hint across devices, Levent Geiger's page on the phone. The background picture pass covers album artists only (song-only artists get theirs when their page opens). Owner-only: the Automix crackle (skipped, upstream), Cheap Thrills lrc.red remix lyrics (upstream source), YouTube Music not listing youtube.com playlists (YouTube's side).

- Evening round (2026-11-04, committed locally, not pushed): the PC gained album/playlist Play next / Add to queue (page More menu, Home card menu), "Keep playlists updated", Replay stories / poster / This week / genres, Automix popup waveforms, Song cache folder row, Track length tolerance, Font (Default/Legacy; Default is the phone's lighter type, a visible change), Copy log, the welcome pages, memory trim when hidden (Revived a6b5311c), NetEase lyrics (PC-Continued aaaaee54), Genius song meanings (off by default; PC-Continued e16ed1f8/18b6b3ae). Five agents wrote it, two Opus audits fixed 8 bugs, a third checked the later backup edits. Phone change: the backup keeps the PC's `fork.pc` part and writes it back (it was dropped, found in testing).
- Tested in the apps (2026-11-04 evening): PC backup both ways through `/pick` (phone file into the PC: 15/16 settings identical, search history gains a timestamp, 9/9 fork settings, the saved queue, both months to the millisecond; PC file into the phone and back out: settings, fork settings, months and per-day plays identical, `fork.pc` now kept); logins survive an import; no secrets in either file. Welcome shows once; settings rows; album Add to queue (29 songs after the current one, notice); NetEase served a song's lyrics; Genius "About this song" for Blinding Lights; Replay Weekly, a story, the poster copied as 1080x1920; Copy log (96 lines, no addresses or logins); the player menu's Fullscreen cover art / Backdrop videos / Animated covers / Saved queues rows show again (they had been hidden since the PC took the phone's player); waveform files written by the analyser. PC tests 602, the usual 16 FFmpeg-only failures; phone backup tests 13/13.
- Not tried in the app: Home card right-click menu (the bridge can't right-click), Keep playlists updated (needs a downloaded YouTube playlist), the waveforms inside a live Automix popup (and songs analysed before today have none until analysed again), memory trim, lyrics card sheet (long-press), Replay genres (need a Last.fm key), poster/lyrics-card Save (now drivable with `/pick`). The owner listens to the audio.
- Seen while testing, not ours: KuGou gave "TheFatRat - Unity" lines for another song of that album (upstream source).

- Local commits on `pc`, NOT pushed (GitHub had an outage; the owner says when): 06da06ac (artist radio, PC parity, engine picks, audits), 9fb0069c (two release watchers), 3ce27cf8 (later list, licences), 436c7175 (PC parity round: settings pass-through, loudness, tags, phone-format backup, video/audio switch + smart alignment, Direct Source Stream, library extras, signatures, lyrics card, single instance, window state, scrollbars, UI scale, Esc, local covers; two audits fixed). `main` is still at 3af3e6fd. No new releases built.
- Tested 2026-11-04: phone on the emulator (Home, artist radio + Similar artists, queue has no scrollbar and drag-reorder works, Export data writes a fork section with 9 settings + 1 saved queue and no login/secrets); PC through the bridge (single instance hands over, settings rows present, Convert to video and back with 0 ms measured offset and the queue intact, artist radio 97 songs, Home/Library rows, no exceptions). Not tried in the app: lyrics card sheet (needs a long-press), backup import on the PC (native file dialog), pins, signatures with an API. The owner listens to everything audio (loudness, version cross, Direct Source Stream, loop mix, Automix curves, Atmos, gapless).
- The "sections below marked not committed" are all in 436c7175 now.
- Recheck (2026-11-04, after 436c7175): phone features still missing on the PC: Local Music tags + Albums/Artists tabs (opus/aiff/dsd skipped) M-L; album/playlist actions (play next, queue, shuffle, download a whole album/playlist; Home card right-click only for songs) M; Replay stories/poster/"This week"/genres M; Automix popup waveforms M; "Keep playlists updated" M; small: show cache folder, upgrade length tolerance, app font, Copy log, onboarding; Cast (niche, L). All 30 fork picks are credited; no uncredited fork code found (FORK.md credits 7422bea1 where the code cites 700e0d16/418d328d). Worth taking next: Revived a6b5311c memory trim when hidden (S), PC-Continued aaaaee54 NetEase lyrics (S); optional PC-Continued Genius meanings (M, scrapes). Waiting on the owner's go.


## PC: cover clips breaking (2026-11-04, not committed)

- Symptom (PC built from fa1663bf, `out\pc-run\run.log`): an animated cover played a few seconds, then the still cover showed; FFmpeg logged `[https] Stream ends prematurely at 331296, should be 4787788` (and 314912 of 7361469), `[tls] IO error: End of file`, then ~135 `[h264] Invalid NAL unit size` / `Error splitting the input into NAL units`.
- Cause: Apple Music's motion covers are HLS, and the PC (`DesktopCanvasVideo`, upstream code) handed the manifest's address to FFmpeg, which streamed it over HTTPS while it played. The decoder pulls bytes at the frame rate and not at all while the song is paused or a mix holds the sleeve, so the connection sat half-read until the CDN closed it; the HLS demuxer then read on past the cut-short fragment, and H.264 got bytes that weren't a sample (the NAL errors, the broken clip). FFmpeg can't seek back in an HLS stream, so every pass of the loop also reopened the manifest and refetched its segments, and so did every reopening of the player. MP4 clips (Spotify, Community, Tidal) were never affected: they are downloaded whole into the clip cache first. The phone is fine because ExoPlayer loads HLS segments into its own buffer ahead of time and retries a failed one.
- Not a regression from 63b0f22a ("lighter animated covers"): the clip was streamed this way before it too. That commit only chose a smaller rendition and dropped the others.
- The repeated `canvas: '…': a cover clip` lines are not network lookups (no provider line follows them; `DesktopCanvasClient` answers them from its cache). They come from the shared player's `rememberCanvasArtwork` running again each time the player sheet opens and when the album name arrives from `trackLinks` (the `links:` lines next to them). The clip's failure didn't start them, so the shared UI is unchanged.
- Fix (`ForkClipSource.kt`, new): an HLS clip's rendition (H.264 first, the smallest reaching 540 px) is saved into the clip cache as one file (init + fragments; Apple's byte ranges of one file are joined into one request) and played from disk, so it loops by seeking and a reopened player starts from the file. Only one fetch per address runs at a time. A clip that fails to fetch or decode is left alone for a minute and the still cover shows. Encrypted or live playlists are still streamed as before. Hooks: one line each in `DesktopCanvasVideo` and in `DesktopCanvasView` (album header), plus one in `DesktopCanvasVideo`'s "decoded no frames" branch. Tests: `ForkClipSourceTest` (variant choice, byte ranges, merging, unsupported playlists, the one-minute wait).
- Not built, not run. To check: play an Apple Music motion cover ('Midnight Sun', 'Blinding Lights') for a minute and pause and resume. It should keep looping with no `[h264]` / `[tls]` / `Stream ends prematurely` lines. A `<hash>.mp4` should appear once in `home\.cache\bitchord\media` and not be fetched again when the player is reopened. "Clear animated covers" should remove it. Not checked against a real Apple playlist: if Apple's rendition isn't fragmented MP4 or TS, the log says `could not fetch the clip's segments` or the decoder refuses the file, and the still cover shows for a minute.

## PC: Audio Pipeline like the phone (2026-11-04, not committed)

- Not built or run. The PC's Audio pipeline panel (both ways it opens: the window's `overlays.pipeline` and the shared player's `AudioPipelineDialog`) now draws the phone's layout: Track info, Decoder, Resampler, DSP, Output device as nodes on a vertical bus, "Label: value" rows, "—" for what the engine can't say yet. While playing a pulse runs down the bus every 2.4 s and lights each node as it passes; paused, the bus is static. `desktopApp/.../ForkAudioPipelineFlow.kt`, one hook line in `DesktopAudioPipelineUi.kt`.
- To check: the nodes sit on the line exactly behind each icon (no bus showing through an icon), the pulse starts/stops with play/pause, stops with Settings > Reduce animation, and, if it is ever shown as a Settings page rather than a card, it still lines up. Closing the panel cancels the animation (it lives in the panel's composition); worth a glance at CPU with it open and paused (should be idle).
- Differs from the phone: rows are the PC engine's (javax.sound output API, device, buffer in KB/ms/frames under Output device, where the PC had them; the phone lists Buffers and Output API under DSP). No PCM data rate, loudness gain/LUFS figures, bit-exact verdict, AudioFlinger/HAL/USB/Bluetooth rows: the PC engine doesn't report them. Loudness shows On/Off and sits after the equaliser, the order the PC applies them. Reduce motion follows the app's switch (the phone reads the system animator scale). Node base colour is the PC card's fill.

## Continue on the other device (2026-11-04, not committed)

- Not built or run. Stop on the phone, open the PC (or back): a prompt offers the same queue at the same song and second. Phone: a chip above the mini player, "Continue from PC: <song>, 2:14" (tap continues, the cross or a sideways swipe lets that note go). PC: a banner at the top of the window (Continue / Not now). Both: "Continue from other device" in the player's menu (the latest note from the other device, whatever its age), and "Continue on other devices" in Settings > Navidrome (on by default, greyed while no server is set up). Nothing plays without the tap.
- Design: the note is short-key JSON in the `comment` of one private, empty playlist per user on the Navidrome server, `~bitchord-handoff` (Subsonic `/rest` only: `getPlaylists`, `createPlaylist`, `updatePlaylist` sent as a form POST, falling back once to a GET when the server ignores the body and the address stays under 7,000 characters, `getPlaylist`). Every Navidrome playlist list in both apps reads `NavidromeClient.playlists()`, which leaves it out. Saved on pause, song change, the app leaving the screen (phone `ON_STOP`, PC hidden to the tray) and every 30 s while playing; one try each, a failure backs off 2 min doubling to 30. Nothing is written until the device has played in this run (each app reopens its own queue at start, which would otherwise overwrite the other's note). Offered on start / return to the front when the note came from the other device, is newer than this device's own last save or play, wasn't dismissed and is under 7 days old.
- What travels: the playing song ±50: Navidrome ids (addresses rebuilt with the other side's login, kept files first), YouTube ids, addon / module / JioSaavn rows (kind + a 12-hex SHA-256 of the addon's address, never the address; dropped on the other side when it has no such source switched on), the queue's origin (name, type, id, radio seed: "Playing from …" and AutoPlay carry on), the original-version pin (pinned on the other side too), and for a YouTube song playing as an addon's copy, that source and its track id, which the other device asks first, once (phone `SourceResolver.substituteForYouTube`, PC `DesktopSourceRegistry.resolveLive`), else its own upgrade search. Local files and radio stations are dropped; a dropped playing song hands over to the next one from 0:00.
- Size: unknown how long a comment Navidrome keeps, so the note degrades: 48 KB budget (4 KB once the server is found to read only the address), thumbnails dropped first, then ±25/10/3/0 songs. A note of 101 YouTube songs with thumbnails is about 25-30 KB.
- The probe (PC): start the PC with the debug bridge (`-Dbitchord.debugBridge=<port>`, as for the other bridge tests), signed in to Navidrome, then `curl -X POST http://127.0.0.1:<port>/handoff-probe`. Through the app's own client it writes 4, 16, 64 and 256 KB comments (ASCII with accented and CJK letters) to a separate playlist `~bitchord-handoff-probe`, reads each back, answers JSON (`largestIntactBytes`, each size's result and whether it went as `post` or `get`), and deletes the playlist. No login in the answer. `GET /handoff-note` summarises the note on the server (device, songs, playing title, position, whether this PC would offer it). If the largest intact size is under 48 KB, lower `ForkHandoff.DEFAULT_BUDGET` to it.
- Hooks: phone `PlaybackService` (4 lines: attach, detach, `onIsPlayingChanged`, `onMediaItemTransition`), `SourceResolver` (2); PC `DesktopSourceRegistry` (2). The rest is fork files (listed in `FORK.md`). Tests: `ForkHandoffTest` (shared jvmTest, `:shared:jvmTest`, not part of `testDevDebugUnitTest`): round trip, degradation, drops, window, addon refs without addresses, source matching, offer rule, hidden playlists, the saver's arming and back-off, the POST-then-GET fallback and the playlist filter (MockWebServer).
- To check: it builds and `ForkHandoffTest` passes; run the probe once and note the size here. Then: play on the phone, pause, open the PC: the banner names the phone's song and second; Continue lands there with "Playing from <album>" and the album's queue; play on, hide the PC to the tray, open the phone: the chip; tap; swipe a later one away (it doesn't come back, a newer one does). A Navidrome album, a YouTube playlist, an addon search row (PC without that addon: the row is gone, the rest plays), a YouTube song playing as a lossless copy (the other side's log says "asked of … first"), a song pinned to its original. The server's own web UI does show the playlist `~bitchord-handoff` (its comment is the note); BitChord's lists don't. Check Navidrome doesn't list the probe playlist after the probe.
- Left out: live remote control (the other device is not driven, only handed a queue), local files, Automix state (the blend plan is made again on the other side), other users (one note per logged-in user; a shared login means a shared note), a save when the PC quits from the tray menu without a pause before (no shutdown hook: one was tried for offline downloads and didn't run reliably), the phone saving while Android has the service stopped (nothing plays then anyway). The two clocks are compared as they are (a phone and PC minutes apart can misjudge "newer" by that much). The phone's setting is not in Export data.
## Navidrome artist pictures (2026-11-04, not committed)

- Not built or run yet. A Navidrome artist with no picture (server: none, or only Navidrome's generic placeholder) now gets one, phone first and the PC the same way: the artist page's header (phone sleeve, PC square), the phone's Artists tab rows/cards (before a song's sleeve), and the "Similar artists" cards. Order: the server's own `ar-` picture when real, the Replay's photo of the name (phone `ArtistFacts`), YouTube's artist search, Apple's portrait (proved by a shared title). Exact folded name only; better no picture than a wrong one. New shared `NavidromeArtistPictures.kt`, phone `ui/components/ForkArtistPictures.kt`, PC `ForkArtistPictures.kt`; hook lines in FORK.md.
- Placeholder: no Subsonic field tells it (every artist gets an `ar-` id and `/share/img` links), so each artist's picture is read once at 48 px and hashed; a hash two unrelated artists share is the placeholder (kept per server). Before one is known, three other artists are read once per session to compare with. A server picture taken as real before the placeholder was learned is dropped and looked up again.
- Kept: phone prefs `bitchord_navidrome_artist_pictures`, PC preferences `com/music/bitchord/navidrome_artist_pictures` (not in Export data; no login in them). "None" for 14 days; a failed lookup isn't kept (that artist left alone 60 s). One lookup at a time, 1 s apart. Background pass after Home's server shelves load: `getArtists` once, one unsettled artist every 5 s; stops offline, on a metered network or battery saver (phone), after 3 failures in a row, or when the server changes; the next library load carries on. Server: `/rest/getArtists`, `/rest/getCoverArt`, `/rest/search3` only, no retries. No settings row (settings files were being edited by others); the pass can't be switched off from the UI yet.
- Replay (phone): a Top artists row opens with its photo (`ArtistFacts.imageFor`, not the row's fallback sleeve). PC: no hook, its Replay rows show the lead song's sleeve and a tap opens YouTube's artist page by search, which brings its own photo.
- Tests: `NavidromeArtistPicturesTest` (app): name matching, placeholder learning, kept answers and their expiry, `getArtists` parsing, error documents, Apple portrait addresses.
- To check: it builds; the test passes. Phone: open "Levent Geiger" from the Navidrome Artists tab: the header gets a photo within a few seconds (log tag `BitChordArtistPics`), and on a second visit at once with no lookup; the Artists tab rows fill in; tap Levent Geiger in Replay's Top artists: the page opens with the Replay's photo. An artist whose server picture is real keeps it. Log shows "placeholder learned" early on; on Wi-Fi after Home loads, "pass: N of M artists to look up", then about one lookup every 5 s; on mobile data or battery saver the pass doesn't run. A misspelt/unknown artist stays a placeholder and is not asked again for 14 days. PC: a Navidrome artist page's square shows the artist instead of an album cover; Similar artists cards too.
- Risks: YouTube can rank a same-named different artist first (the same risk the Replay photo has); the placeholder test needs two unrelated broken artists, so a library with only one keeps Navidrome's placeholder for it; each artist costs one small server image read (48 px, cached by Navidrome) once.

## Settings: dependent rows greyed, not hidden (2026-11-04, not committed)

- Not built or run. The owner's rule: a settings row that only applies when another setting is on/off stays in place, greyed (alpha 0.38) and not interactive, showing its current value. Phone: `ui/screens/ForkDisabledWhen.kt` (twin of `BypassedByBitPerfect`: taps and sideways drags taken, vertical scroll let through; `SettingsGroupScope.forkDisabledWhen` makes each upstream `if` one hook line). PC: `desktop/ForkDisabledWhen.kt` (every pointer event but the wheel consumed).
- Phone, Settings: Crossfade slider (Automix on), Crossfade on skip (no crossfade and no Automix), Allow fixed volume (not direct USB), Convert for limited DACs (bit-perfect off), Progressive blur (Liquid Glass off), Animated covers + Backdrop videos + Animated cover over cellular + Spotify Canvas link (Animated cover art off), Signature API URL (signatures off), Blur unfocused lyrics + Lyrics sources + Translation language (Synced lyrics off), Refresh rate (High performance mode off), Use all audio folders (no folder chosen). Account & scrobbling: ListenBrainz primary-artist row, Last.fm's three rows and Scrobble timing (switch off, signed in). Discord: Presence and Buttons groups (until "Customize the card").
- PC, Settings: Crossfade slider (Automix on), Crossfade on skip, Automix phrase length (Automix off), Blur unfocused lyrics + Lyrics sources + Translation language, Use all audio folders, Backdrop videos + Animated covers + canvas auto-hide + Spotify Canvas setup (Animated canvas off), Keep playing when closed (Tray icon off), Signature API URL. Integrations dialog: Discord Presence/Buttons, ListenBrainz and Last.fm sub-rows, Scrobble timing.
- Left hidden on purpose: rows that need a sign-in/token (Listen as, Sign out, Spotify Disconnect, Discord token/Disconnect, scrobbler sub-rows with no token/session), DAC volume (no DAC plugged in), Full-screen cover art (window shape), PC Title bar / Window material / App background / Ambient glow (platform), Sources' Test row (server not configured), Wear OS row, settings-search filtering.
- To check: it builds. Phone: Automix on, the Crossfade slider stays greyed at its value, a tap and a sideways drag do nothing, a vertical swipe starting on it scrolls; Automix off, it works again. Same for Synced lyrics, Animated cover art, High performance mode, Liquid Glass, signatures, Last.fm/ListenBrainz switches, Discord "Customize the card". With bit-perfect ready and Automix on, the slider is dimmed once (not twice). PC: the same rows greyed, clicks and slider drags do nothing, the mouse wheel over them still scrolls; the signature URL editor closes when signatures are switched off. Settings search still finds the greyed rows (they now show up while their parent is off too).

## Canvas: failed lookups not cached, Spotify token fallback (2026-11-04, not committed)

- Not built or run yet. Phone and PC: a cover-clip lookup now tells "the services said no clip" (cached as before) from "a service never answered" (network, HTTP error other than 404, 401/429, a token missing or refused, an exception): that one is not cached, and the song is left alone for 60 s before a play may ask again. A later provider's clip still wins over an earlier one's failure and is cached. No Spotify login (no `sp_dc`) still counts as an answer. New shared `ForkCanvasLookup.kt`; one-line hooks in the phone's canvas providers/repository and the PC's (FORK.md).
- Spotify token fallback, the YT Music Extension's method (new shared `ForkSpotifyTotpToken.kt`): after the page harvest fails (phone WebView, PC WebEngine), a signed `/api/token` request with Spotify's server time and the newest secret from ThetaDev's spotify-secrets (once per run; the extension's v12 if that fetch fails). On the PC it replaces upstream's own signing (v12 only, `reason=init`), and the PC's client-token step now sends the page's `sp_t` device id instead of a random one. 10 min backoff on failure, PC's 30 min backoff kept.
- Tests: `ForkCanvasLookupTest` (app), `ForkCanvasLookupDesktopTest` (PC), `ForkSpotifyTotpTokenTest` (shared: `:shared:jvmTest`, not part of `testDevDebugUnitTest`).
- To check: it builds; the three test classes pass. Offline (or with a host blocked), play a song, then go online and replay it after a minute: its clip appears (before, "no clip" stuck). A song with no clip anywhere is asked once, not on every play (log: no "not caching" line for it). With the WebView/WebEngine harvest failing (e.g. block open.spotify.com's player JS, or a slow start), the log shows "signed token minted" or "would not mint a signed token" and Spotify canvases still come through; a wrong/expired `sp_dc` gives no token and no request loop. PC: "Made for you" and the Spotify library still load (they share `DesktopSpotifyToken`).

## PC: Replay stories, poster, This week, genres (2026-11-04, not committed)

- Not built or run yet. The Replay page on the PC is now the phone's (`ForkReplayPage`): Weekly/Monthly/Yearly/Lifetime, the cards in the phone's order (each opens the stories at its card), "Play your Replay", Top genres (or "Genres will appear…"), the phone's habit lines, "Share my Replay". Stories over the whole window, 9:16; click right/left, hold to pause, arrow keys/Space, Esc. Share: the phone's poster (whole Replay, or the card it was opened from) with Save (file dialog, Pictures) and Copy image. Tests: `ForkReplayTest`.
- "This week": the PC now keeps per-song days, as the phone does (`td` lines next to the month; older builds skip them). Listening from before this has no days and counts whole in "This week" until its month is over (the phone's own fallback). The backup now carries the days both ways (phone→PC→phone and PC-added days out to the phone); `ForkBackupTest` should still pass unchanged.
- Genres: Last.fm `artist.getTopTags` per lead artist, as on the phone; needs a Last.fm key (the PC's scrobbling key or the build's) and "Work out genres" (Settings > Your data, new, on by default, in the backup as `replay_genres`).
- To check in the app: Replay > Weekly with some plays today (only this week's songs; busiest day this week); a card click opens its story; clicks/keys/hold/Esc in the stories; the share icon on a card and "Share my Replay" (picture draws, Save writes a PNG, Copy image pastes into Paint/Discord); the genre chart filling in a few seconds after opening (log line "Replay genres: …" only on a failure); switch off "Work out genres" (chart gone); Export data and look for per-song `days` on a month played on the PC.

## PC: cache folder, length tolerance, font, Copy log, welcome (2026-11-04, not committed)

- Done (new files `ForkCacheFolder.kt`, `ForkSourceMatching.kt`, `ForkAppFont.kt`, `ForkCopyLog.kt`, `ForkWelcome.kt`; hooks in FORK.md): Storage starts with "Song cache folder" (the path, opens Explorer); Sources has the phone's "Source matching" group with "Track length tolerance" (0–10 s, default 5), checked before an upgrade swap (catalogue runtime) and after its copy opens (decoder length); Appearance has "Font" (Default/Legacy, default Default = the phone's lighter weights; Legacy = the PC's look until now); the player's song menu ends with "Copy log" (the phone's text, now including the PC engine's own lines); the welcome pages (name, then YouTube sign-in) show once over the window. Backup maps `upgrade_length_slack_seconds` and `app_font` under the phone's keys; the welcome flag stays on the machine and an import marks it seen. Not built, not run.
- The phone's cache row is really "Show cached songs folder" (a Library folder of cached songs); the PC can't list cached songs (its cache files have no song metadata), so the PC row shows the folder instead.
- Check: it builds (`ForkBackupTest`'s two new tests); the welcome shows on the first start after this build (on an existing PC too, as the phone did), Enter/Next, Skip, Sign in opens the sign-in dialog over it and closes it once signed in, and it never comes back; the bridge's tests start behind it until it's done. The font switch changes every screen and the player at once; Default looks lighter than before (the owner may prefer Legacy). Copy log pastes the header plus the PC's resolve/upgrade lines for the playing song, with no line twice in `desktop.log`. A swap whose copy differs by more than the tolerance logs "outside the ±Ns tolerance" and keeps playing. The cache folder opens in the portable build's `data\` folder.

## PC: memory trim, NetEase lyrics, Genius meanings (2026-11-04, not committed)

- Memory trim (Desktop Revived a6b5311c, `ForkMemoryTrim`): a window hidden to the tray or minimized for 30 s drops its decoded covers (the PC's artwork cache and Coil's memory cache); restoring sooner cancels it. Revived's `System.gc()` left out (a full G1 pause under the audio thread; the launcher's periodic G1 GC gives the memory back within 20 s). NetEase lyrics (PC-Continued aaaaee54, `ForkNetEaseLyrics`): the phone has no NetEase source, so it is PC only, "NetEase" in Settings > Lyrics sources after LyricsPlus (on for existing setups, as any new source), asked only when it sits ahead of what the shared race found; a hit must match the track's length ±8 s; NetEase's Chinese credit lines are cut. Genius meanings (PC-Continued e16ed1f8 / 18b6b3ae, `ForkGeniusMeanings`): "Genius song meanings", off by default, under the PaxSenix row in Lyrics sources; "About this song" in Song info, the picked lines' annotation in the lyrics share sheet; Genius's JSON API, one request at a time, cached, no retries. Rows in `FORK.md`; parser tests `ForkNetEaseLyricsTest`, `ForkGeniusMeaningsTest` (fixtures, no network), `DesktopLyricsSourceOrderTest` updated.
- To check: build and `testDevDebugUnitTest`'s desktop tests (`:desktopApp:test`); minimize and hide to the tray past 30 s (log line `memory: window away 30 s…`), memory in Task Manager a minute later, covers back on restore; a native-frame minimize on Windows updates `state.isMinimized` (else only the tray case trims). NetEase: the Lyrics sources list shows it, drag it to the top and play a Chinese song and a Western one (word-synced, no "作词" lines, right take), saved order survives a restart; it is not in the player's provider picker (sharedUi lists the phone's sources). Genius: switch on, Song info on a well-known song (paragraph + Open on Genius), an obscure one ("nothing written"), offline (nothing asked); pick a famous line in the share sheet; with the network off, the dialog shows "nothing" at once and asks no more for 10 min.

## PC: album/playlist actions, keep playlists updated (2026-11-04, not committed)

- Done (`ForkCollectionActions.kt`, `ForkPlaylistSync.kt`, hooks in `DesktopApp.kt`, `Main.kt`, `ForkNotice.kt`; not built here). An album or playlist page's More menu now starts with the phone's "Play next" and "Add to queue" for the whole release (Shuffle and Download all were already on the page). A Home card that opens a page (YouTube or Navidrome album/playlist, not artists or Spotify) gets the phone's card menu on right-click: Play, Shuffle, Play next, Add to queue, Download all (YouTube only, as on the phone), Open, Share, Pin. Settings > Downloads has "Keep playlists updated" (off by default): a YouTube playlist downloaded with Download all is followed; 90 s after launch (if the last full check is over 6 h old), every 12 h and when switched on, its new songs are downloaded, removed ones keep their files, songs the listener deleted don't come back; over 50 new asks first with a card over the window. Navidrome's kept playlists already follow the server on every Home load (unchanged).
- Check in the app: Play next on an album page lands right after the current song in album order, Add to queue after the hand-queued songs, a notice counts them; both on a silent player start the album. Right-click a Home album card and a Navidrome album card: the menu, Play/Shuffle work, Download all queues downloads. Turn the setting on with a downloaded playlist that gained a song online: it downloads and "Added to X: 1" shows; `fork_playlist_sync.json` lists it. The log has `playlist sync:` lines.

## PC: Automix popup waveforms (2026-11-04, not committed)

- The PC's mix popup now draws both songs' three-band waveforms like the phone. They are measured during the analysis pass the PC already runs, from the same whole-song decode (no second download or decode), with the phone's filters and normalisation, and kept in `analysis/mixwaves-v1` beside the analysis. At a blend they are read and sliced on a background thread and appear a few ms after the popup opens. `ForkMixWaves` (+ `ForkMixWavesTest`); hooks in `DesktopTrackAnalyzer` (2 lines) and `DesktopAudioEngine` (`showBlend` with ids).
- Songs analysed before this have no kept waveform and keep empty lanes until they are analysed again (a song first played after the build shows them).
- To check: build + `ForkMixWavesTest`/`ForkAutomixReportTest`; play two new songs into a DJ blend and open the popup: lanes present, lows/mids/highs placed under the overlap and the beat grid as on the phone, the incoming lane right under a tempo stretch; the log shows no "mix waveform" errors; the folder `analysis/mixwaves-v1` gets one `.wave` file per analysed song.

## PC fork to follow (owner, 2026-11-04)

- The PC follows **both** PC forks, picking selectively (owner, 2026-11-04, after the BitChord Discord's admins recommended PC-Continued): **PC-Continued** (jackychan4891, remote `pc-continued`) and **Desktop Revived** (Bntagaaa, remote `revived`). Engine fixes and features commit by commit, never their UI or branch. The owner watches both repos and upstream on GitHub; the sync and watcher workflows were removed (owner, 2026-11-10). Forks are ideas only: PC gaps are fixed from the phone's or shared code first.
- Later, the owner's list: PC-Continued's PoToken change (shared code, test phone and PC together); a "Karaoke" switch (centre-channel cancel, PC-Continued e16ed1f8; bass and drums go too); the vocals-only toggle (`vocals-only` branch, plan in the 2026-11-04 conversation; waits until the Revived switch is done); "Fluid Background" (Kawarp: the cover downscaled to 128 px, Kawase-blurred, then a moving domain warp each frame, saturation/brightness/dither, crossfade on track change; library better-lyrics/kawarp `@kawarp/core`; the owner's YT Music Extension project has a tuned integration to read: `src\kawarp.js`, `src\kawarp-bg.js`, defaults in `src\content.js` (warp 1.0, blur passes 6, speed 4.15, saturation 2.6, dithering 0.034, brightness 0.5); BitChord would redo it natively, e.g. as a style of `ArtworkMeshBackdrop`). A light / system theme on the PC (measured 2026-11-04: about 7-10 agent-hours; Revived's `DesktopTheme.kt` / `DesktopSystemTheme.kt` drop in, ~330 literals in ~25 PC dialogs need judging, the native Mica call hard-codes dark in `window_jni.cpp`; default DARK like the phone). A picture-in-picture mini player (Revived `DesktopPip*`, 1b7b472a, built on their UI). Cover change fade (owner, 2026-11-04, from a screen recording of another player): keep the old cover until the new one loads, then an in-place ease-in-out-sine dissolve (~0.6-0.9 s; the recording's was 1.55 s), optional front Y-flip, setting Cut/Dissolve/Flip, later following the Automix blend; one shared `sharedUi` file + ~4 hooks in NowPlayingScreen/LandscapePlayer; core+flip ~5-8 agent-h, all ~10-15 h. Automix: two or three of Apple's AutoMix transition presets on top of BitChord's own analysis (seen in Octave's web player 2026-11-04: 17 presets as gain/filter/reverb/delay/repeater/gater/tempo automations chosen by genre and beat lock; e.g. "Ease-in Ring-out + Reverb", the hip-hop/pop delay-out); needs an effects stage; the owner judges by ear. PC Local Music like the phone's (tags, Albums/Artists tabs, opus/aiff/dsd files; owner moved it here 2026-11-04). The whole later list is reviewed after upstream's v1.9.1 is merged.
- PC-Continued recheck (2026-11-05, their v1.9 = 8f03de8f, already in the watcher): not yet looked at, from the agent's list: canvas decoder fix 5e9dbcbd (S-M, PC), typed lyric search waiting for every provider 18b6b3ae (S, shared `LyricsRepository`), Apple-style search / Top Picks and mood shelves / accent colour (be257dad), charts, city charts, BBC radio now-playing, window snapping (5b1b855c), backdrop pulse `PlayerAudioPulse` and party from the player menu (8498b497), settings sub-pages (e16ed1f8). **Skipped for now (owner, 2026-11-05): PC keyboard shortcuts** (Ctrl+K quick search, the shortcuts sheet, e16ed1f8); the owner may revisit.
- AutoPlay (2026-11-05): the owner's empty AutoPlay was Repeat being on, not a bug. Found on the way (upstream code, not fixed, needs the owner's OK): starting an album/playlist page keeps the previous song's AutoPlay tail (`PlaybackService.startBoundedContext`, `existingAutoplay = autoplay`), so the new song gets no radio of its own while 10 old suggestions wait; and a paged playlist never re-checks AutoPlay once its last page loads.
- How the sources relate (owner, 2026-11-04): BitChord Android (upstream) is the base, merged whole; this fork adds Navidrome and the owner's changes; the PC follows the phone; PC-Continued and Desktop Revived are only quarries. Forks are looked at only when they publish a major release (the watchers), never per commit. Phone parity first: if the phone has a feature, the phone's version wins; a fork's version only where the phone has nothing comparable. Every pick is credited (fork + commit) in the code comment and FORK.md.
- Licences (owner, 2026-11-04): not a constraint any more; the fork is for the owner's own use and will be made private.

## PC: artist signatures and the lyrics share card (2026-11-04, not committed, not tried in the app)

- "Artist signatures" (Settings > Appearance, after Artist Art; off, no address, as on the phone) with its "Signature API URL" row (Test / Save after `/v1/health` / Remove). The signature with "Signed by …" takes the artist banner's name when Apple has no title logo, else closes the About section; Navidrome artist pages: in Apple's banner, else an About at the foot. `ForkArtistSignatures` (the phone's rules; the PC forks' file only as an aid).
- Lyrics share card: the phone's pick mode now shows on the PC (long-press a lyric line or the header's share glyph), the phone's card drawn with Skia, Save (file dialog in Pictures) and Copy image (clipboard). `ForkLyricsShare`.
- To check in the app: an SVG and a scanned signature (white, crisp), an artist with an Apple logo (signature at the About foot), a sleeping API waking within 30 s; the card's fonts and backdrop next to a phone card, CJK/Arabic lines, story mode, pasting the copied image into Discord/Paint, the saved PNG.

## PC: six window extras (2026-11-04, not committed, not tried in the app)

- One instance per data folder (`ForkSingleInstance`): starting BitChord.exe again brings the running window back (tray, minimized) and the new one exits. Remembered window size, place and maximized state, checked against the screens there are (`ForkWindowState`). Overlay scrollbars on the PC's long lists and the player's queue (PC-Continued's, phone unchanged). "UI scale" 80-125 % in Appearance (Revived 8782585b). Esc leaves full screen before closing the player (Revived 8892ec00). Local Music covers from the file's tags or a folder image (Revived 9a8d8445, + ID3v2.2, M4A). Rows in `FORK.md`. On Windows the opening size is no longer multiplied by the screen scale (it opened 1.5x too big on a 150 % screen).
- To check in the app: start BitChord.exe twice (window hidden to the tray, then minimized), and pc-run.sh plain beside the portable folder (both should run); move/resize/maximize, restart, then with a monitor unplugged; scrollbars on Home, Search, Library, an album, an artist, the queue, the Navidrome library (none on the phone); each UI scale with the caption strip, window dragging, menus and dialogs; Esc in full screen; Local Music covers (FLAC, MP3, M4A, a folder with cover.jpg).

## PC: download tags and Export / Import data, the phone's (2026-11-04, not committed, not tried in the app)

- Downloads are tagged as on the phone (title, artist, album, cover, LRC and word-synced lyrics): FLAC, M4A, WebM through the phone's own taggers (moved unchanged to `:shared`, same package; the phone and TV build and their tagger tests pass), MP3 and Ogg with the PC's own; the PC reads the words back offline from all five. Tests: `ForkDownloadTagsTest`.
- Settings > Your data (after Storage): Export data / Import data in the phone's file. Shared settings mapped key by key, Replay months, search history, saved queues and the fork's switches (a new `fork` section, which the phone now writes and reads too); phone-only settings carried through for the next export; no logins or tokens. Import replaces and closes the app. Tests: `ForkBackupFormatTest` (phone), `ForkBackupTest` (PC, a phone-made fixture).
- Not carried: the phone's per-song days for plays added on the PC (the PC doesn't keep them; weekly Replay on the phone counts those plays only in month/year/all-time).
- To try: a real phone backup into the PC and back; an MP3/Ogg addon download's tags in another player.

## PC: video/audio switch, Smart audio alignment, Direct Source Stream, library extras (2026-11-04, not committed, not tried in the app)

- Song menu of the playing song: "Convert to video" / "Convert to audio" (the phone's; not for server songs, stations, files, or in a party). Lands on YouTube's own time map, else the two openings measured by the phone's waveform maths (Settings > Advanced > "Smart audio alignment", on by default; the maths now also in shared `ForkVersionAlignment`, 7 tests), else the same second. Crosses in 200 ms like a quality upgrade (cut over when paused or at another speed); refused while a mix plays. Queue row, history, YouTube history session and scrobble carry across (Desktop Revived 465c39c4 / 7a5509af). Switching back lands on the exact upload it left.
- Settings > Sources: "Direct Source Stream" (off by default): waits up to 15 s for an addon, no second look after an addon's answer, the next song fetched only 25 s before it plays.
- Album/playlist page: the library circle beside Play (add / remove from the YouTube library). Playlist page More: Pin / Unpin (5 at most), pinned first on the Library page. Song menu: "Remove from cache" (when the cache holds some of it), "Remove from history" (History page only).
- To check in the app: a video with a spoken intro switched both ways (lands on the same lyric line; the log says "measured … ms"), the music-video picture appearing after Convert to video with Music videos (beta) on, switching mid-song paused and at 1.25x, Last.fm counting one scrobble, Direct Source Stream with an addon (start waits, next song armed 25 s out in the log), a pin surviving a restart, Remove from cache then replaying (fetched again).

## PC: loudness normalization (2026-11-04, not committed, not heard)

- The phone's "Loudness normalization" (Settings > Playback, on by default) on the PC: per deck after its equaliser, before any mix (`ForkLoudness`). Figure from the server's ReplayGain, YouTube's `loudnessDb`, or the file's tags; the loop mix's incoming deck gets the phone's `inLevel`. Built; `ForkLoudnessTest` (13) and the Fork/engine tests pass. No figure (an addon song never resolved on YouTube, a file without tags): unity, as on the phone.
- To listen for: a quiet old master next to a loud new one (closer); switching it off mid-song (a 40 ms glide, no click); crossfades and Automix blends (no level jump at the promotion or the midpoint); loop mixes between a quiet and a loud song; server and local FLACs with ReplayGain against YouTube songs (about as loud); a boosted quiet song with no clipping grit.

## PC: player settings that did nothing, PaxSenix key, quality-upgraded mark (2026-11-04, not committed, not tried in the app)

- "Hide volume bar", "Reduce dynamic blur" and "Translation language" now reach the phone's player on the PC. New rows with the phone's wording and defaults (all off): Reduce animation (Appearance), Show Listen Together button / Show queue history / Hide Song Status (after Hide volume bar), Auto-hide player controls (Animated canvas). The Listen Together pill is now hidden by default (it was shown).
- PaxSenix API key row in the lyrics sources card (DPAPI-sealed). "Clear lyrics cache" after the clip rows in Storage. "Navidrome plays in YouTube history" in Settings > Navidrome, on by default as before (the owner asked for this sync on the PC; the phone's default is off).
- The player's "quality upgraded" cue on the "…" now shows when a better copy is swapped in mid-song (`ForkQualityUpgrade`, read off the engine's state). Unit tests in `ForkPlayerSettingsTest`.
- To check in the app: each row changes the player; the revert cue after an upgrade, and gone after "Revert to original".

## PC: music videos, opt-in (2026-11-04, not committed, not tried in the app)

- "Music videos (beta)" under Settings > Appearance (off by default): a YouTube music video's picture in the phone's player (sleeve, wide-window full player, quality drawer), kept to the audio clock. From Desktop Revived's `DesktopMusicVideo`/`DesktopVideoBeta`, our own decoder path (`ForkMusicVideo`, details in `FORK.md`). Auto is 720p on the PC; 1080p and up only when picked. Unit tests pass (`ForkMusicVideoSyncTest`, `ForkVideoRangeReaderTest`).
- To check in the app: picture in sync after seek, pause, skip and a crossfade; CPU at 720p and 1080p; that googlevideo serves the 1 MB windows; the full-screen button does nothing on the PC (sharedUi's orientation is a no-op there).

## PC engine: PC-Continued and Desktop Revived pieces (2026-11-04, not committed, not heard)

Ported into our engine (rows in `FORK.md`, desktop table): PC-Continued's speed join window, the deck armed before a blend, no TEMP seek trace, quality searches kept off the decks from 15 s before a seam to 1.5 s after it, a mix started once the tail has gone silent, Atmos first (make-up gain, source walk, follow-up; only with the Atmos setting on). Desktop Revived's partial-write clock, a prepared next song kept when asked again, the phone's fader curves, mid-song decoder recovery (22 s stall or a dead stream: reopened at the heard position, 2 tries), the phone's head/tail model analysis (new `analysis/windows-v1`, so songs are measured again once), the analyser held to the playing and next song, instant playback (next song opened with no Crossfade/Automix, Next on it starts at once, 256 MiB YouTube range cache in the media cache, queue/history written off the click), a fixed 8/16-bar overlap refusing the loop mix. Not taken: PC-Continued's EQ limiter (our equaliser already runs upstream's PeakLimiter with half make-up). Built, 193 Fork/engine tests pass; the full desktop suite fails only in the four FFmpeg-less classes (19 there).
- To listen for: speed changes (cleaner joins at 1.25x), Automix blends (outgoing fader no longer held to the swap; filter rides all one shape), a song whose end fades into silence (mix starts sooner), Atmos tracks (as loud as stereo, no pumping), analyses after the re-measure (loop mixes, vocals), gapless start of the next song with Crossfade off, Next with the next song already open (instant), pulling the network mid-song (reopens where it was).

## PC: web player's volume, top-bar links, Apple artist header (2026-11-04, not committed, not tried in the app)

- Top bar: the speaker mutes and restores (wheel: 5% a notch); the title opens the album, each credited artist their page (Navidrome songs: the library's pages), the progress line seeks (hover shows the times). Apple Music's photo, logo and colours on artist pages (YouTube and Navidrome), "Artist Art" switch under Animated canvas. Ported from upstream's `web/`; details in `FORK.md` (desktop table). The phone is unchanged (its `AppleArtistArt.kt` kept as is).
- To check in the app: the scrubber's 9 dp strip slightly overlaps the artist line's foot; the Navidrome banner sits inside the page gutters, rounded, above the album-style header (which still shows the name).

## PC: phone features ported (2026-11-04, not committed, not tried against the server)

- Home in the phone's Navidrome / YouTube / Spotify sections (`ForkDesktopHome`): the fork's shelves no longer go into the YouTube feed, so Recently Played can't land above the server's shelves; offline the Navidrome section is the kept row, Spotify hidden.
- Library: "Navidrome albums", "Navidrome playlists" and "Available offline" rows (filled with Home's load, as on the phone).
- Explore: Spotify mix tiles, opening the same Spotify page as Home's shelf.
- Offline strip under the player bar (above the mini player when compact): "Go online" when switched on by hand, a notice while the server doesn't answer.
- "Pre-load next songs" (Settings, Navidrome group; off by default): next N server songs read whole into `navidrome_preload` in the media cache, played from there.
- Song Info: `NavidromeSongInfo.init()` runs on the PC, but the PC has no Song Info page to show it (the dialog is the phone's `app` only).

## Artist radio and Similar artists (2026-11-04, not committed, not tried on the server yet)

- Navidrome artist pages, phone and PC: "Start radio" plays `getSimilarSongs2` asked with the artist's id, in the server's order (the artist's own songs allowed); an empty answer says "No similar songs found". Phone: a row after Shuffle in the sheet of the artist page's overflow and of a held artist row in the Navidrome library (not offline). PC: a round radio button beside Shuffle/Play (hidden offline). YouTube artists unchanged.
- "Similar artists" row under the songs (`getArtistInfo2`, artists in the library only, their server pictures), each card opens that artist's Navidrome page; no row when empty or offline. One request per artist and session; the artist id comes from the songs already read, else one `search3` for the exact name.
- To check on the server: that AudioMuse answers `getSimilarSongs2` for an artist id, and how a "A feat. B" page behaves (no artist of that name: no radio, no row).

## Now (2026-11-03)

The owner's list from using the phone and PC. Done and tested on the emulator (branch `android-fixes`):
- Settings: the Navidrome rows are their own "Navidrome" group, no longer under Listen together. Crossfade on skip, Animated covers and Backdrop videos are full rows with icons (they were sub-rows), and the fork's switches use upstream's `IosSwitch`.
- Automix hiding the Crossfade slider is upstream's (`if (!smartFade)` in `SettingsSheet`, "Automix decides its own length"): left as is.
- The release app is named "BitChord" (package still `com.ayhamo.bitchord`, so it still installs beside the official app).
- The album page's pop-up menu: "Available offline" with its size on a second line (it was squeezed to "Av / a...").
- Server songs no longer ask the server for clips (the clips left the server): their animated covers and backdrops come from the online services like a YouTube song's. Clips kept offline before still play; "Available offline" no longer fetches new ones. Seen: a server song asked Spotify, no x-clip request.
- YouTube start with addons on: measured 0.9 s to first audio with a failing addon (Qobuz, HTTP 402) ranked above YouTube, Direct Source Stream off. Upstream races addons against YouTube and swaps up mid-song, with a short break (ExoPlayer can't change sources gaplessly). Direct Source Stream (under Sources) waits up to 15 s for addons and holds the next song until 25 s before it plays: the likely cause of the owner's buffering, to check on their phone.

Automix, phone (found by comparing with upstream's web app, bitchord-8bw.pages.dev, whose bundle matches `web/`; nothing changed yet, the owner decides). The Firefox extension 0.4.4 has no Automix in it.
- The phone keeps one tempo per song, from its ending, and the loop mix anchors that grid on the song's first downbeat (`TrackAnalyzer.kt` around 1121-1145, `LoopMix.constantGrid`). The web keeps a head grid and a tail grid. Likely the biggest cause of loops off the bar.
- No grid-confidence floor before a loop mix (web: 0.2, `autodj.ts` `MIN_GRID_CONFIDENCE`).
- The loop mix is refused more often on the phone (lyrics still loading, a re-analysis running, another source's copy), and then `TransitionPlanner`'s blend plays, which is not the web's.
- The seek bar mark only shows once both songs are analysed; "next: analysing..." means a 6 s plain crossfade if the outro comes first (web: 12 s).
- Saved analyses (`AnalysisStore`, schema 3) are kept for good and never tied to the copy or model; planning is deterministic, so one bad analysis is the same bad mix every time.

Done on the same branch, 2026-11-03 (the owner asked for all four, then an audit of the round before the final build):
- Each side of a mix uses its own grid: the plan and the blend executor see the incoming song at its opening's tempo, the outgoing at its ending's (`ForkAutomix.incoming` / `.outgoing`); the loop mix's fallback grid counts bars from the stretch it loops in.
- The web's 0.2 grid-confidence floor before a loop mix (in practice it only turns away empty or DSP-only grids: head and tail share one averaged score).
- Lyrics still loading after 8 s no longer hold the loop mix back; the plan is made again when they land. Letting a still-refining next song in was tried and taken out after the audit: its head pass has no loudness or vocal windows.
- The phone keeps only analyses with the model's grid, and measures again a saved one without it. No schema bump (the stored fields didn't change; the PC's analyses have no head/tail sides, so the rule is the phone's only).
- Crackling during Automix (owner, S21 FE, Bluetooth, 16-bit output): the loop mix sums both songs at gains reaching 1.6 and nothing limited it when normalization was idle, so a 16-bit output clipped. Both gain curves are now trimmed by `1/sqrt(sum)` where they pass 1 (`ForkAutomix.trimmedGains`), and the limiter runs for the mix (`DspChain.forkMixing`). Not heard: the owner has to listen.
- Audit (one agent over the round's diff and the code it reaches, phone and PC): 1 high (the PC would have stopped saving analyses), 3 medium, 8 low, all handled. Not a whole-codebase audit; that was 2026-10-30.
- Spotify secrets file (thetadev `secretDict.json`, versions 59-61): the phone doesn't sign tokens (WebView); the PC signs only as a fallback with version 12. Not used.
Tested: unit tests 1226 app (1 skipped), 129 shared; the PC zip builds; on the emulator analyses run with the model grid, the lyrics wait clears, a YouTube song starts in 0.8 s, no errors.

PC bugs, done on `pc` 2026-11-03 (tested in the app through the bridge, not listened to):
- A quality upgrade no longer cuts the sound or drops the Automix plan: the better copy crossfades in over 200 ms at the decode point (`forkSwap` in `DesktopAudioEngine`). Seen: `/reload` mid-song crossfaded at 15.7 s, the position kept counting. Not tried with a real addon (none in the test profile) or a sample-rate change.
- Backdrops: the PC asked Spotify last and only when nothing else had a clip, and its REST search 429s. Now Spotify is asked too when Backdrop videos is on (`ForkDesktopCanvas`), through the Pathfinder search the phone uses. Seen: "Sad Girls" got a cover clip and a Spotify backdrop, shown behind the lyrics.
- Animated covers: the decoder took the biggest HLS rendition and downloaded them all; now the smallest that reaches 540 px, the rest discarded, threaded decoding. Not measured; the owner to judge.
- The crackle fix and lyrics wait don't apply: the PC blend already trims the sum, and the PC has no loop mix.
- The phone has the same one-clip limit (Spotify only when first), so server songs get few backdrops there too: not changed yet.

Next (owner, 2026-11-03, after a compaction): new work runs in an Opus 5.5 subagent at medium effort. Open: the PC look (recommendation: back to `sharedUi`), the PC release as one folder with a real `BitChord.exe` and all data inside it, the PC loop mix (proposed task), PC-Continued's PoToken change and its two engine pieces (upgrade hold during a mix, cue correction). No SponsorBlock.

Plan (owner, 2026-11-03, second half):
1. PC loop mix like the phone's (Opus subagent), then cue correction and the upgrade hold during a mix, since they touch the same engine.
2. The PC back on `sharedUi` (the phone's UI), `desktopUi` deleted at the end.
3. Later: PC-Continued's PoToken change (reuse the token page when only the session changes; a loading page isn't abandoned). Shared code, so tested on the phone and the PC together.
4. Later: the PC release as one folder with `BitChord.exe` and all data inside it.

2026-11-04: tested (phone on the emulator: Midnight Sun's Apple clip then Spotify backdrop, FAITH's own cover, no crash; PC from `BitChord.exe`: data only in `data\`, a setting survives a hard kill, registry untouched, the first-start import brought settings (171 values), analyses, queues and offline list, signed in). Found while testing: the settings import read the wrong registry hive through Java's own store; now read through JNA (`ForkPortable.javaName/javaValue`). Committed c6a66bb7, `main` fast-forwarded, both release workflows started.

Was left for 2026-11-04 (owner): everything after cc56b758 was UNCOMMITTED in the working tree (portable `BitChord Portable` zip with `BitChord.exe` and the first-start import of the old app's settings/logins, two-stage clips, server-song YouTube cover fallback, addons on OkHttp, Ktor 3.5.2, four audits' fixes incl. two Fable deep audits). Built: phone 1242 app (1 skipped) / 129 shared / 49 sharedUi tests pass; PC 55 runnable tests pass, `out\BitChord Portable.zip` built. Next: test the phone on the emulator (clip then backdrop, FAITH's own cover, offline cover) and the PC from `BitChord.exe` (data stays in `data\`, settings survive a restart, import, a loop mix), then commit, push, release both (phone: fork-sync-release; PC: fork-pc-release), unzip the new PC zip into Downloads and check it opens signed in.

Done 2026-11-03 (evening):
- The PC draws the phone's `sharedUi`; `desktopUi` is deleted. The phone's UI compiled for the PC unchanged; the PC needed the five new `PlayerHost` members, the #795 drawer parameters dropped (the ⋯ menu sits centred), the Search "Navidrome" heading moved into the shared screen, and the offline flag for the shared Explore. Seen in the app: Home, album, player (landscape), lyrics, queue, ⋯ menu, Explore (Navidrome + YouTube panels), Search (Navidrome first). Phone: 1360 tests, 0 failures.
- A caption strip over the player's top edge moves the window (double-click maximizes) with the player open. Not tried with a real mouse: the owner tries it.
- Crash fixed: an exception from a network read crossed FFmpeg's C frames and killed the app (`DesktopAudioDecoder` callbacks).
- PC loop mix (Opus subagent): the phone's renderer ported to Kotlin, armed from the shared plan. Seen once: Midnight Sun -> FAITH spliced at +0.00 ms (r=1.000), FAITH lined up on its deck and carried on at 28.2 s. Not heard: the owner listens. Cue correction (be257dad) ported with it.
- Fixed: on the PC, "Midnight Sun" (server) found no Apple clip: the window's own lookup asked with the server album "test", turned the hit away and cached the miss for the song. Now every lookup asks without it; seen with the Apple clip in the square and the Spotify Canvas behind, both moving. Open: Hover highlights from #795 not carried over. YouTube showed a signed-out session ("could not renew the session") and one stream that wouldn't decode: the owner signs in again.

Round 3, 2026-11-03 (two Opus audits, phone and PC, before the final build; their findings fixed):
- Phone and PC clips: a cover clip and a Spotify backdrop together (`ForkCanvas`, `ForkDesktopCanvas`); a server song's title without a repeated "Artist - "; Spotify hits checked by name (`ForkSpotifyMatch`, the PC's Pathfinder search). Seen on the emulator: "Sad Girls" (YouTube) and "Midnight Sun" (server) with the Apple clip moving in the square and the Spotify Canvas behind, landscape and portrait; "In the Name of Love" (server) with a Spotify backdrop; "Monody" matched after the prefix strip, but Spotify only has a still image for it. Found while testing: the phone's extra Spotify search ran on the main thread (refused, "http -1"), and a server song's clip was turned away by the player's title/album check; both fixed.
- PC: the quality swap seeks a second ahead and starts the cross once that copy has audio (it used to make the audio thread wait for the decoder's seek), and is dropped on any song change; the incoming deck slowed as well as sped up (0.9-1.25); stricter `worthSwapping`; Listen Together's default server; the Library grid crash (also on the phone); the debug bridge refuses web pages. Seen: `/reload` crossfaded at 19.77 s with the position continuous; a request with an Origin header refused.
- Not done: the PC loop mix (needs a Windows build of the native renderer and a render path; its own job), SponsorBlock (PC-only, sends every video id to a third party: the owner's call), PC-Continued's PoToken generator change (shared code the phone runs too), cue correction and the mix-engaged upgrade hold from be257dad.
- The PC's desktop tests that need FFmpeg can't run in the Linux cross-build (Windows FFmpeg); the rest pass.
- 16-bit/44.1 kHz from addons: info only (addon client): Lossless Bridge always serves its master; only eljohnzc's `lossless` gives 16/44.1.
- UI path research (Opus): point the desktop back at `sharedUi` (option A) is recommended; the owner decides.

PC-Continued (agent rating, 2026-11-03): their app is unpacked in `out\pc-continued` (`Run-PC-Continued.bat`, its data in `out\pc-continued\home`; Java prefs in the registry are shared with ours). The branches split before upstream's `desktopUi`, so their UI edits land in our phone's `sharedUi` unless re-applied by hand. Worth taking: be257dad's engine side (the 0.9-1.25 tempo range our planner already plans for, mix-engaged upgrade hold, cue correction, skip when no mix), c310d0e3 minus yt-dlp (PoToken, `worthSwapping`, cache caps), aaaaee54's three fixes (skip wait 6 s to 1.2 s, Library key crash, Listen Together fallback), 5e9dbcbd's canvas decoder, SponsorBlock. The Apple Music redesign and website player are the owner's call.

PC, to do after PC-Continued's commits (the owner's report):
- Automix works, but when an addon's lossless copy replaces YouTube's the sound cuts, and during a mix it stops and resumes (`DesktopAudioEngine.swapStream`, `upgradeIncoming`).
- Backdrop videos don't play, animated covers stutter, and the PC's look differs from the phone's.
- The PC's own strings for the clip switches still mention server clips.


## Before that (2026-10-30)

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
Finished on PC, tested on the app with the real server: Spotify's token (`ForkSpotifyToken`, its real page in a hidden browser; the "Made for you" shelf closes Home, 1 saved mix, the category endpoint answers HTTP 429 on phone and PC alike), Navidrome songs in Search (the first search waits for the library listing), Genres on Explore, saved queues (no login in the file), internet radio (played 1LIVE, no scrobble or history), crossfade on Next and Previous with its switch, Report plays, Navidrome on Home, clip cache and its clear buttons. Written by five agents at once, then built and tested here. One bug that testing found: the next song's read-ahead asked YouTube for a Navidrome id, fixed in `DesktopSourceRegistry.resolve`. Next covers measured: a plain Next shows the new cover within about 30-60 ms, three fast Nexts (not warmed) stay near-black past 220 ms.
Android builds, unit tests pass, and on the emulator: kept song in airplane mode, genres on Explore, radio list and a station playing, after the moves to `shared` (offline, genres, radio, Spotify's fetch). The moved code is on `pc` only, not on `main`.
Known small: a station's progress shows 0:00 and it tries YouTube's links and video match (harmless, logged); a clean close by a station's server ends it as a finished track. Crossfade read-ahead of a slow stream can stall (watch for "decode-ahead ran dry").
Offline when the server drops: the owner said skip the test. v1.9 merged into `pc` (branch `merge-v1.9`, merge 356928a7, into pc 7d24b845, then 7a217069). NOT in `main`, no PR and no release: the owner said so. Rule for merges: memory `upstream-takes-priority`. A Fable 5.1 audit (three agents, read-only) found no highs and lost no hooks; its fixes are committed: the login token off disk (`NavidromeRepository.atRest`, `.live`: offline manifest, phone's search history, desktop queue and history, saved queues; widget prefs out of backups), the desktop's vocal mask on v1.9's scale, skip crossfade gives way to Direct Source Stream, offline manifest set aside when unreadable (`offline.json.bad`) and a failed write rolled back, a playlist that shrinks by more than half isn't re-kept, ids checked before naming files, a station has no like or playlist row, add-to-playlist hidden on a server's song, the probe needs two misses, clip downloads through a `.part` file, Spotify page folders cleared on the next mint.
Left on purpose: the login held under a lock across the network in `NavidromeClips.token()`, and "Plays count on YouTube Music" default on for the PC (the phone's is off), because the owner asked for that sync. Listening stats may still hold a cover address with the login; a phone's old `offline.json` keeps its logins until the list next changes (it's out of backups).
Tested on the merged build, 2026-10-30. Phone: Home's sections with one Find Your Mood, Explore's genres, Library's offline and albums rows, a server song's heart (seen on the PC, so it reached the server) and menu, the three switches live, Saved queues, Instant Mix, skip crossfade, Settings and its search, Library search, restart with the queue kept, radio with no like or Instant Mix, cover clip, backdrop, hold on pause in landscape and portrait, fullscreen cover, kept songs in airplane mode, a YouTube song and upstream's Convert to video. PC: the same heart and menu, backdrop and lyrics, Search, Explore, radio, skips, a restored queue that plays, keeping an album with no login in its list and it surviving a restart, no exceptions in the log. Then also: search history covers (a server cover saved with no login loads, and stays login-free after a re-save, seeded by hand: no phone screen records a server song there yet), a genre page and Saved queues on the PC, skip and natural crossfade at 4 s, PC memory while playing with clips (working set about 1060 MB, private 1410 MB, level over 150 s, one short rise of 300 MB while Automix measures a song, back within a minute).
Found while testing: a station's logo from the server kept the login in a backed-up prefs file (fixed, out of backups); the PC's update check still read upstream's releases (now the fork's, and only says something when the release has a PC file, so a PC release must carry `-windows-portable.zip`). On the emulator the video decoder can hang: clips stay on the still cover and `kill -3` shows `MediaCodec.native_setup` stuck; reboot the instance, it isn't the app. The first launch after installing can stick too: force-stop and start again.
Upstream's own: after a restart the PC keeps the queue but no current song, so Play does nothing until Next or a click.
Automix on the PC, found by the owner listening: server songs were never measured (it asked YouTube for a `navidrome:` id and fell back to a plain crossfade); fixed in `DesktopTrackAnalyzer.request()`. Measured per deck after the fix: Gate of the Abyss into Resurrection of Soul overlaps for about 5 s with both within 8 dB; Midnight Sun into The Evening Fog overlaps but Fog is over 20 dB quieter for the first half, so it hardly shows; seeking into the last seconds before a song is measured starts the blend almost at the end, with nothing left to overlap. The owner still has to confirm by ear: logs and levels don't prove what a mix sounds like. Automix on the phone hasn't been listened to either. Also open: the small gap on a seek (the engine flushes its queue; upstream's path, not measured reliably: the loopback recorder skips silence).
Android audit, 2026-10-30 (six Fable agents: playback, data, UI, shared, security, fork against v1.9; each stated what it read and sampled). Full unit tests after the merge: 1223 app (1 skipped), 123 shared, no failures. Android's Automix planner has no fork changes, and the crossfade hooks were traced as inert on a natural ending, so the owner said no listening test for it on the phone.
Fixed and tested (fork, or a small hook; the album header, Home paging, Liquid Glass strip, bit-perfect refusal, loop mix casts and the download changes by code and unit tests only, the emulator can't reach them): server login out of song addresses and added in the HTTP layer (media session readable by any app, cache database in backups, Listen Together, Discord, Copy log); crossfade on skip refuses bit-perfect; `ForkSkipMaskPlayer` makes two `as? SessionPlayer` casts null (loop mix position, DAC volume); station AutoPlay watchdog; `prefetchDirect` on server songs; Top Picks seeded from a server song; album header overwritten; landscape clip `key(url)` both places; multi-stamp LRC lines; Home paging under Spotify; download `.part` collision; `DownloadService.promote` guard; `offline.json.bad` blocking sweep; radio playlist read unbounded; preload at 0; saved queue Play at 0:00; offline strip under Liquid Glass; double separator; stars after sign-out offline; `forkBackOnline`.
Upstream's own, reported only: Spotify, Last.fm, ListenBrainz and PaxSenix secrets in the backed-up settings file; SMB close after a failed open; `Downloads.record` write order; WebDAV failed listing shown empty; backup restore against a flush; add-to-playlist on WebDAV and SMB; player and sign-in lost on activity recreation; `q:` search rows; Discord login alert origin; hash-keyed offline HLS and analysis files.
Left, low: cover clip rebuilt on pause under Fullscreen, Home counts for one frame, a bailed skip or pre-load losing a cache entry, pre-loaded songs not in the cached rows, clip miss notes not pruned, radio lookup naming a private host.
Checked after the fixes: 1226 app tests (1 skipped), 123 shared, no failures. On the emulator: covers, a streamed server song, the notification's cover, a saved queue resuming at its second and closing the menu, a station making no radio requests over 40 s, the backdrop across skips between backdrop songs, kept songs in airplane mode, new cache entries login-free. Old entries in `exoplayer_internal.db` and `fork_queues.json` keep their logins until replaced (out of backups; a clean reinstall removes them). An audit claim that pre-load runs on at 0 was wrong: `around()` cancels it. The PC runs on the same addresses: covers, FFmpeg, clips, no login in its log.
Released 2026-10-30 at the owner's word: `pc` fast-forwarded into `main` at c36684d1, `fork-sync-release.yml` run 41131939415 published `fork-v1.9` (https://github.com/ayhamo/BitChord/releases/tag/fork-v1.9): universal, arm64-v8a, armeabi-v7a, x86_64, SHA256SUMS. The released x86_64 APK matched its checksum, installed as `com.ayhamo.bitchord` version 1.9 on the emulator, opens with Home's content, no crash. Its server features haven't run in a release build: that needs the owner's login. `main` and `pc` are one line of code again. Upstream's `android.yml` fails on every push to `main` here, since this fork lacks its signing secrets.
PC test build published: `fork-pc-v1.9` at a444b375 (https://github.com/ayhamo/BitChord/releases/tag/fork-pc-v1.9); the downloaded zip was run and played a server song. It builds in `eclipse-temurin:21-jdk` (Ubuntu 26.04), because the media keys code needs newer MinGW headers than the Ubuntu 24.04 runner has. `fork-pc-release.yml`, run by hand, publishes `fork-pc-v<version>` as a pre-release: `BitChord-<version>-windows-portable.zip` with a pinned Temurin 21 Windows Java inside (`docker/pc-jre.sh`), `BitChord.bat` with the measured memory flags, `BitChord-log.bat` writing `bitchord.log`. A pre-release isn't "latest": the phone's updater and the PC's own never see it, so a full PC release later must attach the zip to a normal release. Tested as a real launch from a folder with a space: its own Java, server songs, covers, backdrop, lyrics, 900 MB working set and 1270 MB private, level over 90 s. A real launch uses the real profile: the Java Preferences in `HKCU\Software\JavaSoft\Prefs\com\music` are the test app's, so it opens signed in to what that was.
Next: the owner's verdict on PC Automix and real use of the zip, PC-Continued's commits, then a full PC release.
Later, the owner's list (2026-10-30), still only on the phone: Home's collapsible sections, Library's Navidrome albums and playlists rows, Spotify's tiles on Explore, the offline strip, Song Info for server songs, pre-loading whole songs. Order the owner chose: upstream's v1.9 now, then PC-Continued's commits, then a code audit, then publish. Clean up last, including the test saved queue.
Later, the owner's call: yt-dlp out of process; look at how PC-Continued did it first (`DesktopYtDlpResolver.kt` in `c310d0e3`). Upstream's v1.9 is out (356 files, 12 conflicts with main in a trial merge) and waits until the PC client is finished; the daily job can't publish it because the merge conflicts. Known: an instrumental can get another song's online lyrics (golden hour); Home scrolling through the bridge is untested; in an upright window the player's menu can open away from its button, because the button's place is only reported in the wide layout (upstream).

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
- Phase 7: updater reads `ayhamo/BitChord` releases (`fork-v<version>`); workflow `.github/workflows/fork-sync-release.yml` builds the **prod** flavor (`com.ayhamo.bitchord`, named "BitChord", installs beside the official app) signed with the fork's own release key (secrets on GitHub; local copy in `docker/keys/release/`, gitignored, back it up).

## Left

- Push to GitHub and the first release: only when the owner says so (don't ask).
- Package `com.ayhamo.bitchord` (named "BitChord") sits beside the official app; the first fork release is a fresh install (its own data).

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

