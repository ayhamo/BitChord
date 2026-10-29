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
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeClips.kt` | x-clip video backdrops: real path from `/api/song/<id>`, `/rest/x-clip/<folder>/<stem>.background.mp4`, Navidrome login (`/auth/login`) added by an interceptor, 7-day "no clip" notes (`bitchord_navidrome_clips` prefs) |
| `app/src/test/java/com/music/bitchord/NavidromeClipsTest.kt` | Clip URL from a song path |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeHome.kt` | Album shelves (recently added / recent / most played / random) laid over the top of Home |
| `app/src/test/java/com/music/bitchord/NavidromeLyricsTest.kt` | Lyrics answer → player lines (offset, word stamps, plain text ignored) |
| `app/src/main/res/values/strings_fork.xml` | Fork strings |
| `app/src/main/java/com/music/bitchord/fork/ForkSettings.kt` | Fork switches (`bitchord_fork` prefs): crossfade on skip, animated covers |
| `app/src/main/java/com/music/bitchord/fork/AnimatedCoverDecoder.kt` | Coil decoder: animated WebP/GIF covers keep moving at 256 px and up (API 28+) |

## Hooks in upstream files

| File | Hook |
|---|---|
| `BitChordApplication.kt` | `NavidromeSettings.init(this)` and `ForkSettings.init(this)` right after `AppSettings.init` |
| `playback/CrossfadeController.kt` | Crossfade on skip: `crossfadeSkip()`, `isSkipBlend()` and `skipTarget` after `onSkipRequested()` (which now clears `skipTarget`); `begin()` takes `targetIndex`; `bail()` carries out a skip that never got to blend; `finish()` clears `skipTarget`; `stillWorthFading()` lets a skip arm without a next item; the listener's seek / playlist-change callbacks clear `skipTarget` |
| `playback/PlaybackService.kt` | `adoptPlayer()`: a skip blend (`crossfade.isSkipBlend()`) is booked as `previousEnded = false`, reason SEEK, so it isn't scrobbled as finished or treated as "sleep after this song". Forwarding player: `seekToPrevious`, `seekToPreviousMediaItem`, `seekToNextMediaItem`, `seekToNext` try `crossfade.crossfadeSkip(…)` first; `forkPreviousTarget()` (Media3's 3 s rule) |
| `ui/screens/SettingsSheet.kt` | "Crossfade on skip" sub-row under the Crossfade slider (shown when the slider is above 0) |
| `ui/MainViewModel.kt` | `remoteLibrary()`: a `NavidromeSettings.BROWSE_ID` branch; in `init`, a collector after the WebDAV one that reloads the Navidrome page and Home shelves when its settings change; `home` is `NavidromeHome.onTop(_home, …)`; `loadHome()` and `refreshHome()` each launch `NavidromeHome.load()` |
| `playback/AudioCache.kt` | `prefetchQueue()`: Navidrome ids filtered out of the YouTube read-ahead |
| `playback/Autoplay.kt` | `loadAutoplayTracks()`: a Navidrome seed takes `NavidromeRepository.related()` (similar songs, else random) instead of a YouTube radio |
| `ui/screens/LibraryPhoneParts.kt` | `libraryLinks()`: two `collectAsStateWithLifecycle` lines and the Navidrome link; `deviceFolderIcon()`: Navidrome icon |
| `ui/screens/SourcesScreen.kt` | `onEditNavidrome` parameter (defaults to `{}`) and a `RemoteLibrarySection` for Navidrome after the SMB one |
| `MainActivity.kt` | `showNavidromeEditor` state, `onEditNavidrome = …` passed to `SourcesScreen`, and the `ServerEditorHost` block before the SMB editor |
| `data/lyrics/EmbeddedLyrics.kt` | `forUri()`: first two lines hand a Navidrome stream URL to `NavidromeLyrics` (covers both the player screen and the service's lyric subtitle) |
| `shared/…/data/model/Models.kt` | `String.artworkAt()`: for a `/rest/getCoverArt` URL, `size=` is set to the asked px (`SUBSONIC_SIZE` regex next to `PARAM_SIZE_HINT`). Rows get small covers, the player 1200 px, which is what lets an animated WebP through. Test in `ModelsTest.subsonicCoverArtTakesTheAskedSize` |
| `shared/…/data/Http.kt` | `forkInterceptor` property (set by `NavidromeClips.init`) and one `.addInterceptor { … forkInterceptor … }` line after the WebDAV one |
| `data/canvas/CanvasRepository.kt` | `canvasFor()`: `NavidromeClips.canvasFor(song)` tried first, right after the blank-id guard; no clip falls through to the catalogue searches as before |
| `BitChordApplication.kt` (`newImageLoader`) | `add(AnimatedCoverDecoder.Factory())` first in `components {}` |
| `app/build.gradle.kts`, `tv/build.gradle.kts` | `coil-gif:3.0.4` next to `coil-svg` (tv compiles the app's sources, so it needs it too) |
| `ui/screens/SettingsSheet.kt` | "Animated covers" sub-row right after the upstream "Animated cover art" (canvas video) row |
| `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` | Exclude `bitchord_navidrome.xml` and `bitchord_navidrome_plain.xml`, next to the `bitchord_sources` lines |

## Gotchas

- Navidrome songs carry `albumId` / `artistId` set to the library's own album and artist pages (`LocalGroups` ids). That is what keeps the player from asking YouTube for "links" about a song it has never heard of, and it makes the player's album and artist taps open the library pages. No upstream hook needed.

- Stream and cover URLs send Subsonic's `v` parameter as `%76`. The player treats any URI with a `v` query parameter as a YouTube watch link, so a literal `v=1.16.1` made every Navidrome song fail ("URL not accepted: https://www.youtube.com/watch?v=1.16.1"). Navidrome decodes the key and still reads it.
