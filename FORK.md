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
| `app/src/main/java/com/music/bitchord/fork/ForkSettings.kt` | Fork switches (`bitchord_fork` prefs): crossfade on skip, Navidrome cover clips, backdrop clips, report plays to Navidrome, Navidrome on Home |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeStars.kt` | The heart/star for Navidrome songs as the server's star (`star`/`unstar`), seeded from the songs' `starred` |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromePlaylists.kt` | Server playlists as pages (`local:group:playlist:navidrome:<id>`), reached from the Home "Playlists" shelf |
| `shared/src/commonMain/kotlin/com/music/bitchord/data/model/ForkSong.kt` | `NAVIDROME_ID_PREFIX`, `Song.isNavidrome` (readable from the shared UI) |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeScrobble.kt` | "Now playing" and finished plays sent to Navidrome (`scrobble`, submission false/true) |
| `app/src/main/java/com/music/bitchord/ui/screens/ForkSettingsRows.kt` | The fork switches as full settings rows, for the "Fork settings" group |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ForkPausedCover.kt` | The cover drawn back over a full-screen clip while paused |
| (ForkSleeveClip.kt) | `ForkCanvasSwitch.backdrops`: the backdrop switch as a flow the player reads (ForkSettings persists it); `forkCanvas()` applies it live |
| `app/src/main/java/com/music/bitchord/fork/PlayerDecoderNames.kt` | The decoder each ExoPlayer last stood up, so a crossfade handoff can publish it (the Audio Pipeline showed "—" after every crossfade) |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeAlbums.kt` | Navidrome album pages (`local:group:album:navidrome:<name>`) from `getAlbum` (album id remembered from songs/Home cards, else `search3`) instead of the whole library; the album's own cover and artist for the header |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeSongInfo.kt` | Song Info for Navidrome songs from `getSong`: release date, your plays, rating/star |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/ForkSongInfo.kt` | Provider slot the app fills, asked first by `SongInfoRepository.load` |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ForkSleeveClip.kt` | `forkInSleeve()`: where a backdrop can't fill the screen, the sleeve plays the cover clip (`sleeveUrl`) or the still cover, never the backdrop; `forkFillsScreen()`: a backdrop also fills any portrait window, not only phone width |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeSearch.kt` | The Search tab's "Library" source gets the Navidrome songs after the device's |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ForkPreloadCovers.kt` | The next two songs' covers fetched at player size ahead of time (no black player at a crossfade/skip) |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/ForkLibraryPlaylists.kt` | "Navidrome albums" (all, A–Z) and "Navidrome playlists" Library rows (filled by `NavidromeHome.load`) |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeOffline.kt` | "Available offline": whole albums/playlists kept in `files/navidrome_offline` (original files, covers at 1200 px, backdrops and cover clips with `clips.json`, timed lyrics as `.lrc`), never evicted; two files at a time, waiting for a network the "Download over" switches allow; covers and clips answered from disk by `serve()` in the HTTP clients; sizes before and after keeping; `offline.json` list; offline-mode listing (`keptSongs`); Library "Available offline" row |
| `app/src/main/java/com/music/bitchord/fork/ForkYouTubeHistory.kt` | Navidrome plays into the YouTube Music history: strict YouTube match (title, artists, length ±5 s), then upstream's `PlaybackTracker` with that id (switch, off by default) |
| `app/src/main/java/com/music/bitchord/fork/ForkClipCache.kt` | Clip cache size (adjustable evictor) and clearing backdrops / cover clips by kind |
| `app/src/main/java/com/music/bitchord/ui/components/ForkMenuRows.kt` | "Backdrop videos" row in the player's song menu; "Available offline" row in an album/playlist sheet |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeArtists.kt` | Navidrome artist pages from one paged `search3` (exact artist tag), not the whole library |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeGenres.kt` | The server's genres as Explore tiles (`getGenres`, a `byGenre` album cover each) and genre pages (`getSongsByGenre`, id `local:group:genre:navidrome:<genre>`) |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/ForkExplore.kt` | Explore's "YouTube", "Navidrome" (server genres) and "Spotify" (mixes) panels, each collapsible |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/ForkHome.kt` | Home in collapsible "Navidrome", "Spotify" and "YouTube" sections (`NavidromeHome.onTop` says how many leading shelves are the server's and Spotify's) |
| `app/src/main/java/com/music/bitchord/fork/ForkSpotifyMixes.kt` | The signed-in Spotify account's mixes ("Made For You" category via the Web API, plus Spotify-owned playlists in the library) as a Home shelf and Explore tiles; they open as upstream's Spotify playlist page (tracks matched to YouTube) |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ForkLandscapeBackdrop.kt` | Landscape backdrop: the clip on the right behind the controls, the screen a blur of it (frames read back once a second), the cover kept on the left; two layouts behind `FORK_LANDSCAPE_STYLE` (delete the unused branch once chosen) |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ForkBackdropIntro.kt` | A full-screen clip waits 2.5 s on a new track (the cover is seen first) and then fades in over 1.2 s; not again for the same track |
| `app/src/main/java/com/music/bitchord/fork/ForkNetwork.kt` | The network for the offline features: Wi-Fi (unmetered) always, mobile data only with "Download over mobile data"; offline mode (switch, or no network) |
| `app/src/main/java/com/music/bitchord/fork/ForkOfflineService.kt` | Foreground service (dataSync) keeping "Available offline" downloads going in the background, with a progress notification and Cancel |
| `app/src/main/java/com/music/bitchord/fork/ForkPreload.kt` | "Pre-load next songs": the next N Navidrome songs read whole into the song cache (upstream's read-ahead writer), on allowed networks |
| `app/src/main/java/com/music/bitchord/ui/components/ForkOfflineBanner.kt` | Strip above the mini player in offline mode ("Go online" when switched on by hand; "No network" otherwise) |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeRadio.kt` | The server's internet radio stations (`getInternetRadioStations`) as the Library "Internet radio" page; `.pls`/`.m3u` resolved to the stream; a `#bitchord-radio` mark keeps streams out of the song cache; logos: the server's `ra-<id>` cover (what psysonic uploads), else radio-browser.info, kept a week (`bitchord_radio_logos` prefs) |
| `app/src/main/java/com/music/bitchord/fork/ForkQueues.kt` | Saved queues (try-out): the queue, its song and second, saved under a name in `files/fork_queues.json` (max 20), played back from there |
| `docker/keys/release/` (gitignored) | The fork's release key `bitchord-release.jks` + `keystore.properties` (random password). Back it up: without it no update installs over a release |

## Hooks in upstream files

| File | Hook |
|---|---|
| `BitChordApplication.kt` | `NavidromeSettings.init(this)` and `ForkSettings.init(this)` right after `AppSettings.init` |
| `playback/CrossfadeController.kt` | Crossfade on skip: `crossfadeSkip()`, `isSkipBlend()` and `skipTarget` after `onSkipRequested()` (which now clears `skipTarget`); `begin()` takes `targetIndex`; `bail()` carries out a skip that never got to blend; `finish()` clears `skipTarget`; `stillWorthFading()` lets a skip arm without a next item; the listener's seek / playlist-change callbacks clear `skipTarget` |
| `playback/PlaybackService.kt` | `adoptPlayer()`: a skip blend (`crossfade.isSkipBlend()`) is booked as `previousEnded = false`, reason SEEK, so it isn't scrobbled as finished or treated as "sleep after this song". Forwarding player: `seekToPrevious`, `seekToPreviousMediaItem`, `seekToNextMediaItem`, `seekToNext` try `crossfade.crossfadeSkip(…)` first; `forkPreviousTarget()` (Media3's 3 s rule). `buildPlayer()`: `.also(PlayerDecoderNames::track)`; `adoptPlayer()` after `onTrackBecameCurrent`: publishes the incoming player's decoder name and calls `publishNerdStats()`. `currentSourceName()`: first line returns "Navidrome" for Navidrome ids (was "Unknown") |
| `ui/screens/SettingsSheet.kt` | "Crossfade on skip" sub-row under the Crossfade slider (shown when the slider is above 0) |
| `ui/MainViewModel.kt` | `remoteLibrary()`: a `NavidromeSettings.BROWSE_ID` branch; in `init`, a collector after the WebDAV one that reloads the Navidrome page and Home shelves when its settings change; `home` is `NavidromeHome.onTop(_home, …)`; `loadHome()` and `refreshHome()` each launch `NavidromeHome.load()` |
| `playback/AudioCache.kt` | `prefetchQueue()`: Navidrome ids filtered out of the YouTube read-ahead |
| `playback/Autoplay.kt` | `loadAutoplayTracks()`: a Navidrome seed takes `NavidromeRepository.related()` (similar songs, else random) instead of a YouTube radio |
| `ui/screens/LibraryPhoneParts.kt` | `libraryLinks()`: two `collectAsStateWithLifecycle` lines and the Navidrome link; `deviceFolderIcon()`: Navidrome icon |
| `ui/screens/SourcesScreen.kt` | `onEditNavidrome` parameter (defaults to `{}`) and a `RemoteLibrarySection` for Navidrome after the SMB one |
| `MainActivity.kt` | `showNavidromeEditor` state, `onEditNavidrome = …` passed to `SourcesScreen`, and the `ServerEditorHost` block before the SMB editor |
| `data/lyrics/EmbeddedLyrics.kt` | `forUri()`: first two lines hand a Navidrome stream URL to `NavidromeLyrics` (covers both the player screen and the service's lyric subtitle) |
| `shared/…/data/model/Models.kt` | `String.artworkAt()`: for a `/rest/getCoverArt` URL, `size=` is set to the asked px (`SUBSONIC_SIZE` regex next to `PARAM_SIZE_HINT`). Rows get small covers, the player a sharp 1200 px. Test in `ModelsTest.subsonicCoverArtTakesTheAskedSize` |
| `shared/…/data/Http.kt` | `forkInterceptor` property (set by `NavidromeClips.init`) and one `.addInterceptor { … forkInterceptor … }` line after the WebDAV one |
| `data/canvas/CanvasRepository.kt` | `canvasFor()`: `NavidromeClips.canvasFor(song)` tried first, right after the blank-id guard; no clip falls through to the catalogue searches as before |
| `ui/screens/SettingsSheet.kt` | "Navidrome cover clips" and "Navidrome backdrop clips" sub-rows (inside a fork `if (animatedCanvas)`) right after the upstream "Animated cover art" (canvas video) row; "Report plays to Navidrome" row after "Account & integrations"; a "Fork settings" `SearchableSettingsGroup` just before Advanced repeating all fork switches |
| `shared/…/data/canvas/CanvasArtwork.kt` | `CanvasSource`: `BACKDROP` value and `fillsPhone` (SPOTIFY or BACKDROP) |
| `sharedUi/…/ui/player/NowPlayingScreen.kt` | Three `== CanvasSource.SPOTIFY` checks (`spotifyCanvasOnPhone`, `spotifyCanvasFullscreen`, `clipFullscreen`) read `?.fillsPhone == true`, so a backdrop gets Spotify Canvas's full-screen presentation; `ForkPausedCover(…)` after the Canvas dim box |
| `ui/MainViewModel.kt` (stars, playlists) | `setLike()`: a first block for Navidrome ids (optimistic `LikeState`, `NavidromeStars.rate`, roll back on failure) before `requireSignIn()`; `localPageState()`: `NavidromePlaylists.pageState(…)` asked before `remoteLibrary(…)` |
| `sharedUi/…/NowPlayingScreen.kt`, `LandscapePlayer.kt` | The star's gate `signedIn && song.localUri == null` also lets `song.isNavidrome` through (+ import) |
| `playback/PlaybackService.kt` (stars) | `canLike()` also true for Navidrome ids; `toggleFavoriteFromNotification()` calls `NavidromeStars.rate` for them instead of `YtMusicRepository.rate` |
| `playback/PlaybackService.kt` (ListenBrainz) | First line of `submitListenBrainzFinished` / `submitListenBrainzPlayingNow`: `NavidromeScrobble.finished/nowPlaying` |
| `data/AppUpdateChecker.kt` | `LATEST_RELEASE_URL` points at `ayhamo/BitChord`; the tag has `fork-` stripped before `v` |
| `.github/workflows/fork-sync-release.yml` (new) | Daily: merge upstream's latest release, fork tests, `assembleProdRelease` signed with the fork's release key, certificate check, attestation, `fork-v<version>` release; on failure an issue + GitHub's failure email |
| `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` | Exclude `bitchord_navidrome.xml` and `bitchord_navidrome_plain.xml`, next to the `bitchord_sources` lines |
| `data/settings/AppSettings.kt`, `ui/screens/DetailScreen.kt`, `MainActivity.kt` | `SongSort.REVERSED` ("Reverse order", psysonic's reverse #): enum value after DEFAULT, `asReversed()` in `sortedForDetail`, label in `localizedLabel` |
| `sharedUi/…/ui/components/Common.kt` | Track number `Text`: `softWrap = false` + `wrapContentWidth(unbounded = true)` (+ import), so 3-digit numbers aren't cut to 2 |
| `ui/MainViewModel.kt` (albums) | `localPageState()`: `NavidromeAlbums.pageState(…)` after the playlists line; `openDetail()`: after `localPageState`, `NavidromeAlbums.header(browseId)` sets `artwork`/`credit` |
| `shared/…/data/canvas/CanvasArtwork.kt` | `sleeveUrl` field (a backdrop's cover clip) |
| `sharedUi/…/NowPlayingScreen.kt` (sleeve) | `rememberCanvasArtwork(song).forkInSleeve(playerFillsWindow(windowWidth))`; `ForkPausedCover(…)`: the cover back over the full-screen clip while paused |
| `shared/…/data/SongInfoRepository.kt` | First line of `load()`: `ForkSongInfo.provider` |
| `MainActivity.kt` (video) | `onToggleVersion`: not offered for Navidrome ids ("Convert to video") |
| `sharedUi/…/NowPlayingScreen.kt` (preload) | `ForkPreloadCovers(queue, song)` right after `remoteArt` |
| `ui/MainViewModel.kt` (search) | `loadLibrarySongs()`: last line `NavidromeSearch.withLibrary(…)` |
| `sharedUi/…/ui/screens/LibraryScreen.kt` | `item(key = "shelf:navidrome-playlists") { ForkLibraryPlaylistsRow(…) }` after the on-device row |
| `MainActivity.kt` (Library page) | `localFolderStates.getOrPut`: the Navidrome page's `LocalFolderState` starts on `ALBUMS` |
| `playback/CrossfadeController.kt` (skip handoff) | `pendingSkipIndex()` next to `isSkipBlend()`; `driveFade()`: a skip blend hands over on its first tick (`isSkipBlend() \|\|` in the handoff condition) |
| `playback/PlaybackService.kt` (skip masking) | `SessionPlayer`: `forkSkipIndex()`, `getCurrentMediaItemIndex`/`getCurrentPeriodIndex`/`getCurrentMediaItem` overrides, `getMediaMetadata` base, and position 0 / duration unset in the position and duration overrides while a skip arms (Media3's controller showed the next song, then flipped back) |
| `sharedUi/…/NowPlayingScreen.kt` (backdrop width) | `spotifyCanvasOnPhone` and the `canvas` line use `forkFillsScreen(…)` instead of `playerFillsWindow(windowWidth)` |
| `app/build.gradle.kts` | `prod` flavor: `applicationId = "com.ayhamo.bitchord"`, `app_name` "BitChord Fork" |
| `ui/screens/LibraryPhoneParts.kt` (icon) | Navidrome's folder icon is `LibraryMusic` (+ import) |
| `sharedUi/…/NowPlayingScreen.kt` (sharp video) | Canvas deck: `if (true \|\| reduceDynamicBlur …)` (gradient, no glass blur) and the controls dim's target `0f` (was 0.5f); the `canvas` line is `forkCanvas(…)` (backdrop switch read live) |
| `ui/components/SongActionsSheet.kt` | `ForkBackdropMenuRow(…)` after the sleep timer, player menu + Navidrome songs only |
| `ui/components/BrowseActionsSheet.kt` | `ForkOfflineRow(target, …)` before the Open row |
| `data/canvas/CanvasCache.kt` | `cache` is `internal`; evictor is `ForkClipCache.evictor` |
| `data/innertube/PlaybackTracker.kt` | First line of `onPlaying`: `ForkYouTubeHistory.intercept`; first line of `onProgress`: maps a Navidrome id to its YouTube match |
| `ui/screens/SettingsSheet.kt` (caches, history) | Clip cache size + clear backdrops + clear cover clips rows after "Clear image cache"; "Navidrome plays in YouTube history" after "Navidrome on Home"; all again in Fork settings |
| `ui/MainViewModel.kt` (artists, genres, mixes) | `localPageState()`: `NavidromeArtists.pageState` and `NavidromeGenres.pageState` after the albums line; `openMoodGenre()`: first block opens a Navidrome genre tile as a page; `openMoodGenre()`: a Spotify mix tile opens as its playlist page |
| `sharedUi/…/ui/screens/ExploreScreen.kt` | `val fork = rememberForkExplore()`; `forkYouTubeHeading(fork)` and `if (!fork.panels \|\| fork.youtubeOpen)` before `when (state)`; `forkNavidromePanel(…)` after it (reusing `MoodGenreRow`) |
| `shared/…/data/innertube/StreamResolver.kt` | `signatureTimestamp()`: `init` (NewPipe) before use; upstream relied on a YouTube stream having been resolved first, so with only Navidrome songs played the YouTube history could never register (upstream bug, worth sending upstream) |
| `ui/screens/SettingsSheet.kt` (shelves) | "Navidrome on Home" row after "Report plays to Navidrome", and again in Fork settings |
| `sharedUi/…/NowPlayingScreen.kt` (backdrop intro) | `val forkIntro = rememberForkBackdropIntro(…)` after `clipFullscreen`; the hero clip's `presentationAlpha` is multiplied by `forkIntro.alpha` and its `onRenderedChanged` goes through `forkIntro.rendered(…)` |
| `BitChordApplication.kt` (covers) | `coilHttpClient`: first interceptor runs `Http.forkInterceptor` (kept Navidrome covers come from disk) |
| `AndroidManifest.xml` | `.fork.ForkOfflineService` (dataSync) after upstream's DownloadService |
| `playback/AudioCache.kt` (pre-load) | `forkReadAhead()` after `setUpstream()`: the read-ahead cache source for `ForkPreload` |
| `playback/PlaybackService.kt` (pre-load) | First line of `prefetchAround()`: `ForkPreload.around(player)` |
| `ui/MainViewModel.kt` (lyrics cache) | Before `val selected = …` in the lyrics job: `NavidromeLyrics.remember(videoId, found?.lines)` |
| `ui/screens/SettingsSheet.kt` (offline) | Offline mode, Available offline storage, Download over mobile data, Pre-load next songs after "Navidrome plays in YouTube history"; "Clear lyrics cache" after "Clear cover clips"; all again in Fork settings |
| `MainActivity.kt` (sort skip) | `playDetailSongs()`: `forkSort` line; the page's playback context is sorted like the page (`sortedForDetail`) and no continuation token is passed for a sorted page. **Fixes upstream**: it queued the running order whatever the sort, so Next didn't follow A–Z, date or Reverse order |
| `MainActivity.kt` (offline strip) | `ForkOfflineBanner()` after `QueueActionNoticeHost`, above the mini player (floating bars only, not liquid glass) |
| `ui/MainViewModel.kt` (radio) | `localPageState()`: `NavidromeRadio.pageState` after the genres line |
| `ui/screens/LibraryPhoneParts.kt` (radio) | `libraryLinks()`: a `forkOffline` line and an "Internet radio" link after Navidrome, hidden offline (+ `Radio` icon import) |
| `playback/AudioCache.kt` (radio, shares) | `playbackFactory()`: a `#bitchord-radio` stream bypasses the cache like file/content URIs; `prefetchQueue()`: `radio:` ids filtered out of the YouTube read-ahead; WebDAV/SMB ids filtered out too (**fixes upstream**: they were warmed as YouTube ids) |
| `playback/CrossfadeController.kt` (failed standby) | `driveArming()`: bails at once when the standby has a `playerError` (a skip onto a failing stream no longer holds the old song for the 12 s arm timeout) |
| `MainActivity.kt` (saved queues) | `ForkQueues.bind(controller, scope) { … playFrom(…) }` right after `playFrom()` |
| `ui/components/SongActionsSheet.kt` (saved queues) | `ForkSavedQueuesRow(…)` after the backdrop row, player menu only |
| `ui/components/SongActionsSheet.kt` (radio) | "Start radio" hidden for a `radio:` station |
| `ui/player/CanvasArtworkPlayer.kt` (pause) | `playWhenReady` also needs `isPlaying` (and it is a key of that `LaunchedEffect`): a clip freezes on its frame while the music is paused (upstream kept it looping). Album headers pass `true`, unaffected |
| `ui/screens/DetailScreen.kt` (animated covers) | `canvasEnabled` is also gated on `ForkCanvasSwitch.covers` ("Animated covers"), for album and artist headers |
| `ui/components/SongActionsSheet.kt` (animated covers) | `ForkAnimatedCoversMenuRow(…)` after the backdrop row, player menu, not for radio stations |
| `ui/screens/SettingsSheet.kt` (animated covers) | "Animated covers" sub-row first under "Animated cover art", and again in Fork settings |
| `sharedUi/…/ui/screens/HomeScreen.kt` | `forkHome = rememberForkHome()`; the shelf call wrapped in a local `shelves(…)` and drawn per `ForkHome` section; `itemsIndexedShelves` takes a `keyPrefix` (the sections share one list) |
| `sharedUi/…/NowPlayingScreen.kt` (landscape backdrop) | `forkRawCanvas` line before `canvas`, and `forkLandscapeClip` after it; landscape `background` slot draws `ForkLandscapeBackdrop(…)` after the blur; landscape sleeve gets `null` for its clip while a backdrop plays beside it |
| `README.md` | A "This fork" note and "What this fork adds" section above upstream's header (a likely but easy merge conflict when upstream edits its top) |

## Gotchas

- Navidrome songs carry `albumId` / `artistId` set to the library's own album and artist pages (`LocalGroups` ids). That is what keeps the player from asking YouTube for "links" about a song it has never heard of, and it makes the player's album and artist taps open the library pages. No upstream hook needed.

- Stream and cover URLs send Subsonic's `v` parameter as `%76`. The player treats any URI with a `v` query parameter as a YouTube watch link, so a literal `v=1.16.1` made every Navidrome song fail ("URL not accepted: https://www.youtube.com/watch?v=1.16.1"). Navidrome decodes the key and still reads it.

## Upstream bugs the fork fixes

Worth sending upstream, and worth checking at each merge whether upstream fixed them its own way (then drop the fork lines):

- `MainActivity.playDetailSongs()`: tapping a song on an album/playlist page queued the page's running order whatever the sort (A–Z, date, …), so Next didn't follow what the page shows.
- `AudioCache.prefetchQueue()`: WebDAV/SMB ids were warmed as YouTube ids (wasted lookups).
- `StreamResolver.signatureTimestamp()`: NewPipe wasn't initialised when no YouTube stream had been resolved yet, so the YouTube history couldn't register.
