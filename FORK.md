# Fork changes

Everything this fork adds lives in new files where possible. This file lists every place it touches an **upstream** file, so a conflicting upstream merge can be redone by hand. Fork lines in upstream files carry a `// fork` comment (or are in a block that starts with one).

## New files

| File | What |
|---|---|
| `docker/` | Docker build (`build.ps1`, `Dockerfile`); `docker/keys` holds the debug key (gitignored) |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeSettings.kt` | Server URL, username, password in their own encrypted prefs (`bitchord_navidrome`) |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeClient.kt` | Subsonic client: token auth, `search3` paging, raw stream and cover URLs |
| `app/src/main/java/com/music/bitchord/data/navidrome/NavidromeRepository.kt` | The library as `Song` rows (tags, per-song covers), cached in memory |
| `app/src/main/res/values/strings_fork.xml` | Fork strings |
| `app/src/main/java/com/music/bitchord/fork/ForkSettings.kt` | Fork playback switches (`bitchord_fork` prefs): crossfade on skip |

## Hooks in upstream files

| File | Hook |
|---|---|
| `BitChordApplication.kt` | `NavidromeSettings.init(this)` and `ForkSettings.init(this)` right after `AppSettings.init` |
| `playback/CrossfadeController.kt` | Crossfade on skip: `crossfadeSkip()`, `isSkipBlend()` and `skipTarget` after `onSkipRequested()` (which now clears `skipTarget`); `begin()` takes `targetIndex`; `bail()` carries out a skip that never got to blend; `finish()` clears `skipTarget`; `stillWorthFading()` lets a skip arm without a next item; the listener's seek / playlist-change callbacks clear `skipTarget` |
| `playback/PlaybackService.kt` | `adoptPlayer()`: a skip blend (`crossfade.isSkipBlend()`) is booked as `previousEnded = false`, reason SEEK, so it isn't scrobbled as finished or treated as "sleep after this song". Forwarding player: `seekToPrevious`, `seekToPreviousMediaItem`, `seekToNextMediaItem`, `seekToNext` try `crossfade.crossfadeSkip(…)` first; `forkPreviousTarget()` (Media3's 3 s rule) |
| `ui/screens/SettingsSheet.kt` | "Crossfade on skip" sub-row under the Crossfade slider (shown when the slider is above 0) |
| `ui/MainViewModel.kt` | `remoteLibrary()`: a `NavidromeSettings.BROWSE_ID` branch; in `init`, a collector after the WebDAV one that reloads the Navidrome page when its settings change |
| `ui/screens/LibraryPhoneParts.kt` | `libraryLinks()`: two `collectAsStateWithLifecycle` lines and the Navidrome link; `deviceFolderIcon()`: Navidrome icon |
| `ui/screens/SourcesScreen.kt` | `onEditNavidrome` parameter (defaults to `{}`) and a `RemoteLibrarySection` for Navidrome after the SMB one |
| `MainActivity.kt` | `showNavidromeEditor` state, `onEditNavidrome = …` passed to `SourcesScreen`, and the `ServerEditorHost` block before the SMB editor |
| `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` | Exclude `bitchord_navidrome.xml` and `bitchord_navidrome_plain.xml`, next to the `bitchord_sources` lines |

## Gotchas

- Stream and cover URLs send Subsonic's `v` parameter as `%76`. The player treats any URI with a `v` query parameter as a YouTube watch link, so a literal `v=1.16.1` made every Navidrome song fail ("URL not accepted: https://www.youtube.com/watch?v=1.16.1"). Navidrome decodes the key and still reads it.
