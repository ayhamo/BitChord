# Fork changes

Everything this fork adds lives in new files where possible. This file lists every place it touches an **upstream** file, so a conflicting upstream merge can be redone by hand. Fork lines in upstream files carry a `// fork` comment (or are in a block that starts with one).

## New files

| File | What |
|---|---|
| `docker/` | Docker build (`build.ps1`, `Dockerfile`); `docker/keys` holds the debug key (gitignored) |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeSettings.kt` | Server URL, username, password in their own encrypted prefs (`bitchord_navidrome`) |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeClient.kt` | Subsonic client: token auth, `search3` paging, raw stream and cover URLs |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeRepository.kt` | The library as `Song` rows (tags, per-song covers), cached in memory |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeLyrics.kt` | Timed lyrics for Navidrome songs: a kept song's `.lrc`, then the lyrics cache (`cache/navidrome_lyrics`: what showed last time, server's or online), then `getLyricsBySongId`; `clearCache()` for Settings |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeClips.kt` | x-clip cover clips: real path from `/api/song/<id>`, `/rest/x-clip/<folder>/<stem>.cover.mp4` played in the cover square, `.background.mp4` backdrops (with the cover clip as `sleeveUrl`), Navidrome login (`/auth/login`) added by an interceptor, 7-day "no clip" notes (`bitchord_navidrome_clips` prefs). |
| `app/src/test/java/com/music/bitchord/NavidromeClipsTest.kt` | Clip URL from a song path |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeHome.kt` | Album shelves (recently added / recent / most played / random) laid over the top of Home |
| `app/src/test/java/com/music/bitchord/NavidromeLyricsTest.kt` | Lyrics answer → player lines (offset, word stamps, plain text ignored) |
| `app/src/main/res/values/strings_fork.xml` | Fork strings |
| `app/src/main/java/com/music/bitchord/fork/ForkSettings.kt` | Fork switches (`bitchord_fork` prefs): crossfade on skip, backdrop videos, animated covers, report plays to Navidrome, Navidrome on Home. On its first start it turns upstream's "Spotify Canvas auto-hide" off once (`canvas_manual_set`), so the controls over a clip stay until tapped away; it can be turned back on in Settings |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeStars.kt` | The heart/star for Navidrome songs as the server's star (`star`/`unstar`), seeded from the songs' `starred` |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromePlaylists.kt` | Server playlists as pages (`local:group:playlist:navidrome:<id>`), reached from the Home "Playlists" shelf |
| `shared/src/commonMain/kotlin/com/music/bitchord/data/model/ForkSong.kt` | `NAVIDROME_ID_PREFIX`, `Song.isNavidrome` (readable from the shared UI) |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeScrobble.kt` | "Now playing" and finished plays sent to Navidrome (`scrobble`, submission false/true) |
| `app/src/main/java/com/music/bitchord/ui/screens/ForkSettingsRows.kt` | The fork switches as full settings rows, for the "Fork settings" group |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ForkPausedCover.kt` | The cover drawn back over a full-screen clip while paused |
| (ForkSleeveClip.kt) | `ForkCanvasSwitch.backdrops` / `.covers`: "Backdrop videos" (a clip filling the screen: server backdrops, Spotify Canvas) and "Animated covers" (a clip in the cover square: server cover clips, Apple Music etc.), flows the player reads live (ForkSettings persists them); `forkCanvases()` applies them |
| `app/src/main/java/com/music/bitchord/fork/PlayerDecoderNames.kt` | The decoder each ExoPlayer last stood up, so a crossfade handoff can publish it (the Audio Pipeline showed "—" after every crossfade) |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeAlbums.kt` | Navidrome album pages (`local:group:album:navidrome:<name>`) from `getAlbum` (album id remembered from songs/Home cards, else `search3`) instead of the whole library; the album's own cover and artist for the header |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeSongInfo.kt` | Song Info for Navidrome songs from `getSong`: release date, your plays, rating/star |
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
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeArtists.kt` | Navidrome artist pages from one paged `search3` (exact artist tag), not the whole library |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeGenres.kt` | The server's genres as Explore tiles (`getGenres`, a `byGenre` album cover each) and genre pages (`getSongsByGenre`, id `local:group:genre:navidrome:<genre>`) |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/ForkExplore.kt` | Explore's "YouTube", "Navidrome" (server genres) and "Spotify" (mixes) panels, each collapsible |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/ForkHome.kt` | Home in collapsible "Navidrome", "Spotify" and "YouTube" sections (`NavidromeHome.onTop` says how many leading shelves are the server's and Spotify's) |
| `app/src/main/java/com/music/bitchord/fork/ForkSpotifyMixes.kt` | The signed-in Spotify account's mixes ("Made For You" category via the Web API, plus Spotify-owned playlists in the library) as a Home shelf and Explore tiles; they open as upstream's Spotify playlist page (tracks matched to YouTube) |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ForkLandscapeBackdrop.kt` | Landscape backdrop (a server backdrop or a Spotify Canvas): the clip fills the right half behind the controls and melts into upstream's pre-blurred cover (`fullArtworkBlurImage`), which fills the rest; the cover stays on the left; same cover hold as portrait (`rememberForkBackdropIntro`) |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ForkBackdropIntro.kt` | A full-screen clip waits 2.5 s on a new track (the cover is seen first) and then fades in over 1.2 s; not again for the same track |
| `app/src/main/java/com/music/bitchord/fork/ForkNetwork.kt` | The network for the offline features: Wi-Fi (unmetered) always, mobile data only with "Download over mobile data"; offline mode (switch, or no network) |
| `app/src/main/java/com/music/bitchord/fork/ForkOfflineService.kt` | Foreground service (dataSync) keeping "Available offline" downloads going in the background, with a progress notification and Cancel |
| `app/src/main/java/com/music/bitchord/fork/ForkPreload.kt` | "Pre-load next songs": the next N Navidrome songs read whole into the song cache (upstream's read-ahead writer), on allowed networks |
| `app/src/main/java/com/music/bitchord/ui/components/ForkOfflineBanner.kt` | Strip above the mini player in offline mode ("Go online" when switched on by hand; "No network" otherwise) |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeRadio.kt` | The server's internet radio stations (`getInternetRadioStations`) as the Library "Internet radio" page; `.pls`/`.m3u` resolved to the stream; a `#bitchord-radio` mark keeps streams out of the song cache; logos: the server's `ra-<id>` cover (what psysonic uploads), else radio-browser.info, kept a week (`bitchord_radio_logos` prefs) |
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
| `MainActivity.kt` | `ForkQueuesBinding(controller, scope) { … playFrom(…) }` right after `playFrom()`. `localFolderStates.getOrPut`: `LocalFolderState().forkDefaults(page.browseId)` (+ import; Navidrome opens on albums). `playDetailSongs()`: a `forkSort` line, the page's context sorted like the page (`sortedForDetail`), no continuation token for a sorted page (**fixes upstream**, see below). `ForkOfflineBanner()` after `QueueActionNoticeHost`. `onToggleVersion`: not for Navidrome ids or radio stations. `ForkNavidromeEditor(hazeState)` before the SMB editor. `localizedLabel`: `SongSort.REVERSED` |
| `ui/MainViewModel.kt` | `home` is `NavidromeHome.onTop(_home, …)`. Before `val selected = …` in the lyrics job: `NavidromeLyrics.remember(…)`. First line of `setLike()`: `NavidromeStars.setLike(…)`. In `init`, after the WebDAV collector: `NavidromeHome.follow(…)`, which calls the new `forkBackOnline()` (above `refreshHome()`) when the network returns: Home loads again and a song with no lyrics asks again. First line of `openMoodGenre()`: `NavidromePages.tileSource(…)` opens a Spotify mix or a Navidrome genre as a page. `loadHome()` and `refreshHome()`: `launch { NavidromeHome.load(…) }`. `loadLibrarySongs()`: last line `NavidromeSearch.withLibrary(…)`. `remoteLibrary()`: a `NavidromeSettings.BROWSE_ID` branch. `openDetail()`: after `localPageState`, `NavidromeAlbums.header(browseId)` sets `artwork`/`credit`. `localPageState()`: `NavidromePages.pageState(browseId, ::text)` before `remoteLibrary(…)` |
| `playback/CrossfadeController.kt` | The skip code is one block at the end of the class (`crossfadeSkip()`, `isSkipBlend()`, `pendingSkipIndex()`, `skipTarget`, …) plus `SKIP_ARM_TIMEOUT_MS` after the class. One-line calls in upstream's code: the listener's discontinuity (`forkDropSkip()`) and item transition (`forkDropSkipOn(reason)`), `onSkipRequested()`, `finish()` (`forkDropSkip()`); `begin()` takes `targetIndex` (`nextIndex`, `forkArmTimeout(targetIndex)`); `driveArming()`: `forkSkipFailed(into)`; `driveFade()`: `isSkipBlend() \|\|` in the handoff condition, `&& !skipFadeOn()` in `settingSwitchedOff`; `bail()`: `forkTakeSkip()` around `finish()`; first line of `stillWorthFading()`. Natural endings and Automix's planning are untouched |
| `playback/PlaybackService.kt` | `newSessionPlayer()`: `.forkMasked(crossfade)` on the `SessionPlayer` (see `ForkSkipMaskPlayer`). `SessionPlayer`: `seekToPrevious`, `seekToPreviousMediaItem`, `seekToNextMediaItem`, `seekToNext` try `crossfade.crossfadeSkip(…)` first. `adoptPlayer()`: a skip blend (`isSkipBlend()`) is booked as `previousEnded = false`, reason SEEK (not scrobbled as finished, no "sleep after this song"); publishes the incoming decoder (`PlayerDecoderNames`) and `publishNerdStats()`. `buildPlayer()`: `.also(PlayerDecoderNames::track)`. `canLike()` also true for Navidrome ids; the notification's favorite goes through `NavidromeStars.rateOr`. `currentSourceName()`: "Navidrome". `prefetchAround()`: `ForkPreload.around(player)`. `submitListenBrainzFinished` / `submitListenBrainzPlayingNow`: `NavidromeScrobble` first |
| `playback/PlayerConnection.kt` | `toMediaItem()`: `offlineUri` also tries `NavidromeOffline.uriFor(videoId)`, so a Navidrome song whose kept file has gone streams from the server instead of being taken for a YouTube id |
| `playback/AudioCache.kt` | `playbackFactory()`: a `#bitchord-radio` stream bypasses the cache like file/content URIs. `forkReadAhead()` after `setUpstream()`. `prefetchQueue()`: `.filterNot(ForkPreload::skipsReadAhead)` (Navidrome, radio, and WebDAV/SMB: **fixes upstream**) |
| `playback/Autoplay.kt` | `loadAutoplayTracks()`: a Navidrome seed takes `NavidromeRepository.related()` (similar songs, else random) instead of a YouTube radio |
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
| `ui/components/SongActionsSheet.kt` | "Start radio" hidden for a `radio:` station; `ForkPlayerMenuRows(song, palette.accent)` after the sleep timer, player menu only |
| `ui/player/CanvasArtworkPlayer.kt` | `playWhenReady` also needs `isPlaying` (and it is a key of that `LaunchedEffect`): a clip freezes on its frame while the music is paused. Album headers pass `true`, unaffected |
| `ui/screens/SettingsSheet.kt` | `SettingsSearch`, `SettingsGroupScope`, `SearchableSettingsGroup` are `internal`. One line each from `ForkSettingsRows.kt`: `forkNavidromeRows()` after "Account & integrations", `forkCrossfadeOnSkipRow(crossfade > 0 \|\| smartFade)` before Automix (outside upstream's `if (!smartFade)`), `forkCanvasRows(animatedCanvas)` after "Animated cover art", `forkCacheRows()` after "Clear image cache", `ForkSettingsGroup(search)` before Advanced. The "Clear song cache" dialog: `ForkClipCache.clearWithSongs()`, and says so (`fork_clear_song_cache_also`) |
| `ui/screens/SourcesScreen.kt` | `RemoteLibrarySection` is `internal`; `ForkNavidromeSourceSection()` after the SMB section |
| `ui/screens/LibraryPhoneParts.kt` | `libraryLinks()`: `*forkLibraryLinks { … }` before the Spotify link; `deviceFolderIcon()`: `ForkNavidromeIcon` |
| `ui/screens/DetailScreen.kt` | `canvasEnabled` is also gated on `ForkCanvasSwitch.covers`; `sortedForDetail`: `SongSort.REVERSED`; artist rows: `val maxItems = forkRowMax(…)` replaces `ARTIST_ROW_MAX_ITEMS` in two lines (+ import) |
| `sharedUi/…/ui/components/Common.kt` | Track number `Text`: `softWrap = false` + `wrapContentWidth(unbounded = true)` (+ import), so 3-digit numbers aren't cut to 2 |
| `sharedUi/…/ui/player/NowPlayingScreen.kt` | `ForkPreloadCovers(queue, song)` after `remoteArt`. `val (canvas, forkLandscapeClip) = forkCanvases(rememberCanvasArtwork(song), …)`. `spotifyCanvasOnPhone` uses `fillsPhone` and `forkFillsScreen(…)`; `spotifyCanvasFullscreen` and `clipFullscreen` use `fillsPhone`. `forkIntro = rememberForkBackdropIntro(…)` after `clipFullscreen`; the hero clip's `presentationAlpha` is multiplied by `forkIntro.alpha` and its `onRenderedChanged` goes through `forkIntro.rendered`. Landscape `background` slot: `ForkLandscapeBackdrop(it, fullArtworkBlurImage, …)` after the blur; landscape sleeve drops its clip only when it is the backdrop's own. Canvas deck: `if (true \|\| reduceDynamicBlur …)` (gradient, no glass blur) and the controls dim's target `0f`. `ForkPausedCover(…)`. The star's gate also lets `song.isNavidrome` through (+ import) |
| `sharedUi/…/ui/player/LandscapePlayer.kt` | The star's gate also lets `song.isNavidrome` through (+ import) |
| `sharedUi/…/ui/screens/HomeScreen.kt` | `forkHome = rememberForkHome()`; upstream's shelf call runs inside `forkHomeFeed(forkHome, state.data, …) { list, firstIsHero, keyPrefix -> … }`; the load-more effect also needs `forkHome.loadsMore`; `itemsIndexedShelves` takes a `keyPrefix` |
| `sharedUi/…/ui/screens/ExploreScreen.kt` | `val fork = rememberForkExplore()`; `forkPanelsAbove(…)` and `if (!fork.panels \|\| fork.youtubeOpen)` before `when (state)`; `forkPanelsBelow(…)` after it; `MoodGenreRow` is `internal` |
| `sharedUi/…/ui/screens/LibraryScreen.kt` | `item(key = "shelf:navidrome-playlists") { ForkLibraryPlaylistsRow(…) }` after the on-device row; `LibraryGridShelf`: `val maxItems = forkRowMax(…)` replaces `LIBRARY_ROW_MAX_ITEMS` in two lines (+ import) |

## Gotchas

- Navidrome songs carry `albumId` / `artistId` set to the library's own album and artist pages (`LocalGroups` ids). That is what keeps the player from asking YouTube for "links" about a song it has never heard of, and it makes the player's album and artist taps open the library pages. No upstream hook needed.

- Stream and cover URLs send Subsonic's `v` parameter as `%76`. The player treats any URI with a `v` query parameter as a YouTube watch link, so a literal `v=1.16.1` made every Navidrome song fail ("URL not accepted: https://www.youtube.com/watch?v=1.16.1"). Navidrome decodes the key and still reads it.

## Upstream bugs the fork fixes

Worth sending upstream, and worth checking at each merge whether upstream fixed them its own way (then drop the fork lines):

- `MainActivity.playDetailSongs()`: tapping a song on an album/playlist page queued the page's running order whatever the sort (A–Z, date, …), so Next didn't follow what the page shows.
- `AudioCache.prefetchQueue()`: WebDAV/SMB ids were warmed as YouTube ids (wasted lookups).
- `StreamResolver.signatureTimestamp()`: NewPipe wasn't initialised when no YouTube stream had been resolved yet, so the YouTube history couldn't register.
