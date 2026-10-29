# Fork changes

Everything this fork adds lives in new files where possible. This file lists every place it touches an **upstream** file, so a conflicting upstream merge can be redone by hand. Fork lines in upstream files carry a `// fork` comment (or are in a block that starts with one).

## New files

| File | What |
|---|---|
| `docker/` | Docker build (`build.ps1`, `Dockerfile`); `docker/keys` holds the debug key (gitignored) |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeSettings.kt` | Server URL, username, password in their own encrypted prefs (`bitchord_navidrome`) |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeClient.kt` | Subsonic client: token auth, `search3` paging, raw stream and cover URLs |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeRepository.kt` | The library as `Song` rows (tags, per-song covers), cached in memory |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeLyrics.kt` | Timed lyrics from `getLyricsBySongId` for Navidrome stream URLs, remembered per song |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeClips.kt` | x-clip cover clips: real path from `/api/song/<id>`, `/rest/x-clip/<folder>/<stem>.cover.mp4` played as the canvas over the sleeve, Navidrome login (`/auth/login`) added by an interceptor, 7-day "no clip" notes (`bitchord_navidrome_clips` prefs). `Kind.BACKDROP` (`.background.mp4`) is there for the backdrop layer, not used yet |
| `app/src/test/java/com/music/bitchord/NavidromeClipsTest.kt` | Clip URL from a song path |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeHome.kt` | Album shelves (recently added / recent / most played / random) laid over the top of Home |
| `app/src/test/java/com/music/bitchord/NavidromeLyricsTest.kt` | Lyrics answer → player lines (offset, word stamps, plain text ignored) |
| `app/src/main/res/values/strings_fork.xml` | Fork strings |
| `app/src/main/java/com/music/bitchord/fork/ForkSettings.kt` | Fork switches (`bitchord_fork` prefs): crossfade on skip, Navidrome cover clips, backdrop clips, report plays to Navidrome |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeStars.kt` | The heart/star for Navidrome songs as the server's star (`star`/`unstar`), seeded from the songs' `starred` |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromePlaylists.kt` | Server playlists as pages (`local:group:playlist:navidrome:<id>`), reached from the Home "Playlists" shelf |
| `shared/src/commonMain/kotlin/com/music/bitchord/data/model/ForkSong.kt` | `NAVIDROME_ID_PREFIX`, `Song.isNavidrome` (readable from the shared UI) |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeScrobble.kt` | "Now playing" and finished plays sent to Navidrome (`scrobble`, submission false/true) |
| `app/src/main/java/com/music/bitchord/ui/screens/ForkSettingsRows.kt` | The fork switches as full settings rows, for the "Fork settings" group |
| `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ForkPausedCover.kt` | The cover drawn back over a full-screen clip while paused |
| `app/src/main/java/com/music/bitchord/fork/PlayerDecoderNames.kt` | The decoder each ExoPlayer last stood up, so a crossfade handoff can publish it (the Audio Pipeline showed "—" after every crossfade) |

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
| `.github/workflows/fork-sync-release.yml` (new) | Daily: merge upstream's latest release, fork tests, signed `assembleDevRelease`, certificate check, attestation, `fork-v<version>` release; on failure an issue + GitHub's failure email |
| `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` | Exclude `bitchord_navidrome.xml` and `bitchord_navidrome_plain.xml`, next to the `bitchord_sources` lines |

## Gotchas

- Navidrome songs carry `albumId` / `artistId` set to the library's own album and artist pages (`LocalGroups` ids). That is what keeps the player from asking YouTube for "links" about a song it has never heard of, and it makes the player's album and artist taps open the library pages. No upstream hook needed.

- Stream and cover URLs send Subsonic's `v` parameter as `%76`. The player treats any URI with a `v` query parameter as a YouTube watch link, so a literal `v=1.16.1` made every Navidrome song fail ("URL not accepted: https://www.youtube.com/watch?v=1.16.1"). Navidrome decodes the key and still reads it.
