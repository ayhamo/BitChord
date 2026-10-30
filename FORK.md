# Fork changes

Everything this fork adds lives in new files where possible. This file lists every place it touches an **upstream** file, so a conflicting upstream merge can be redone by hand. Fork lines in upstream files carry a `// fork` comment (or are in a block that starts with one).

## New files

| File | What |
|---|---|
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/navidrome/NavidromePlatform.kt` | What the shared Navidrome code needs from each app: key-value stores, what is kept offline, offline mode, scrobble switch |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeAndroid.kt` | The phone's side of `NavidromePlatform`; starts the Navidrome objects |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/ForkNavidrome.kt` | The desktop's side of `NavidromePlatform` (Java Preferences, password sealed with Windows DPAPI), the sidebar page and login |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/spotify/ForkSpotifyMadeForYou.kt` | Reads Spotify's mixes, for both apps (moved from the phone's `ForkSpotifyMixes`) |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/ForkSpotifyHome.kt` | The mixes as Home's "Made for you" shelf |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/ForkNotice.kt` | A short message over the window, the phone's toast |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/ForkOffline.kt` | "Available offline" and offline mode on the desktop: keeps pages with the shared `NavidromeOffline`, offline when switched on or the server can't be reached |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/ForkSpotifyToken.kt` | Spotify's token from its real page in a hidden JavaFX browser, as the phone does |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/ForkNavidromeSearch.kt` | Search: the server's songs (kept songs only, offline) for a typed search, read beside YouTube's request |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/ForkNavidromeGenres.kt` | Reads Explore's genre tiles, following the login and offline |
| `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/ForkExplore.kt` | The desktop's copy of Explore's panels: "Navidrome" (hidden offline) and "YouTube", no "Spotify" |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/ForkPlayerMenu.kt` | The player menu's three switches (`ForkPlayerMenuRows`) and their wording, shared with Settings |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/ForkNavidromeSettings.kt` | "Report plays to Navidrome", "Navidrome on Home" and the clip cache's size, saved; Settings' "Navidrome" group |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/ForkClipCache.kt` | The clips' own size limit and the "Clear backdrop videos" / "Clear animated covers" rows, which also forget the "no clip" notes; kept offline files are in another folder |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/ForkDebugBridge.kt` | Test door: off unless `-Dbitchord.debugBridge=<port>`, loopback only, input goes to the app's window |
| `docker/pc-build.sh`, `docker/pc-run.sh`, `docker/pc-shot.ps1` | Cross-build the Windows zip, run it isolated in `out/pc-run`, screenshot only its window |
| `docker/` | Docker build (`build.ps1`, `Dockerfile`); `docker/keys` holds the debug key (gitignored) |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/navidrome/NavidromeSettings.kt` | Server URL, username, password in their own encrypted prefs (`bitchord_navidrome`) |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/navidrome/NavidromeClient.kt` | Subsonic client: token auth, `search3` paging, raw stream and cover URLs |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/navidrome/NavidromeRepository.kt` | The library as `Song` rows (tags, per-song covers), cached in memory |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/navidrome/NavidromeLyrics.kt` | Timed lyrics for Navidrome songs: a kept song's `.lrc`, then the lyrics cache (`cache/navidrome_lyrics`: what showed last time, server's or online), then `getLyricsBySongId`; `clearCache()` for Settings |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/navidrome/NavidromeClips.kt` | x-clip cover clips: real path from `/api/song/<id>`, `/rest/x-clip/<folder>/<stem>.cover.mp4` played in the cover square, `.background.mp4` backdrops (with the cover clip as `sleeveUrl`), Navidrome login (`/auth/login`) added by an interceptor, 7-day "no clip" notes (`bitchord_navidrome_clips` prefs). |
| `app/src/test/java/com/music/bitchord/NavidromeClipsTest.kt` | Clip URL from a song path |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeHome.kt` | Album shelves (recently added / recent / most played / random) laid over the top of Home |
| `app/src/test/java/com/music/bitchord/NavidromeLyricsTest.kt` | Lyrics answer → player lines (offset, word stamps, plain text ignored) |
| `app/src/main/res/values/strings_fork.xml` | Fork strings |
| `app/src/main/java/com/music/bitchord/fork/ForkSettings.kt` | Fork switches (`bitchord_fork` prefs): crossfade on skip, backdrop videos, animated covers, report plays to Navidrome, Navidrome on Home. On its first start it turns upstream's "Spotify Canvas auto-hide" off once (`canvas_manual_set`), so the controls over a clip stay until tapped away; it can be turned back on in Settings |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/navidrome/NavidromeStars.kt` | The heart/star for Navidrome songs as the server's star (`star`/`unstar`), seeded from the songs' `starred` |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/navidrome/NavidromePlaylists.kt` | Server playlists as pages (`local:group:playlist:navidrome:<id>`), reached from the Home "Playlists" shelf |
| `shared/src/commonMain/kotlin/com/music/bitchord/data/model/ForkSong.kt` | `NAVIDROME_ID_PREFIX`, `Song.isNavidrome` (readable from the shared UI) |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/navidrome/NavidromeScrobble.kt` | "Now playing" and finished plays sent to Navidrome (`scrobble`, submission false/true) |
| `app/src/main/java/com/music/bitchord/ui/screens/ForkSettingsRows.kt` | The fork switches as full settings rows, for the "Fork settings" group |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ForkPausedCover.kt` | The cover drawn back over a full-screen clip while paused |
| (ForkSleeveClip.kt) | `ForkCanvasSwitch.backdrops` / `.covers`: "Backdrop videos" (a clip filling the screen: server backdrops, Spotify Canvas) and "Animated covers" (a clip in the cover square: server cover clips, Apple Music etc.), flows the player reads live (ForkSettings persists them); `forkCanvases()` applies them |
| `app/src/main/java/com/music/bitchord/fork/PlayerDecoderNames.kt` | The decoder each ExoPlayer last stood up, so a crossfade handoff can publish it (the Audio Pipeline showed "—" after every crossfade) |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/navidrome/NavidromeAlbums.kt` | Navidrome album pages (`local:group:album:navidrome:<name>`) from `getAlbum` (album id remembered from songs/Home cards, else `search3`) instead of the whole library; the album's own cover and artist for the header |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/navidrome/NavidromeSongInfo.kt` | Song Info for Navidrome songs from `getSong`: release date, your plays, rating/star |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/ForkSongInfo.kt` | Provider slot the app fills, asked first by `SongInfoRepository.load` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ForkSleeveClip.kt` | `forkCanvases()` (private `forkInSleeve()`): where a backdrop can't fill the screen, the sleeve plays the cover clip (`sleeveUrl`) or the still cover, never the backdrop; `forkFillsScreen()`: a backdrop also fills any portrait window, not only phone width, and nothing fills a landscape window |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeSearch.kt` | The Search tab's "Library" source gets the Navidrome songs after the device's |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/ForkRowFill.kt` | `forkRowMax()`: preview rows (Library shelves, artist albums) show as many cards as fill the window, at least upstream's 5 |
| `app/src/main/java/com/music/bitchord/playback/ForkSkipMaskPlayer.kt` | While a crossfade skip arms, the session reports the chosen song at 0:00, so the screen doesn't flip back; a `ForwardingPlayer` around upstream's `SessionPlayer` |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromePages.kt` | The Navidrome pages and Explore tiles as one question for `MainViewModel` |
| `app/src/main/java/com/music/bitchord/ui/screens/ForkSources.kt` | Navidrome's section in Sources, its server editor, its Library links, its folder page's first tab |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ForkPreloadCovers.kt` | The next two songs' covers fetched at player size ahead of time (no black player at a crossfade/skip) |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/ForkLibraryPlaylists.kt` | "Navidrome albums" (all, A–Z) and "Navidrome playlists" Library rows (filled by `NavidromeHome.load`) |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeOffline.kt` | "Available offline": whole albums/playlists kept in `files/navidrome_offline` (original files, covers at 1200 px, backdrops and cover clips with `clips.json`, timed lyrics as `.lrc`), never evicted; two files at a time, waiting for a network the "Download over" switches allow; covers and clips answered from disk by `serve()` in the HTTP clients; sizes before and after keeping; `offline.json` list; offline-mode listing (`keptSongs`); Library "Available offline" row |
| `app/src/main/java/com/music/bitchord/fork/ForkYouTubeHistory.kt` | Navidrome plays into the YouTube Music history: strict YouTube match (title, artists, length ±5 s), then upstream's `PlaybackTracker` with that id (switch, off by default) |
| `app/src/main/java/com/music/bitchord/fork/ForkClipCache.kt` | Clip cache size (adjustable evictor) and clearing by the switches' two kinds: backdrop videos (server backdrops, Spotify Canvas) and animated covers (server cover clips, Apple Music etc.); `clearWithSongs()` empties both and the lyrics cache, and clearing also forgets the server's "no clip" notes |
| `app/src/main/java/com/music/bitchord/ui/components/ForkMenuRows.kt` | The player's song menu rows (`ForkPlayerMenuRows`: "Backdrop videos", "Animated covers", saved queues); "Available offline" row in an album/playlist sheet |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/navidrome/NavidromeArtists.kt` | Navidrome artist pages from one paged `search3` (exact artist tag), not the whole library |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/navidrome/NavidromeGenres.kt` | The server's genres as Explore tiles (`getGenres`, a `byGenre` album cover each, handed to the app by `NavidromePlatform.onGenres`) and genre pages (`getSongsByGenre`, id `local:group:genre:navidrome:<genre>`) |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/ForkExplore.kt` | Explore's "YouTube", "Navidrome" (server genres) and "Spotify" (mixes) panels, each collapsible |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/ForkHome.kt` | Home in collapsible "Navidrome", "Spotify" and "YouTube" sections (`NavidromeHome.onTop` says how many leading shelves are the server's and Spotify's) |
| `app/src/main/java/com/music/bitchord/fork/ForkSpotifyMixes.kt` | The signed-in Spotify account's mixes ("Made For You" category via the Web API, plus Spotify-owned playlists in the library) as a Home shelf and Explore tiles; they open as upstream's Spotify playlist page (tracks matched to YouTube) |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ForkLandscapeBackdrop.kt` | Landscape backdrop (a server backdrop or a Spotify Canvas): the clip fills the right half behind the controls and melts into upstream's pre-blurred cover (`fullArtworkBlurImage`), which fills the rest; the cover stays on the left; same cover hold as portrait (`rememberForkBackdropIntro`) |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ForkBackdropIntro.kt` | A full-screen clip waits 2.5 s on a new track (the cover is seen first) and then fades in over 1.2 s; not again for the same track |
| `app/src/main/java/com/music/bitchord/fork/ForkNetwork.kt` | The network for the offline features: Wi-Fi (unmetered) always, mobile data only with "Download over mobile data"; offline mode (switch, or no network) |
| `app/src/main/java/com/music/bitchord/fork/ForkOfflineService.kt` | Foreground service (dataSync) keeping "Available offline" downloads going in the background, with a progress notification and Cancel |
| `app/src/main/java/com/music/bitchord/fork/ForkPreload.kt` | "Pre-load next songs": the next N Navidrome songs read whole into the song cache (upstream's read-ahead writer), on allowed networks |
| `app/src/main/java/com/music/bitchord/ui/components/ForkOfflineBanner.kt` | Strip above the mini player in offline mode ("Go online" when switched on by hand; "No network" otherwise) |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/navidrome/NavidromeRadio.kt` | The server's internet radio stations (`getInternetRadioStations`) as the Library "Internet radio" page; `.pls`/`.m3u` resolved to the stream; a `#bitchord-radio` mark keeps streams out of the song cache; logos: the server's `ra-<id>` cover (what psysonic uploads), else radio-browser.info, kept a week (`bitchord_radio_logos` prefs) |
| `app/src/main/java/com/music/bitchord/fork/ForkQueues.kt` | Saved queues (try-out): the queue, its song and second, saved under a name in `files/fork_queues.json` (max 20), played back from there |
| `docker/keys/release/` (gitignored) | The fork's release key `bitchord-release.jks` + `keystore.properties` (random password). Back it up: without it no update installs over a release |

`STATUS.md` and `FORK_PLAN.md` are this fork's own notes (where the work stands, and the plan); upstream has neither.

## Hooks in upstream files

| File | Hook |
|---|---|
| `BitChordApplication.kt` | `NavidromeSettings.init(this)` and `ForkSettings.init(this)` after `AppSettings.init`; `coilHttpClient`: first interceptor runs `Http.forkInterceptor` (kept Navidrome covers come from disk) |
| `AndroidManifest.xml` | `.fork.ForkOfflineService` (dataSync) after upstream's DownloadService |
| `app/build.gradle.kts` | `prod` flavor: `applicationId = "com.ayhamo.bitchord"`, `app_name` "BitChord Fork" |
| `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` | Exclude `bitchord_navidrome.xml` and `bitchord_navidrome_plain.xml`, next to the `bitchord_sources` lines |
| `.gitignore` | `CLAUDE.md`, `graphify-out/`, `/out/` |
| `.github/workflows/fork-sync-release.yml` (new) | Daily: merge upstream's latest release, fork tests, `assembleProdRelease` signed with the fork's release key, certificate check, attestation, `fork-v<version>` release; on failure an issue + GitHub's failure email |
| `README.md` | A "This fork" note and "What this fork adds" section above upstream's header (a likely but easy merge conflict when upstream edits its top) |
| `MainActivity.kt` | `ForkQueuesBinding(controller, scope) { … playFrom(…) }` right after `playFrom()`. `localFolderStates.getOrPut`: `LocalFolderState().forkDefaults(page.browseId)` (+ import; Navidrome opens on albums). `playDetailSongs()`: a `forkSort` line, the page's context sorted like the page (`sortedForDetail`), no continuation token for a sorted page (**fixes upstream**, see below). `ForkOfflineBanner()` after `QueueActionNoticeHost`. `onToggleVersion`: not for Navidrome ids or radio stations. `ForkNavidromeEditor(hazeState)` before the SMB editor. `localizedLabel`: `SongSort.REVERSED`. `startRadio`: `forkStation = true`, and on a Navidrome song the empty-answer toast says `fork_no_similar_songs` |
| `ui/MainViewModel.kt` | `home` is `NavidromeHome.onTop(_home, …)`. Before `val selected = …` in the lyrics job: `NavidromeLyrics.remember(…)`. First line of `setLike()`: `NavidromeStars.setLike(…)`. In `init`, after the WebDAV collector: `NavidromeHome.follow(…)`, which calls the new `forkBackOnline()` (above `refreshHome()`) when the network returns: Home loads again and a song with no lyrics asks again. First line of `openMoodGenre()`: `NavidromePages.tileSource(…)` opens a Spotify mix or a Navidrome genre as a page. `loadHome()` and `refreshHome()`: `launch { NavidromeHome.load(…) }`. `loadLibrarySongs()`: last line `NavidromeSearch.withLibrary(…)`. `remoteLibrary()`: a `NavidromeSettings.BROWSE_ID` branch. `openDetail()`: after `localPageState`, `NavidromeAlbums.header(browseId)` sets `artwork`/`credit`. `localPageState()`: `NavidromePages.pageState(browseId, ::text)` before `remoteLibrary(…)` |
| `playback/CrossfadeController.kt` | The skip code is one block at the end of the class (`crossfadeSkip()`, `isSkipBlend()`, `pendingSkipIndex()`, `skipTarget`, …) plus `SKIP_ARM_TIMEOUT_MS` after the class. One-line calls in upstream's code: the listener's discontinuity (`forkDropSkip()`) and item transition (`forkDropSkipOn(reason)`), `onSkipRequested()`, `finish()` (`forkDropSkip()`); `begin()` takes `targetIndex` (`nextIndex`, `forkArmTimeout(targetIndex)`); `driveArming()`: `forkSkipFailed(into)`; `driveFade()`: `isSkipBlend() \|\|` in the handoff condition, `&& !skipFadeOn()` in `settingSwitchedOff`; `bail()`: `forkTakeSkip()` around `finish()`; first line of `stillWorthFading()`. Natural endings and Automix's planning are untouched |
| `playback/PlaybackService.kt` | `newSessionPlayer()`: `.forkMasked(crossfade)` on the `SessionPlayer` (see `ForkSkipMaskPlayer`). `SessionPlayer`: `seekToPrevious`, `seekToPreviousMediaItem`, `seekToNextMediaItem`, `seekToNext` try `crossfade.crossfadeSkip(…)` first. `adoptPlayer()`: a skip blend (`isSkipBlend()`) is booked as `previousEnded = false`, reason SEEK (not scrobbled as finished, no "sleep after this song"); publishes the incoming decoder (`PlayerDecoderNames`) and `publishNerdStats()`. `buildPlayer()`: `.also(PlayerDecoderNames::track)`. `canLike()` also true for Navidrome ids; the notification's favorite goes through `NavidromeStars.rateOr`. `currentSourceName()`: "Navidrome". `prefetchAround()`: `ForkPreload.around(player)`. `submitListenBrainzFinished` / `submitListenBrainzPlayingNow`: `NavidromeScrobble` first. `adoptPlayer()`: `QueueCoordinator.consumePlayedUserQueue(incoming)`, so played "Play next" rows go on a blend too. `cancelPrefetch()`: `ForkPreload.cancel()`. `carButtons()`: the Instant Mix label on a Navidrome song. `startStationFromSession()`: `forkStation = true` |
| `playback/PlayerConnection.kt` | `toMediaItem()`: `offlineUri` also tries `NavidromeOffline.uriFor(videoId)`, so a Navidrome song whose kept file has gone streams from the server instead of being taken for a YouTube id |
| `playback/AudioCache.kt` | `playbackFactory()`: a `#bitchord-radio` stream bypasses the cache like file/content URIs. `forkReadAhead()` after `setUpstream()`. `prefetchQueue()`: `.filterNot(ForkPreload::skipsReadAhead)` (Navidrome, radio, and WebDAV/SMB: **fixes upstream**) |
| `playback/Autoplay.kt` | `loadAutoplayTracks()`: a Navidrome seed takes `NavidromeRepository.related()` (similar songs, else random) instead of a YouTube radio. New parameter `forkStation`: from "Start radio" a Navidrome seed takes `NavidromeRepository.instantMix()` instead |
| `data/canvas/CanvasCache.kt` | `cache` is `internal`; evictor is `ForkClipCache.evictor` |
| `data/canvas/CanvasRepository.kt` | `canvasFor()`: `NavidromeClips.canvasFor(song)` first; a Navidrome song is looked up without its album (a server album can be a collection, "Youtube Topic", whose name failed `matches()` for every right hit) |
| `data/settings/AppSettings.kt` | `SongSort.REVERSED` ("Reverse order") after DEFAULT |
| `data/lyrics/EmbeddedLyrics.kt` | `forUri()`: first two lines hand a Navidrome stream URL to `NavidromeLyrics` |
| `data/AppUpdateChecker.kt` | `LATEST_RELEASE_URL` points at `ayhamo/BitChord`; the tag has `fork-` stripped before `v` |
| `data/innertube/PlaybackTracker.kt` | First line of `onPlaying`: `ForkYouTubeHistory.intercept`; first line of `onProgress`: maps a Navidrome id to its YouTube match |
| `shared/…/data/Http.kt` | `forkInterceptor` property (set by `NavidromeClips.init`) and one `.addInterceptor { … forkInterceptor … }` line after the WebDAV one |
| `shared/…/data/SongInfoRepository.kt` | First line of `load()`: `ForkSongInfo.provider` |
| `shared/…/data/innertube/StreamResolver.kt` | `signatureTimestamp()`: `init` (NewPipe) before use (**fixes upstream**) |
| `shared/…/data/model/Models.kt` | `String.artworkAt()`: `.forkSubsonicSized(px)` (in `ForkSong.kt`). Test in `ModelsTest.subsonicCover…` |
| `shared/…/data/canvas/CanvasArtwork.kt` | `CanvasSource`: `BACKDROP` and `fillsPhone`, on upstream's one line; `sleeveUrl` field |
| `ui/components/BrowseActionsSheet.kt` | `ForkOfflineRow(target, …)` before the Open row |
| `ui/components/SongActionsSheet.kt` | "Start radio" hidden for a `radio:` station, and labelled "Start radio (Instant Mix)" on a Navidrome song; `ForkPlayerMenuRows(song, palette.accent)` after the sleep timer, player menu only |
| `ui/player/CanvasArtworkPlayer.kt` | `playWhenReady` also needs `isPlaying` (and it is a key of that `LaunchedEffect`): a clip freezes on its frame while the music is paused. Album headers pass `true`, unaffected |
| `ui/screens/SettingsSheet.kt` | `SettingsSearch`, `SettingsGroupScope`, `SearchableSettingsGroup` are `internal`. One line each from `ForkSettingsRows.kt`: `forkNavidromeRows()` after "Account & integrations", `forkCrossfadeOnSkipRow(crossfade > 0 \|\| smartFade)` before Automix (outside upstream's `if (!smartFade)`), `forkCanvasRows(animatedCanvas)` after "Animated cover art", `forkCacheRows()` after "Clear image cache", `ForkSettingsGroup(search)` before Advanced. The "Clear song cache" dialog: `ForkClipCache.clearWithSongs()`, and says so (`fork_clear_song_cache_also`). The "Full-screen cover art" row takes the name `fork_fullscreen_cover_art` |
| `ui/screens/SourcesScreen.kt` | `RemoteLibrarySection` is `internal`; `ForkNavidromeSourceSection()` after the SMB section |
| `ui/screens/LibraryPhoneParts.kt` | `libraryLinks()`: `*forkLibraryLinks { … }` before the Spotify link; `deviceFolderIcon()`: `ForkNavidromeIcon` |
| `ui/screens/DetailScreen.kt` | `canvasEnabled` is also gated on `ForkCanvasSwitch.covers`; `sortedForDetail`: `SongSort.REVERSED`; artist rows: `val maxItems = forkRowMax(…)` replaces `ARTIST_ROW_MAX_ITEMS` in two lines (+ import) |
| `sharedUi/…/ui/components/Common.kt` | Track number `Text`: `softWrap = false` + `wrapContentWidth(unbounded = true)` (+ import), so 3-digit numbers aren't cut to 2 |
| `sharedUi/…/ui/player/NowPlayingScreen.kt` | `ForkPreloadCovers(queue, song)` after `remoteArt`. `val (canvas, forkLandscapeClip) = forkCanvases(rememberCanvasArtwork(song), …)`. `spotifyCanvasOnPhone` uses `fillsPhone` and `forkFillsScreen(…)`; `spotifyCanvasFullscreen` and `clipFullscreen` use `fillsPhone`. `forkIntro = rememberForkBackdropIntro(…)` after `clipFullscreen`; the hero clip's `presentationAlpha` is multiplied by `forkIntro.alpha` and its `onRenderedChanged` goes through `forkIntro.rendered`. Landscape `background` slot: `ForkLandscapeBackdrop(it, fullArtworkBlurImage, …)` after the blur; landscape sleeve drops its clip only when it is the backdrop's own. Canvas deck: `if (true \|\| reduceDynamicBlur …)` (gradient, no glass blur) and the controls dim's target `0f`. `ForkPausedCover(…)`. The star's gate also lets `song.isNavidrome` through (+ import). `heroMode`: `forkBigArtFits(…)` in place of `playerFillsWindow(…)`; `rememberForkBackdropIntro`: only with `fullBleedArt`. `fullBleedArtworkAvailable()`: always true. Both `CanvasArtworkPlayer` calls wrapped in `key(url)`. `heroMode`: `forkArtAwake` too |
| `sharedUi/…/ui/player/LandscapePlayer.kt` | The star's gate also lets `song.isNavidrome` through (+ import) |
| `sharedUi/…/ui/screens/HomeScreen.kt` | `forkHome = rememberForkHome()`; upstream's shelf call runs inside `forkHomeFeed(forkHome, state.data, …) { list, firstIsHero, keyPrefix -> … }`; the load-more effect also needs `forkHome.loadsMore`; `itemsIndexedShelves` takes a `keyPrefix` |
| `sharedUi/…/ui/screens/ExploreScreen.kt` | `val fork = rememberForkExplore()`; `forkPanelsAbove(…)` and `if (!fork.panels \|\| fork.youtubeOpen)` before `when (state)`; `forkPanelsBelow(…)` after it; `MoodGenreRow` is `internal` |
| `sharedUi/…/ui/screens/LibraryScreen.kt` | `item(key = "shelf:navidrome-playlists") { ForkLibraryPlaylistsRow(…) }` after the on-device row; `LibraryGridShelf`: `val maxItems = forkRowMax(…)` replaces `LIBRARY_ROW_MAX_ITEMS` in two lines (+ import) |

## Gotchas

- Navidrome songs carry `albumId` / `artistId` set to the library's own album and artist pages (`LocalGroups` ids). That is what keeps the player from asking YouTube for "links" about a song it has never heard of, and it makes the player's album and artist taps open the library pages. No upstream hook needed.

- Stream and cover URLs send Subsonic's `v` parameter as `%76`. The player treats any URI with a `v` query parameter as a YouTube watch link, so a literal `v=1.16.1` made every Navidrome song fail ("URL not accepted: https://www.youtube.com/watch?v=1.16.1"). Navidrome decodes the key and still reads it.

## The desktop app (branch `pc`)

Upstream removed `desktopApp` and `desktopUi` in `e4dd63d8`; `pc` restores them from `e4dd63d8^`, so they count as upstream files.

| File | Hook |
|---|---|
| `settings.gradle.kts` | `include(":desktopUi")`, `include(":desktopApp")`, only on JDK 21 or newer (the Android image and CI run on 17) |
| `.gitignore` | `/desktopApp/bin/` |
| `desktopUi/build.gradle.kts` | `CMP_DESKTOP`: the build's target decides, not its host, so a Windows zip cross-built from Linux gets Compose 1.10.3 |
| `desktopApp/native/window/CMakeLists.txt` | Cross-building: the same `jni_md.h` stand-in the analyser uses |
| `desktopApp/.../Main.kt` | `ForkDebugBridge.install()`, `ForkNavidrome.install()`, `ForkDebugBridge.attach(composeWindow)` |
| `desktopApp/.../DesktopApp.kt` | `ForkDebugBridge.queue` and `.engine`. `DesktopDestination.NAVIDROME` with its sidebar row and page. `rate()`: a Navidrome song's heart is its star on the server |
| `desktopApp/.../DesktopApp.kt` (pages) | `startRadio`: `forkStation = true`. `openArtist` and the collection loader: `ForkNavidrome` answers a server page. `loadHome`: the server's shelves lead Home |
| `desktopApp/.../DesktopAutoplay.kt` | `tracksFor`: `forkStation`, a Navidrome seed takes `ForkNavidrome.tracks` |
| `desktopApp/.../DesktopSongMenuUi.kt` | "Start radio (Instant Mix)" on a Navidrome song. `DesktopMenuRow` is internal; the player's menu adds `ForkPlayerMenuRows` |
| `desktopApp/.../DesktopApp.kt` (Home, library) | `loadHome`, `publishHomeShelves`: Spotify's mixes close Home, reloaded on sign-in. `DesktopLocalMusicPage.forkPlayFrom`: the Navidrome page plays the list on screen (an album, not the whole library) |
| `desktopApp/.../DesktopPlayerHost.kt` (auto-hide) | `spotifyCanvasAutoHide` off, the phone's default |
| `desktopApp/.../DesktopApp.kt` (offline) | `ForkOfflineButton` on a server album or playlist page, and on an album on the Navidrome page. Home reloads when offline changes. `onOpenPage` |
| `desktopApp/.../DesktopArtwork.kt` | `load()`: a kept cover first. Budget a tenth of the heap, was a sixth |
| `desktopApp/.../DesktopCanvasVideo.kt` | One copy per frame, not three, and a replaced frame is closed: `forkHandedOut` |
| `desktopApp/.../DesktopTrackAnalyzer.kt` | `results` keeps 12 analyses in RAM (from PC-Continued `c310d0e3`) |
| `desktopApp/.../DesktopSpotifyCanvas.kt` | `accessToken()`: the page's own token first (`ForkSpotifyToken`) |
| `desktopApp/.../DesktopApp.kt` (notice) | `ForkNotice.Host()`. `startRadio`: "No similar songs found" on a Navidrome song |
| `desktopApp/.../DesktopApp.kt` (switches) | `ForkPlayerMenu.onFullBleedChange`. Settings: "Fullscreen cover art" renamed, "Backdrop videos" and "Animated covers" under "Animated canvas". Album headers follow "Animated covers" |
| `desktopUi/.../player/NowPlayingScreen.kt` | The heart also shows for a Navidrome song (and in `LandscapePlayer.kt`). `forkCanvases`, `ForkLandscapeBackdrop` and `fillsPhone`, as on the phone (copies of `ForkSleeveClip`, `ForkBackdropIntro`, `ForkLandscapeBackdrop` live in `desktopUi`). `heroMode` as on the phone: `forkFillsScreen`, `forkBigArtFits`, `rememberForkArtAwake`; publishes `ForkCanvasSwitch.bigArtFits` for the menu |
| `desktopApp/.../DesktopCanvas.kt` | `fileFor`: the server's clips go through `ForkNavidrome.fetchClip`; a failed download is deleted (**fixes upstream**); the file's extension from `ForkClipCache.extension`, so a backdrop can be told from a cover |
| `desktopApp/.../DesktopMediaCache.kt` | `trim()`: `ForkClipCache.trim()` first, and the song limit leaves `.mp4` clips alone |
| `desktopApp/.../DesktopApp.kt` (settings) | `DesktopSettingsSection.NAVIDROME` and its `ForkNavidromeSettingsGroup()`. Storage: `ForkClipCacheRows()` after "Clear image cache", `ForkClipCache.afterSongCacheCleared()` in "Clear song cache". `SettingsGroup`, `SettingsToggle`, `SettingsRow` are internal. Home reloads when "Navidrome on Home" changes |
| `desktopApp/.../DesktopSourceRegistry.kt` | `resolveLive()` and `resolve()`: `ForkNavidrome.stream(song)` first |
| `desktopApp/.../DesktopPlaybackTracker.kt` | `onPlaying`, `onProgress`: `ForkYouTubeHistory` first (Navidrome plays into YouTube history; the switch is on the Navidrome page) |
| `desktopApp/.../DesktopLyricsClient.kt` | `lookup()`: the server's timed lyrics first |
| `desktopApp/.../DesktopPlayerHost.kt` | `canvasFor()`: `NavidromeClips.canvasFor(song)` first |
| `desktopApp/.../DesktopScrobbling.kt` | `updateNowPlaying()`, `onPlaybackEnded()`: `NavidromeScrobble`; both do nothing for a radio station |
| `desktopApp/.../ForkRadio.kt` (new) | Internet radio, the phone's Library page: stations from the shared `NavidromeRadio` (moved from `app`, its logo prefs now a `NavidromePlatform.Store`), a page in place of the library on the Navidrome page, hidden offline; logo keys hashed for Java Preferences' 80 characters |
| `desktopApp/.../DesktopApp.kt` (radio) | `ForkNavidromeScreen`: `radio = { ForkRadioPage(…) }`. `playPrevious`: on a station, always the previous row |
| `desktopApp/.../DesktopAudioEngine.kt` (radio) | `load`: a station starts at 0. `seekTo`: nothing on a station. `prepareNext`: no read-ahead deck for a station |
| `desktopApp/.../DesktopSongMenuUi.kt` (radio) | No "Start radio" and no `ForkPlayerMenuRows` on a station, as on the phone |
| `desktopApp/.../DesktopCanvas.kt` (radio) | `lookup`: none for a station |
| `desktopApp/.../DesktopAudioEngine.kt` (Crossfade on skip) | `Command.ForkSkip` and its `drainCommands` line. `loadInternal`: drops `forkSkipTicket`. `prepareNext`: held while one is set. `finishTrack`: no `onEnded()` while one is set. `ClearUpcoming`: past the switch, `promoteUpcoming()` first. The section `forkSkipTicket`, `forkSkipTo`, `forkSkipPlainly`, `forkStartSkip` |
| `desktopApp/.../DesktopApp.kt` (Crossfade on skip) | `playCurrent(forkSkipSeconds)`: above 0 it calls `forkSkipTo` instead of `load()`. `playNext` (two lines), `playPrevious` pass it. Settings: the "Crossfade on skip" switch after "Automix on skip" |
| `desktopApp/.../ForkCrossfadeOnSkip.kt` (new) | The switch (on by default, as on the phone), the skip's fade length and plan |
| `desktopUi/.../player/NowPlayingScreen.kt` (covers) | `ForkPreloadCovers(queue, song)` |
| `desktopUi/.../player/ForkPreloadCovers.kt` (new) | Copy of the `sharedUi` file: the next two covers warmed at the player's size |
| `desktopApp/.../DesktopApp.kt` (search) | `forkSearchRows`. `search()`: `ForkNavidromeSearch.start`. `SearchScreen` call: `results` shows the server's songs while YouTube loads or fails and ahead of its rows, `loadingMore = searchLoading`, `onSongClick`: a Navidrome row plays the server's rows in order and isn't recorded in recents |
| `desktopUi/.../screens/SearchScreen.kt` | `searchSections()`: the server's songs under a "Navidrome" heading, first |
| `desktopApp/.../DesktopApp.kt` (Explore) | `openMoodGenre()`: a server genre's tile opens as a collection page |
| `desktopUi/.../screens/ExploreScreen.kt` | `val fork = rememberForkExplore()`; `forkPanelsAbove(…)` and `if (!fork.panels \|\| fork.youtubeOpen)` before `when (state)`; `MoodGenreRow` is `internal` |
| `desktopApp/.../ForkQueues.kt` (new) | Saved queues, the phone's `ForkQueues` and its row: `bitchord/fork_queues.json` in the data folder (max 20); a Navidrome row keeps no address, only its cover's id, and gets both from today's login when played |
| `desktopApp/.../DesktopApp.kt` (saved queues) | `playCurrent`: `startAtMs`, passed to `playbackEngine.load`. After `playSongs`: `ForkQueues.currentQueue`, `.playingAtMs`, `.restore` |
| `desktopApp/.../DesktopSongMenuUi.kt` (saved queues) | `forkSavedQueues` and its drawer at the top of `DesktopMenuSurface`; `ForkSavedQueuesRow` after `ForkPlayerMenuRows` |

## Upstream bugs the fork fixes

Worth sending upstream, and worth checking at each merge whether upstream fixed them its own way (then drop the fork lines):

- `MainActivity.playDetailSongs()`: tapping a song on an album/playlist page queued the page's running order whatever the sort (A–Z, date, …), so Next didn't follow what the page shows.
- `AudioCache.prefetchQueue()`: WebDAV/SMB ids were warmed as YouTube ids (wasted lookups).
- `StreamResolver.signatureTimestamp()`: NewPipe wasn't initialised when no YouTube stream had been resolved yet, so the YouTube history couldn't register.
