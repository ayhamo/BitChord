# BitChord: addons and lyrics highlighting, as built

A behaviour spec read from the code at d4edeb47 (phone first, PC differences after), for comparison with the shared rulebook (YT Music Extension `spec/`). Every rule cites file:line. Nothing here is a proposal.

## A. Addons

This part describes how BitChord handles addon music sources, from the code as it stands at commit d4edeb47. All paths are relative to the repo root. "Fork" marks behaviour added by the owner's fork in `Fork*.kt` files or `// fork:` lines. Everything else is upstream BitChord.

Short path names used below:

| Short | Path |
|---|---|
| `AddonModels` | `app/src/main/java/com/music/bitchord/data/sources/addon/AddonModels.kt` |
| `AddonClient` | `app/src/main/java/com/music/bitchord/data/sources/addon/AddonClient.kt` |
| `SourceFormats` | `app/src/main/java/com/music/bitchord/data/sources/addon/SourceFormats.kt` |
| `ForkAddonLimitsPhone` | `app/src/main/java/com/music/bitchord/data/sources/addon/ForkAddonLimitsPhone.kt` |
| `AddonSource` | `app/src/main/java/com/music/bitchord/data/sources/AddonSource.kt` |
| `SourceRegistry` | `app/src/main/java/com/music/bitchord/data/sources/SourceRegistry.kt` |
| `SourceResolver` | `app/src/main/java/com/music/bitchord/data/sources/SourceResolver.kt` |
| `SourceKind` | `app/src/main/java/com/music/bitchord/data/sources/SourceKind.kt` |
| `ModuleSource` | `app/src/main/java/com/music/bitchord/data/sources/ModuleSource.kt` |
| `DeviceCodecs` | `app/src/main/java/com/music/bitchord/data/sources/DeviceCodecs.kt` |
| `StreamLengthProbe` | `app/src/main/java/com/music/bitchord/data/sources/StreamLengthProbe.kt` |
| `ForkAddonPickPhone` | `app/src/main/java/com/music/bitchord/data/sources/ForkAddonPickPhone.kt` |
| `AppSettings` | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` |
| `PlaybackService` | `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` |
| `QualityUpgrade` | `app/src/main/java/com/music/bitchord/playback/QualityUpgrade.kt` |
| `ForkUpgradeCover` | `app/src/main/java/com/music/bitchord/playback/ForkUpgradeCover.kt` |
| `DirectSourceGate` | `app/src/main/java/com/music/bitchord/playback/DirectSourceGate.kt` |
| `AudioCache` | `app/src/main/java/com/music/bitchord/playback/AudioCache.kt` |
| `ForkAddonPick` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/ForkAddonPick.kt` |
| `ForkAddonStream` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/ForkAddonStream.kt` |
| `TrackMatcher` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/TrackMatcher.kt` |
| `ForkAddonLimits` | `shared/src/commonMain/kotlin/com/music/bitchord/data/ForkAddonLimits.kt` |
| `ForkAddonUserAgents` | `shared/src/commonMain/kotlin/com/music/bitchord/data/ForkAddonUserAgents.kt` |

### A.1 Manifest and protocol

**Adding an addon (identify)**

1. A link ending in `.json` is fetched as the manifest first, then `base/manifest.json`. Any other link tries `url/manifest.json` first, then the link itself (`SourceFormats:78-122`).
2. Fork: a 403 on the default user agent is retried once as Firefox. If that works, Firefox is kept for that host (`SourceFormats:98`, `ForkAddonUserAgents:48-57`). The presets are BitChord (the default), Firefox and Edge, stored per host (`ForkAddonUserAgents:24-28`).
3. A JSON body is a module index when it has a `category:` key. It is a manifest when it has a `resources` array, or an `id` plus `name` or `version` (`SourceFormats:132-194`).
4. A manifest with non-empty `resources` that lacks `search` is refused (`SourceFormats:171-176`, `AddonClient:96-117`, "BitChord needs search"). Empty `resources` counts as playable (`AddonModels:122`).
5. If no manifest is found, BitChord tries `search?q=music` (`AddonClient:135-137`, `:603`). If that answers, it builds a manifest of `{id: base, name: host, resources: [search, stream]}` and labels the addon's health "No manifest · search works" (`SourceFormats:110-116`, `:203-207`, `AddonSource:163-213`).
6. The base URL is normalised by dropping a final `.json` segment (`AddonClient:536-544`). A duplicate is detected on the lowercased scheme and host, the port unless it is the default, the path as written and the query (`SourceRegistry:548-569`; fork made `canonicalUrl` internal, `:563`).
7. A blank `id` is refused. A manifest 404 reads "No manifest at that URL" (`AddonClient:96-117`).

**Manifest fields read** (`AddonModels:42-84`)

| Field | Use |
|---|---|
| `id`, `name`, `version` | Display name is `name`, else `id` (`:125`). Health shows "name vX" (`AddonSource:163-213`). |
| `resources` | Must include `search` when not empty (`:122`). |
| `settings` | Declared defaults are sent with every call (`AddonClient:236-274`). The `quality` select is shown as a picker only when it has 2 or more distinct options (`AddonModels:96-105`). |
| `allowDownloads` | Defaults to true (`:87`). When false, the addon is left out of downloads (`SourceRegistry:241-242`, `:262-271`). |
| `checkValidLossless` | The addon is skipped unless a lossless output is connected (`:89`, `AddonSource:78-82`, `SourceRegistry:79-80`). |
| `bitchordAuth` | Turns on upstream's signed verification (`:85`, `AddonClient:96-117`). |
| `limits` | Fork. A declared stream rate limit (`:83`, `AddonClient:109`). |

**Requests** (`AddonClient`)

1. JSON is parsed leniently (`:44-49`).
2. Every call sends the declared setting defaults first, then BitChord's own params (`:236-274`).
3. Headers are `Accept: application/json` and a User-Agent. Fork: the User-Agent is the per-host preset, else `BitChord/v…` (`:384-385`, `:595`). Auth headers are added when the addon uses `bitchordAuth`.
4. Answers are cached in memory: the manifest and search for 10 minutes, a stream for 5 minutes. Keys are length-prefixed and include the params (`:53-55`, `:497`, `:570-583`).
5. Timeouts: 45 s per call, 60 s read (`:623-628`).
6. Error mapping (`:374-432`):

| Answer | Result |
|---|---|
| 401 with `WWW-Authenticate: BitChord` | Re-authenticate and retry, at most twice (`:390-407`) |
| 404 | `AddonNotFound` (`:408`), not logged on `/stream` (`AddonSource:327`) |
| 429 | Retried up to `MAX_RETRIES` = 2, after `Retry-After`, else 500 ms << attempt, clamped to 0.5–8 s (`:418`, `:460-463`, `:585`, `:592-593`). Fork: the 429 also teaches the limiter a rate (`:420`). |
| ≥ 500 | `AddonUnavailable` (`:416`) |
| other 4xx | `AddonException` (`:417`) |

7. A 429 on `/stream` is retried like any other call. The rulebook gives up on a 429 instead.
8. Auth headers are sent only over HTTPS, or to loopback (`:440-447`).
9. Logged URLs keep only the scheme and host (`:558-559`).

**Params sent on `/search` and `/stream`** (`AddonClient:236-274`)

1. `quality`:
   - For a tier below LOSSLESS, fork `ForkAddonStream.lowerQuality` picks the value (`:249`, `ForkAddonStream:36-43`). It takes an option named as the tier first. Then it takes one whose value or label has the tier's words: LOW `low|96|128|min`, HIGH `high|320|normal|standard`. LOW then falls back to the high option, else the last listed. HIGH falls back to the first listed.
   - Upstream's `matchTier` sent the first option, which is usually the best, for LOW.
   - For LOSSLESS, BitChord sends the listener's picked option if it is still listed, else the first option. With no manifest, it sends the pick (`:250-251`).
   - When no options are listed, the tier name is sent (`:253`).
2. Stereo-only requests send the declared `atmos` setting's "off" option, else fork `"false"`. Otherwise the param is removed (`:266`).
3. When `DeviceCodecs.atmosWanted`, `atmosHint=auto` is added if absent (`:269-270`, `:308-311`).
4. Fork: when Atmos is not wanted, a declared `atmos` setting is sent `ForkAddonPick.atmosOff`. That is the first option reading 0/false/off/no/none/never/stereo, else `false` (`:271`, `ForkAddonPick:216-220`). Upstream sent the declared default, which on some addons is `true`.

**Auth.** An addon declaring `bitchordAuth` is left out while `AddonAuth.standingRefusal` holds (`AddonSource:97-111`, `SourceRegistry:222-235`). Per `ForkAddonAuthGate.kt:4-17`, the fork's build cannot pass that verification because it needs upstream's signing key, so such addons are in practice never used.

### A.2 Track matching

1. **Search.** `/search` is called with the song's queries in turn: "title artist", then the title alone, plus an optional romaji or other-script query (`TrackMatcher:85-103`). A blank query returns nothing. Up to 15 rows are kept per query (`SourceResolver:1116-1217`, `MATCH_CANDIDATES` `:1499`).
2. **Rows read** (`AddonModels:184-221`): `id`, `title`, `artist`, `album`, `duration` (seconds, cut to a whole number, `:222`), artwork, `format`, `audioQuality`, `audioMode(s)` and `streamURL`. Fork adds `infotext`/`cached` (`:212-216`), `isAtmos`/`isAtmosOnly` (`:218-219`) and `isrc` (`:220`). No `explicit` field is read, so the explicit rule never fires on addon rows.
3. A row needs a non-blank `id` and `title`. The addon's order is kept (`AddonSource:253-296`).
4. **Row flags.** A row is Atmos if a flag says so or a field matches `atmos|dolby|eac3[_-]?joc|ec-?3` (`AddonModels:247-250`, `:266`). It is Hi-Res on `hi[-_ ]?res|24[-_ ]?bit` (`:253`, `:257`). Its tier is DOLBY when Atmos, else `ModuleSource.qualityTier(audioQuality + format)` (`AddonSource:253-296`, `ModuleSource:536-545`). Fork: `tierSpelling` also reads "Hi Res", "16bit" and "24 bit" as lossless and a bare "96" as LOW (`ForkAddonPickPhone:96-102`, `ModuleSource:543`).
5. **Fork rulebook pick.** For addons, `SourceResolver.matchAndStream` calls `ForkAddonPickPhone.ranked` in place of `TrackMatcher.ranked` (`SourceResolver:1150`, `ForkAddonPickPhone:49-76`). Modules and JioSaavn keep upstream's matcher. `ForkAddonPick.rowsToOpen` (`ForkAddonPick:86-136`) then applies these rules:
   1. Read the first 15 rows with an id and title (`:97-99`).
   2. Refuse `atmos`: an Atmos row while Atmos is not wanted (`:106`).
   3. Refuse `preview`: a row of 35 s or less against a song over 45 s (`:101-102`, `:197-198`).
   4. Rows whose ISRC equals the song's go first (`:114-116`). An ISRC is 12 characters, upper case, with separators dropped (`:142-145`).
   5. Send the rest through `TrackMatcher.ranked`. A row it drops is refused as `not this song` (`:119-124`).
   6. Refuse `length`: |row − song| greater than the slack (`:126-130`). The slack is the caller's strict bar, else the listener's "Track length tolerance" (default 5 s, range 0–10, `AppSettings:202`). Downloads use 2 s (`:94`). An unknown length on either side refuses nothing (`:96`).
   7. Rows within 3 s go first, in a stable order (`:132-134`).
   8. Open at most 3 rows (`:135`).
   9. Refusals other than `not this song` are logged (`ForkAddonPickPhone:67-74`).
6. **ISRC-first during playback.** The song's ISRC comes only from the lyrics lookup's memory (`ForkAddonStream:139-140`, `LyricsRepository.kt:282`). `TrackMatcher.targetOf` sets it (`TrackMatcher:58-66`), and since 2026-11-20 so does `SourceResolver.targetIn(uri)`, which every playback path uses (`SourceResolver:183-191`, from the URI's `v`, the YouTube id; an addon row's own URI has none). So the rule applies once the song's lyrics were looked up. The race at the song's very start can run before that lookup ends; the upgrade pass after it has the ISRC.
7. **Matcher scores** (`TrackMatcher:209-293`):

| Part | Rule |
|---|---|
| Title | Equal core, romaji equal, translation, or containment when both cores are over 3 characters (`:221-227`). Otherwise no match. |
| Versions | Version-word sets must be equal: live, remix, acoustic, instrumental, karaoke, cover, edit, mix, sped, slowed, part and others (`:243-246`, `:949-966`). "Remaster" is noise, so it does not count as a version (`:1009-1015`). |
| Duration | ≤ 3 s +40, ≤ 30 s +15, else no match. A video sharing an artist may drift up to 90 s for 0. Unknown counts 0 (`:756-765`). |
| Artist | Exact +25, shared +10, no overlap null; 0 if either side is blank (`:707-714`). No overlap costs −30 only for a non-video within 2 s (`:286-289`). |
| Album | Exact +35 (`:776-780`). |
| Explicit | Both stated and equal +20, unequal no match (`:787-791`, `:290`). |
| Context | +20 (`:822-823`). |
| Total | 100 + the parts (`:291-292`). |

8. If any row shares an artist, rows without one are dropped. The rest are sorted by score with a stable sort (`TrackMatcher:184-200`).
9. Titles are NFKC-normalised and lowercased, with `&` read as "and" (`:563-568`). Latin text is folded to remove accents (`:580-590`). Brackets, dash or pipe tails, feat. credits and trailing takes are stripped (`:381-481`).

### A.3 Picking a row and tier

1. **Which addons.** An addon is active when it is enabled and complete. It is skipped while waiting for a lossless output, or while `bitchordAuth` is unverified (`SourceRegistry:222-235`). Addons are ordered by kind rank (ADDON 0, JioSaavn 2, YouTube 3, `SourceKind:72-82`, `:136`, `:153`), then by the user's list order (`SourceRegistry:448-457`).
2. **Ceiling.** Playback uses addons only when the effective quality is LOSSLESS. HIGH excludes kinds that can serve lossless, which includes every addon. MEDIUM and LOW allow YouTube only (`AppSettings:39-47`, `SourceRegistry:346-365`).
3. **Requested tier** (`SourceResolver:55-71`): LOSSLESS asks Lossless. Any other unlimited ceiling asks Best. Anything else asks Capped. Search always uses `request?.tier ?: LOSSLESS` (`AddonSource:253-296`). Tier names map as Lossless→LOSSLESS, Best→HIGH, Capped at 128 kbps or less→LOW, else HIGH (`AddonSource:482-488`, `ModuleSource:553`).
4. **Order among matched rows** (`SourceResolver.preferred`, `:1264-1298`): rows of the same length within `SAME_RECORDING_SEC` (3 s) come first. Then Atmos comes first if wanted. Then lossless with hi-res comes first.
5. **Opening rows** (`SourceResolver.streamBest`, `:1316-1379`):
   - Fork: rows marked cached are opened first (`ForkAddonLimitsPhone.cachedFirst`, `:1326`; `ForkAddonLimitsPhone:110-118`).
   - At most `STREAM_ATTEMPTS` = 3 opens (`:1509`).
   - A lossless, Atmos, or "states nothing lossy" answer is accepted at once.
   - Otherwise the best lossy answer is kept as a `belowRequest` settle-for.
6. **Comparing copies** (`SourceResolver.isBetter`, `:1426-1451`): Atmos beats lossless, which beats bit depth, then sample rate, then kbps.
7. **Across addons** (`bestAcross`, `:1010-1082`): addons are queried in a race. An equal-quality tie goes to the higher-ranked addon. The race stops at the first good answer unless the caller asked to wait for all.
8. **Picked addon rows.** A row the user played from an addon search is strict: no substitution and no YouTube fallback (`SourceResolver:132-146`). A blocked one shows `AddonBlockedAlert`. It names the quality setting when that is below Lossless, else the watch's temporary cap (`app/src/main/java/com/music/bitchord/ui/AddonBlockedAlert.kt:23-46`).
9. **Worth swapping** (`SourceResolver:872-883`): never swap away from Atmos. A lossless or Atmos copy is always worth it. A lossy copy needs a gain of 96 kbps or more (`:1556`).

### A.4 Resolving the stream

1. **Fork limiter, before the call** (`AddonClient:180-193`, `ForkAddonLimits:211-249`). The rules come from the per-addon preset (`ForkAddonLimits:71-92`):

| Preset | Limit |
|---|---|
| Automatic (default) | The manifest's `limits`, else a limit learned from a 429, else **none** |
| TorBox-backed | 5 a minute / 60 an hour |
| Gentle | 2 a minute / 30 an hour |
| No limits | none |

   - A learned limit is the calls sent minus 1, only when that is 2 or more, and it lasts 24 h (`:126-137`).
   - A row known to be uncached is skipped once the hour's allowance is spent. The skip ends the walk for that addon with no row fallback (`:211-249`, `AddonSource:326`).
   - Otherwise the call **waits** up to a minute for a slot. The rulebook skips instead.
   - A failed or uncached call is refunded (`:273-281`, `ForkAddonLimitsPhone:89-94`).
   - "Cached" is read from the flags first, else from text containing the marker but not "not cached" or "uncached" (`:98-105`).
2. **The `/stream` answer** (`AddonModels:287-329`) reads `url`, format, quality labels, codec, container, manifest or media type, `mimeType`, `encrypted`, `sampleRate`, `bitDepth`, `bitrate`, audio modes and `error`. There is **no `headers` field**, so addon-sent request headers are ignored.
3. **Failures** (`AddonSource:312-356`):
   - A 404 falls back silently to the search row's own `streamURL`.
   - Any other failure is logged, then falls back to the row's `streamURL`.
   - A blank `url` logs the addon's `error` text, then falls back to the row.
4. **Checks before opening, in order** (`AddonSource.openable`, `:398-437`):
   1. Malformed URL (`ModuleSource.malformed`).
   2. Fork: plain `http` is refused unless both the URL and the addon are on localhost or 127.0.0.1 (`:406`, `ForkAddonStream:100-104`). Upstream opened any URL.
   3. Encrypted: anything but false, "false", "none" or blank is refused (`AddonModels:351-358`).
   4. Atmos while not wanted is refused (`ModuleSource:500-501`, `AddonSource:416`).
   5. Transport is HLS or DASH from the first filled of manifest, mediaType or format. The URL extension is not read (`AddonModels:395-406`). Fork: `mimeType` is read too when nothing else named one (`AddonSource:430`, `ForkAddonStream:113-121`).
5. **Format of the answer** (`AddonSource.formatOf`, `:453-479`):
   - Codec: a stated codec if known, then the container (flac, wav, mp3, aiff), then the MIME subtype, then `eac3-joc` if Atmos, then `flac` if the quality text reads lossless, else the URL extension.
   - Sample rate: values under 1000 are kHz, else the kHz label (`AddonModels:418-424`). Bits come from an "N-bit" label in the range 8–32 (`:427-430`). Kbps over 3000 means bit/s, else the kbps label (`:440-442`).
   - Fork: when no bitrate is stated, it is inferred from the answer's own words: HIGH 320, LOW 128, lossless none, else the asked tier (`:471`, `ForkAddonStream:54-85`). The result is flagged `kbpsInferred`. Upstream used only the asked tier.
6. **File length probe (Direct path only)**:
   - A `Range: bytes=0-0` request reads the total size (`StreamLengthProbe:35-58`).
   - The copy is refused as cut short if its bytes imply less than the floor: 200 kbps for lossless, min(kbps, 64) for lossy (`:77-101`, timeout 4 s).
   - Only `cutShort` uses it, and only `directStream` sets `checkFileLength` (`SourceResolver:353-396`, `:1395-1411`).
7. **Fork hand-off.** `ForkHandoffPhone.preferredStream` is tried before the race in `substituteForYouTube` (`SourceResolver:291`). Each open is recorded with `noteOpened` (`:1350`).

### A.5 Playback

**Three ways an addon copy reaches the player**

| Path | When | What happens |
|---|---|---|
| Picked addon row (`bitchord://source…`) | User played an addon search row | `SourceResolver.resolve`: a lossless upgrade from addons ranked above, then the pinned addon, then other addons, never YouTube (`SourceResolver:197-261`, `PlaybackService:1418-1466`). Timeout 120 s (`PlaybackService:9638`). |
| YouTube song, normal mode (default) | `directSourceStream` off (`AppSettings:364`) | `substituteForYouTube` (20 s, `PlaybackService:9650`) races `StreamResolver.resolve` (`PlaybackService:5532-5672`). |
| YouTube song, Direct Source Stream | Setting on, not in a party (`DirectSourceGate:85`) | `resolveDirect` → `SourceResolver.directStream` with strict length, a shared artist required, slack drift and the file-length probe (`PlaybackService:5687-5713`, `SourceResolver:353-396`). Only the next track within 25 s is looked up, with a 15 s timeout (`DirectSourceGate:62`, `:75`, `:204`). |

**The race (normal mode)** (`PlaybackService:5532-5672`)

1. If the song refuses substitutes, it plays from YouTube.
2. An addon that wins with a non-`belowRequest` answer plays from the first note.
3. A YouTube win plays YouTube and calls `QualityUpgrade.settledForLess`, with the addon lookup still running (`QualityUpgrade:196-228`).
4. A manifest-type answer starts on YouTube and is handed over through QualityUpgrade.

**Upgrade (audition → swap)**

1. `QualityUpgrade.lookAgain` uses the in-flight answer if its length is within the slack and it is worth swapping. Otherwise it calls `SourceResolver.upgradeFor` (`QualityUpgrade:439-562`). `upgradeFor` waits for all addons, uses strict length and the slack drift, requires a shared artist, and excludes the addon that already served the song (`SourceResolver:513-577`).
2. Direct mode never swaps an addon answer except on a manual upgrade (`QualityUpgrade:279-300`). The manual "Upgrade" clears the addon call caches, with a cooldown (`PlaybackService:4701-4733`, `AddonClient:491-494`).
3. **Audition** (`PlaybackService:5154-5250`, `:5288-5349`, `:5371-5390`):
   - The first 1 MB is warmed (`:5193`, `:9757`).
   - A silent second player (volume 0, 40 s / 24 MB buffer) loads the copy at the current position for up to 25 s (`:9730`, `:9780-9782`).
   - The copy is rejected on a player error, an early end, or a length outside the slack.
   - Fork-free fake-FLAC check: a copy promised as lossless whose decoder MIME is not lossless is rejected (`:5316-5319`).
   - It must buffer to the current position + 12 s and load at least 1 MB (`:5327`, `:9745`, `:9760`).
4. **Swap** (`swapIn`, `PlaybackService:4856-5086`):
   - It needs 20 s or more left in the song (`:9656`).
   - It waits out any crossfade (polling every 250 ms, up to 20 s) plus a 5 s grace after a transition (`:9662-9688`).
   - Fork: `ForkUpgradeCover.raise` (`:4965`).
   - The item is replaced with the upgraded URI, which carries `EXTRA_QUALITY_UPGRADED` (`:5027-5034`), seeked to the same position (`:5039`) and prepared.
   - Fork: `cover.land` (`:5041`).
   - The head (4 MB) is warmed for analysis (`:5080-5083`, `:9768`).
5. **Watch** (`PlaybackService:5405-5483`): for 10 s after the swap, a duration off by more than the slack, or an early end, reverts to the previous URI at the same position.
6. **Substitute length check** (`PlaybackService:6585-6626`): a substituted copy that decodes more than slack + 1 s off is replaced by YouTube **from position 0**.
7. **Fallback** (`PlaybackService:4199-4269`): a failing substitute falls back to YouTube after a recovery delay, refusing further upgrades and substitutes for that song. Picked source rows are exempt.
8. **Window check** (fork, 2026-11-20, after this spec was read): before the swap, 12 s of the copy at YouTube's position is fitted against YouTube's Opus (`ForkWindowCheckPhone`, `ForkWindowFit`); no match is no swap, a match seeks the copy to the position plus its offset. No level fit: the copy gets YouTube's figure (Loudness below).
9. **Fork `ForkUpgradeCover`** (`ForkUpgradeCover:19-58`, `:340-375`, `:414-427`):
   - The standby player plays the old copy from cache, lined up on the players' clocks (tolerance 120 ms).
   - The session fades under it over 150 ms and takes the swap muted, then fades back in over 550 ms with equal-gain fades.
   - It stands down when there is no standby, the song changed, playback is paused, a party is on, bit-perfect output is on, the phone speaker is in use, a transition is running, the speed is easing, the copy is already upgraded, or a transition is within 20 s.
   - Any failure leaves upstream's hard cut.

**Transport and cache**

1. Audio is fetched by OkHttp through `ChunkedDataSource` in 1 MB chunks (`PlaybackService:1732-1752`, `:9598`). It goes through `AudioCache.playbackFactory` (a `CacheDataSource` with `FLAG_IGNORE_CACHE_ON_ERROR`, `AudioCache:1186`, `:1304-1310`) and the DSD extractors (`PlaybackService:1763-1768`).
2. Request headers come from `stream.headers`, which is empty for addons (`PlaybackService:1418-1466`). No user agent is set on the factory (`:1727-1731`), so the audio request carries no addon user agent.
3. An addon copy is cached under an `#alt` key (`AudioCache:1179`), with `-noatmos` when stereo was asked (`:601-618`). It is kept only when whole (`:620-640`).
4. Read-ahead prefetches only kinds that are worth prefetching. Addons are not (`SourceKind:136`, `AudioCache:1373`, `:1447`). An addon's `/stream` call therefore happens when ExoPlayer prepares the item, or in the Direct gate's 25 s window.
5. Load control buffers far ahead: up to 15 min / 8 MB, 500 ms to start, 2 s to resume, and a 30 s back buffer (`PlaybackService:6802-6812`, `:9607-9622`).

**Loudness**

1. The gain is YouTube's loudness for the media id, clamped to −15…+3 dB. It is off on the phone speaker (`PlaybackService:7448-7460`, `:9580-9581`; `StreamResolver.kt:298`).
2. An addon copy of a YouTube song therefore gets YouTube's figure.
3. A picked addon row has no YouTube id and plays at unity.
4. The phone applies no ReplayGain to addon copies.

### A.6 Gapless, crossfade and Automix

1. Crossfade runs two peer ExoPlayers. Manual skips cancel it (`app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt:61-72`, `:836`). Addon copies use the same players and data source chain as YouTube.
2. An upgrade never lands during a crossfade: `swapIn` waits it out, then 5 s more (`PlaybackService:9662-9688`). Fork `ForkUpgradeCover` stands down within 20 s of a transition (`ForkUpgradeCover:371`).
3. **Analysis is YouTube-only.** `TrackAnalyzer.chooseRendition` keeps only canonical YouTube renditions (`app/src/main/java/com/music/bitchord/playback/TrackAnalyzer.kt:785-830`, `AutomixAnalysisSource.kt:15-17`). Source rows are analysed from a matched YouTube Opus. Picked addon rows are not analysed (`TrackAnalyzer.kt:904-930`, `:910`).
4. **Loops** (`CrossfadeController.kt:2454-2612`):
   - A source-backed song is refused a loop as "analysed off another recording" (`:2463-2472`). `loopRegion` returns null for source URIs (`TrackAnalyzer.kt:1516-1524`).
   - Fork gates `ForkV2LoopGate` and `ForkMixPicks` apply too.
   - A YouTube song playing an addon copy is not source-backed, so its loop decks are cut from the YouTube Opus while the player plays the addon copy.
5. Automix v2 plans come from fork `ForkV2Loop.plan` (`CrossfadeController.kt:2510`). Plan times from the YouTube analysis are applied to the addon copy with no offset fit.

### A.7 Metered vs Wi-Fi, downloads, badges

**Network**

1. The ceiling is the cellular quality when metered, else the Wi-Fi quality. Both default to LOSSLESS (`AppSettings:198-199`, `:896-917`). The watch can cap it lower (`:893`).
2. Metered state comes from the `ConnectivityManager` callback (`AppSettings:1228`).
3. Because only LOSSLESS permits addons (A.3 rule 2), a lower cellular setting turns addons off on mobile data. Addon `quality` values below LOSSLESS (A.1) are reached only by downloads and Capped requests.
4. `ForkNetwork.kt` gates Navidrome only, not addons.

**Downloads** (`app/src/main/java/com/music/bitchord/data/Downloads.kt`)

1. Downloads are Wi-Fi only by default (`AppSettings:238`, `:927-928`). A song the cache can already serve skips the rule (`Downloads.kt:193`).
2. A YouTube song uses `TrackMatcher.targetOf`, so the ISRC rule can apply, then `SourceResolver.forDownload` with an 85 s budget (`Downloads.kt:1419-1446`, `:1610`).
3. A picked row uses `forDownloadOfRow` (`:1453-1475`, `SourceResolver:159-173`).
4. `forDownload` races a bit-exact walk against a lossy race. Lossless or Atmos wins. A lossy copy must beat 256 kbps (`SourceResolver:665-840`, `:856-857`). STANDARD quality returns null, meaning YouTube.
5. Rows are held to ±2 s (`ForkAddonPick:194`). Addons with `allowDownloads: false` are excluded (`SourceRegistry:241-242`).
6. The kept file's bytes are probed before it is accepted (`Downloads.kt:1497-1540`).

**Badges**

| Where | Rule |
|---|---|
| Search row | `SourceQualityBadge.kt:33-41` shows the row's `sourceQuality` and hi-res flag (`Common.kt:587`). |
| Player: Lossless / Hi-Res Lossless | Decoder MIME ends in flac, alac or raw, or is DSD. Hi-Res means more than 16-bit or more than 48 kHz (`NerdStats.kt:106`, `:121`, `:166`, `:178`). Shown with a shimmer (`PlayerControls.kt:1771`). |
| Player: Dolby Atmos | MIME ends in `eac3-joc` **or plain `eac3`** (`NerdStats.kt:191`, `PlayerControls.kt:1776`). |
| Player: High quality | Lossy at 256 kbps or more (`NerdStats.kt:142`, `:209`, `PlayerControls.kt:1785`). |
| Player: Upgrading quality | Pulses while the upgrade race runs and the copy is not lossless (`PlayerControls.kt:1757-1764`). |

Badges read the decoder output, not the addon's labels.

### A.8 PC differences (desktopApp/)

Paths are under `desktopApp/src/main/kotlin/com/music/bitchord/desktop/`.

1. The manifest reads `bitchordAuth` and `limits` too (`DesktopAddon.kt:51-52`). A `bitchordAuth` addon is refused for the whole run (`DesktopAddon.kt:297`, `ForkAddonAuthGate.kt:19-45`). A 401 BitChord challenge is refused, not retried (`DesktopAddon.kt:419-450`).
2. The client is the same apart from those points: same cache TTLs, same 404/5xx/429 handling, the fork user agent, and a 20 s connect timeout added (`DesktopAddon.kt:279-281`, `:419-450`, `:478-505`). Identify uses the same 403 Firefox retry (`:908`).
3. The quality value is chosen by `ForkAddonQuality.valueFor` (`:139-143`, `DesktopAddon.kt:366`). The Atmos param is `auto` when the PC has an E-AC-3 decoder and the setting is on (default on, `DesktopAddon.kt:235`), else `atmosOff` (`:354-388`).
4. Search always asks the LOSSLESS tier (`DesktopAddon.kt:669-704`). Tier names map LOW→LOW, MEDIUM/STANDARD/HIGH→HIGH, else LOSSLESS (`:836-840`). The row cache is cleared above 400 rows (`:578`, `:842-845`).
5. Matching uses the same `ForkAddonPick.rowsToOpen`, with the PC's length setting and `ForkAtmos.wanted` (`DesktopAddon.kt:711-720`). The PC target comes from `targetOf`, so the ISRC rule can fire.
6. `openable` refuses plain http and reads the transport and MIME the same way (`DesktopAddon.kt:789`, `:802`). Atmos is refused later, in `playableHere`, when there is no E-AC-3 decoder or the setting is off (`DesktopSourceRegistry.kt:634-646`).
7. The live race (`DesktopSourceRegistry.kt:331-412`) adds the fork's pins, hand-off and direct-source checks. It also chases Atmos as an upgrade (`:415-440`) and gives Atmos a grace period (`:443+`).
8. Upgrades (`DesktopAudioEngine.kt:428-520`):
   - A lossless upgrade gets 2 follow-ups.
   - `take` checks worth-swapping and the length.
   - The swap re-seeks when the playhead moved 250 ms or more (`:3523`, `:3526`).
9. Addon songs are not read ahead. The next addon song is armed 15 s before the end (`DesktopAudioEngine.kt:661-665`, `:2308-2315`, `:3576`). Fork `forkQueueSwap` crosses copies with a 200 ms equal-power fade, armed 1 s ahead (`:1592-1650`).
10. Loudness order on the PC is Navidrome ReplayGain, then YouTube's figure, then the file's ReplayGain/R128 tags, else unity, applied per deck (`ForkLoudness.kt`). The phone has no tag step.

### Open points

1. **Audio request headers.** The code sets no per-addon user agent and no addon headers on the audio fetch. The exact headers OkHttp sends (user agent, Accept-Encoding) depend on the shared `Http.client` interceptors, which this part did not trace.
2. **Official verification.** Whether any `bitchordAuth` addon can ever pass on this fork's build rests on the doc comment in `ForkAddonAuthGate.kt:4-17`. It was not run.
3. **Automix v2 on addon copies.** Beyond the loop gate, it is unclear how plan times cut from YouTube Opus line up on an addon copy with a different intro length. No offset correction was found.
4. **`manifest.limits` in the wild.** Whether real addons declare it is unknown. Without it the Automatic preset starts unlimited until a 429 arrives.
5. **ISRC memory size.** "Last 100 songs" comes from the `ForkAddonStream:128-133` doc. The cap was not checked in `LyricsRepository`.
6. **PC deck details.** HLS/DASH handling and the exact decoder path for addon copies on the PC were not traced.
7. **Stale comment.** `PlaybackService:5332` says "every two megabytes", but `STREAM_CHUNK_BYTES` is 1 MB (`:9598`). Which one the audition's load proof assumes was not settled.


## B. Lyrics highlighting

What BitChord does today with lyrics, from the code at `d4edeb47`. The rulebook (`YT Music Extension/spec/`) has no lyrics document (only `automix/`, `canvas/`, `addons/`), so this part uses its own terms. Rules are numbered B1, B2, … so the comparison can cite them.

### Path key

References are `KEY:line`. Each key stands for one repo-relative path.

| Key | Path |
|---|---|
| REPO | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsRepository.kt` |
| SRC | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsSource.kt` |
| HTTP | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsHttp.kt` |
| LINE | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricLine.kt` |
| GAPS | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricGaps.kt` |
| LRCLIB | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LrcLib.kt` |
| ELRC | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/EnhancedLrc.kt` |
| TTML | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/TtmlLyrics.kt` |
| RUNS | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/SyllableRuns.kt` |
| BG | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/BackgroundVocals.kt` |
| ALIGN | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricAlignments.kt` |
| PROV | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/ProviderLyrics.kt` |
| QUERY | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsQuery.kt` |
| TRANS | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsTranslation.kt` |
| `<Name>.kt` | other providers in the same `data/lyrics/` folder (e.g. `KuGou.kt`) |
| NAVI | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/navidrome/NavidromeLyrics.kt` |
| PL | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt` |
| CLOCK | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricClock.kt` |
| FOCUS | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricFocus.kt` |
| HOLD | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ForkLyricsHold.kt` |
| OSHEET | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsOffsetSheet.kt` |
| NPS | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt` |
| HOST | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerHost.kt` |
| POS | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/playback/PlaybackPosition.kt` |
| ONSCR | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/ForkLyricsOnScreen.kt` |
| VM | `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt` |
| SET | `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` |
| EMB | `app/src/main/java/com/music/bitchord/data/lyrics/EmbeddedLyrics.kt` |
| CONN | `app/src/main/java/com/music/bitchord/playback/PlayerConnection.kt` |
| SVC | `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` |
| DLC | `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopLyricsClient.kt` |
| NETEASE | `desktopApp/src/main/kotlin/com/music/bitchord/desktop/ForkNetEaseLyrics.kt` |
| UNFOC | `desktopApp/src/main/kotlin/com/music/bitchord/desktop/ForkLyricsUnfocused.kt` |
| DAPP | `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt` |
| DENG | `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioEngine.kt` |
| DHOST | `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPlayerHost.kt` |
| DMENU | `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSongMenuUi.kt` |

### Terms

- **Line-synced**: every line has a start stamp (`timeMs`), no word timing. A line may also carry its own end (`sungUntilMs`) (LINE:132-133, 149).
- **Word-synced**: the line has `words`, each with start and end, and optionally `syllables` inside a word (LINE:16-21, 157).
- **Plain**: all lines stamped 0. The panel treats a sheet as synced only when some line has `timeMs > 0` (PL:1807, PL:2808; `Unison.kt`:70-79).
- **Gap line**: a line with empty text, meaning an instrumental break (LINE:155).
- **Background line**: the answering vocal, kept as `background` under its lead line, with its own timing. It never nests (LINE:135-143).
- **Known end**: a line has a known end when it has words or `sungUntilMs` (LINE:262). Otherwise its end is its own start (LINE:273-277).

---

### B.1 Sources, order and choosing a result

#### Sources and default order

B1. The default order is the declaration order of `LyricsSource`. Hidden sources are left out (SRC:25-29, SRC:134). On a fresh install every offered source is enabled and in that order (SET:624, SET:634).

| # | Source (label) | Gives | Matches on | Ref |
|---|---|---|---|---|
| 1 | lrc.red | word/syllable (Apple TTML) | ISRC; strict title/version/artist/±3 s | SRC:43-47; `LrcRed.kt`:40, 100-119 |
| 2 | BiniLyrics | word/syllable (Apple TTML) | recording (ISRC) via its own search | SRC:48-52 |
| 3 | BetterLyrics | word/syllable (Apple TTML) | title, artist, duration (s), album | SRC:53-57; `BetterLyrics.kt`:46-57 |
| 4 | BetterLyrics Portato | word (QQ karaoke) | same, `/qq/` endpoint | SRC:58-62; `BetterLyrics.kt`:22 |
| 5 | PaxSenix | word (Apple) | scored candidates, min score 10 | SRC:63-67; `PaxSenix.kt`:24, 312-320 |
| 6 | PaxSenix: Spotify | line; needs API key | | SRC:68-72 |
| 7 | PaxSenix: Musixmatch | word; needs API key | | SRC:73-77 |
| 8 | LyricsPlus | syllable; six mirrors raced | title, artist, duration, album, ISRC | SRC:78-82; `LyricsPlus.kt`:33-40, 87-99 |
| 9 | Unison | TTML, LRC or plain (community) | song, artist, album, duration | SRC:89-93; `Unison.kt`:38-66 |
| 10 | YouTube captions | line (transcript cues) | exact video id | SRC:94-98; `YouTubeLyrics.kt`:38-51 |
| 11 | YouTube Music | plain (Lyrics tab) | exact video id | SRC:99-103; `YouTubeLyrics.kt`:14-34 |
| 12 | KuGou | line | hash search within ±8 s | SRC:110-114; `KuGou.kt`:24, 50-63 |
| 13 | LRCLIB | line | exact `get` with duration, then search nearest length | SRC:115-119; LRCLIB:33-74 |
| 14 | Musixmatch | rich sync (word) if present, else line | scored: title 80/40, artist 40, length +30/+15/+5/−20 | SRC:120-124; `Musixmatch.kt`:57-99 |
| 15 | Genius | plain, section headers | scored on title/artist/path | SRC:125-129; `Genius.kt`:175-203 |
| – | SimpMusic (hidden) | rich sync / LRC by video id, ±10 s | | SRC:83-88; `SimpMusicLyrics.kt`:27-49 |
| – | Megalobiz (hidden) | line | | SRC:104-109 |
| PC | NetEase (fork, PC only) | YRC word, else LRC, ±8 s | | NETEASE:23-66 |

B2. A hidden source is never asked, even if saved settings name it (REPO:107-109; SRC:17-23).

B3. Musixmatch is labelled line-only (`wordSynced = false`, SRC:120-124), but it returns word timings when the track has rich sync (`Musixmatch.kt`:57-64). The label and the behaviour disagree.

B4. A saved order is brought up to date on load. A source added in a newer build goes right after the source declared before it, or to the top if none (SRC:136-152; SET:642-648). A source enabled in no saved set but never shown before is switched on (SET:1601-1608).

#### Query cleaning

B5. The title loses credits ("feat.", "with") and upload packaging ("Official Video", "Lyrics", "4K"). Version words such as Remix, Live, Acoustic and Remastered stay (QUERY:14-33, 46-59). The artist loses a trailing " - Topic" (QUERY:41-42). This runs once for every source (REPO:114-115).

B6. LRCLIB also strips its own noise pattern and cuts at " | " (LRCLIB:158-163, 208-212).

#### Identifying the recording (ISRC)

B7. Before the race, one BiniLyrics search finds the recording's ISRC. It is capped at 2.5 s and skipped when BiniLyrics is disabled (REPO:117-126, 238, 248-263).

B8. A caller-supplied ISRC wins, then the in-memory map (100 entries, LRU, never on disk), then the search (REPO:120, 266-280). Fork: the remembered ISRC is also read by the addon matcher (REPO:282).

#### The race and the pick

B9. Every enabled source starts at once on the IO dispatcher. Genius is the exception: it starts lazily (REPO:129-157).

B10. Answers are taken **in priority order**: the loop awaits each source in turn, so a faster lower source never preempts a pending higher one (REPO:32-39, 159-171).

B11. A word-synced answer is returned at once (REPO:166).

B12. With "Prioritize syllable sync" **off** (default, SET:644), the first answer with any stamped line is returned, word-synced or not (REPO:167-169).

B13. With it **on**, a line-synced answer is kept only as a fallback. The search goes on for a word-synced one. If none comes, the first fallback is returned (REPO:79-86, 160-172).

B14. A plain (all-zero) answer never ends the search. It is kept as the fallback if nothing earlier was (REPO:167-170). So a timed answer from a lower source beats a plain answer from a higher one.

B15. Genius is skipped when any earlier source gave anything, plain included (REPO:162-163). In practice it answers only when every source ahead of it missed.

B16. When the loop ends, every unfinished source is cancelled (REPO:173-178). A document two sources both want (BiniLyrics' answer and lrc.red's URL) is fetched once per lookup (REPO:289-306).

B17. Every winning answer gets the background-vocal split (REPO:227-228; see B88).

#### Timeouts

| Item | Limit | Ref |
|---|---|---|
| ISRC identify | 2.5 s | REPO:238 |
| Most providers (shared `lyricsGet`) | call 6 s, connect 3 s | HTTP:17, 23-30 |
| PaxSenix authenticated routes | call 15 s, connect 10 s | HTTP:32-41 |
| Musixmatch own client | call 8 s, connect 4 s | `Musixmatch.kt`:42-47 |
| LRCLIB | uses the app-wide `Http.client`, not the 6 s lyrics client | LRCLIB:76-82 |
| Whole lookup | no overall cap; the slowest pending source ahead of the winner sets the wait | REPO:32-39 |

#### Per-source matching details

B18. lrc.red treats each test as a requirement: same core title, same version words, a shared artist, length within ±3 s. Among passes, the nearest length wins (`LrcRed.kt`:83-119).

B19. KuGou keeps only cuts within ±8 s, closest first (`KuGou.kt`:44-63).

B20. LRCLIB first asks `get` with title, artist and duration. On a miss it searches and takes the hit with synced lyrics nearest in length. There is no tolerance cap (LRCLIB:39-74).

B21. PaxSenix scores title (exact 20 / partial 10), artist (15/5) and length (<3 s +10, <10 s +5). When it gets several documents, it ranks them by score, then by distance between the last stamp and the duration, then by stamped lines, then by word-synced lines (`PaxSenix.kt`:153-176, 312-327).

B22. LyricsPlus races its six mirrors and takes the first **usable** answer. It remembers the winner for the next track (`LyricsPlus.kt`:42-77).

B23. Unison ignores its own vote and confidence fields (`Unison.kt`:19-23).

#### Embedded lyrics in local files, and caching (phone)

B24. A track with a `localUri` is read from the file first, with no duration needed (VM:432-445). In order: an HLS/DASH `lyrics.lrc` sidecar, then the app's `BITCHORD_LYRICS` field (A2 word stamps), then the container's standard lyrics field. FLAC, Matroska/WebM and MP4 are read (EMB:22-30, 36-45, 84-128).

B25. Embedded text goes through `LrcLib.parseLrc`, so unstamped plain lyrics in a file give nothing, and the lookup falls back to the network (EMB:79-80; LRCLIB:91-93).

B26. Embedded lyrics show no source name. The status reads "saved with download" (VM:439-442; PL:3274).

B27. Fork: a Navidrome stream URL is answered by `NavidromeLyrics`: a kept song's `.lrc`, then the disk cache, then the server. Only timed lyrics count (EMB:70-72; NAVI:21-39, 82-110). An online answer shown for a Navidrome song the server had none for is written to `cache/navidrome_lyrics` (64 in memory, 4000 files on disk) (VM:460; NAVI:44-47, 112-129, 150-157).

B28. Fork: the Navidrome (OpenSubsonic) offset is applied per source. It is "positive = sooner" and is subtracted from every start (NAVI:172-181). This is the only per-source offset in the app.

B29. For YouTube tracks there is no persistent lyrics cache. Each provider's hit for the **playing** track is kept in memory, so picking another provider from the drawer is instant (VM:351-352, 495-512). Settings changes re-run the lookup: the key is (video id, sources, order, prioritize flag) (VM:367-373, 398-401).

B30. A lookup waits for the duration, which the matching needs. Fork: if a new song has no duration yet, the previous song's lines are dropped instead of staying up (VM:402-409, 446-450, 468-489).

B31. Lyrics off, or no source ticked, means no lookup and no "no lyrics" message (VM:393-397, 423-429).

#### Settings and defaults (phone)

| Setting | Default | Ref |
|---|---|---|
| Synced lyrics on | true | SET:615-621 |
| Sources enabled | all offered | SET:624 |
| Source order | `LyricsSource.offered` | SET:634 |
| Prioritize syllable sync | false | SET:636-644 |
| Lyrics offset | 0 ms, range ±5000 | SET:521-522, 1051-1052, 2236-2237 |
| Blur unfocused lyrics | true | SET:518-519 |
| PaxSenix API key | empty | SET:1082 |

---

### B.2 Timing: what drives the highlight

#### Readings (phone)

B32. The UI polls `controller.currentPosition` every 500 ms while playing and while the app is in the foreground. It also reads on every player event, which covers seeks made while paused (CONN:343-345, 417-431).

B33. Each reading is stamped with `System.nanoTime()` when it is taken, not when the UI sees it (POS:32-43, 63-72).

B34. Every jump the player announces (`onPositionDiscontinuity`) bumps `seeks`. One case does not: an internal discontinuity that moves **forward**, which is the loop mix re-anchoring through its tempo ramp. Loop wraps go backward and do count (CONN:387-402).

#### The lyric clock

B35. The highlight runs on a per-frame clock (`withFrameNanos`) that the readings steer, not set (CLOCK:5-34; PL:691-762). It uses the vsync frame time when that time is within 250 ms of `System.nanoTime()`, and `nanoTime` otherwise (PL:744-750, 765).

B36. The clock carries the newest believed reading forward at a playback rate it learns. The rate is measured over 0.9–4 s of readings, clamped to 0.25–4×. Before then it takes a rough step from each pair at least 300 ms apart (CLOCK:236-255, 346-356).

B37. The words close the gap to that target with a 600 ms time constant. They run at most 25% faster and at most 50% slower, so they never stop and never go backward (CLOCK:262-296, 358-367).

B38. A reading off by more than 250 ms, plus slack per elapsed ms (0.2, or 0.75 before the rate settles), is held as suspect. A second reading that agrees with it is believed. After 2 refusals in a row, the next reading is taken whatever it says (CLOCK:156-206, 323-339).

B39. If the words are behind by 300 ms or more, they glide forward over 260 ms with smoothstep. A confirmed gap of 3 s or more either way is jumped (CLOCK:280-286, 369-379).

B40. Only an announced discontinuity may move the words backward. A jump within 2 s is rolled over 260 ms; a farther one is taken at once (CLOCK:20-25, 113-118, 158-165, 298-307, 375-376).

B41. Paused: every reading is applied outright, so a seek while paused moves the words (PL:715-725; CLOCK:93-102). The frame loop runs only while playing and in the foreground. Each start is a fresh run from the latest reading (PL:727-760; CLOCK:82-91).

B42. Fork: the foreground test goes through `forkLyricsOnScreen`. The phone is unchanged (null keeps the lifecycle gate). The PC may supply its own answer (ONSCR:6-33; PL:739; see B.5).

B43. The clock is created per track (`remember(trackKey)`, trackKey = video id). A track change therefore starts a new clock, not a jump (PL:710-713; NPS:2310, NPS:3873).

B44. A play state counts as moving only when `isPlaying && position.advancing`. `advancing` is false on the PC while a seek waits for audio. The phone never clears it (POS:54-61; NPS:2203, 2313).

#### Lead, lag and offsets

B45. The highlight has no built-in lead or lag. The **scroll** leads: see B66.

B46. There is one global user offset, ±5000 ms in 100 ms steps. Positive delays the lyrics (SET:521-522; HOST:300-301; OSHEET:70, 208-237, 274). The highlight position is `max(0, position − offset)` (PL:567-568, 670). A tap-to-seek target is `max(0, lineTime + offset)` (PL:570-571; NPS:921-923).

B47. Changing the offset counts as a discontinuity, so the words glide or jump to the new place and are not treated as bad readings (PL:674-680).

B48. There is no per-track offset and no per-provider offset, apart from Navidrome's server offset (B28). The LRC `[offset:]` tag is not read: the stamp regex accepts only `[mm:ss.xx]` (LRCLIB:206).

B49. Nothing in the lyrics code compensates for audio output latency, Bluetooth included. The phone uses `controller.currentPosition` as is (CONN:426). The PC reads frames the device has rendered (DENG:2070-2091).

#### Crossfade, loop mix and the notification

B50. In a crossfade the session moves to the other player at hand-over (SVC:852-858). The panel follows the session's current item, so lyrics switch to the incoming song at hand-over, on a new clock (B43).

B51. The media-session subtitle ("♪ line") is updated on its own 500 ms ticker. It uses `crossfade.loopPositionMs()` or `exoPlayer.currentPosition`, and it **ignores the user offset** (SVC:7999-8031).

B52. The service runs its own lookup with the same settings for the subtitle. A local file is tried first (SVC:7955-7997, 7342-7353).

---

### B.3 The fill: lines, words, syllables

#### Character progress

B53. Within a word, progress is linear in time. A word split into syllables fills each syllable's characters over that syllable's own time, and rests on the boundary in the pause between two syllables (LINE:22-44). A zero-length span is clamped to 1 ms (LINE:32, 38).

B54. Between two words, the space fills across the pause, so the edge keeps creeping and does not wait on the last letter (LINE:283-316).

B55. On screen, the edge crosses each timed piece (a syllable, or an unsplit word) at a **constant pixel rate** across its width. Spaces, and words broken over a wrap, go letter by letter (PL:1252-1315). Lines that wrap are handled one visual line at a time. Right-to-left text fills from the right (PL:1317-1413).

#### Drawing the sweep

B56. The sweep uses two copies of the line: a dim one, and a bright one clipped to what has been sung (PL:767-785, 890-904, 953-975). Dim alpha is 0.45 in the panel and 0.55 in the one-line strip (PL:211-212, 2601-2609, 2955).

B57. The edge is feathered with a 30 dp gradient **ahead** of the boundary, on the active line only. The feather grows in over the first 30 dp of each row (PL:302-310, 1380-1450, 2613).

B58. A line before its start draws dim. A line past its `endMs` draws fully lit (PL:893-903). Once the panel has passed a line, its dim copy animates up to 1, so the tail closes up as the line dims away (PL:2595-2604, 2261).

#### Lift ("rise")

B59. Each word lifts while it is sung. Peak lift is 2 dp × height. Height runs from 0.25 for words of 150 ms or less to 1.0 for words of 900 ms or more, with smoothstep (PL:312-319; LINE:245-251, 511-527).
- It rises over the word's own length, clamped to 250–700 ms, and settles 700 ms after the word ends (LINE:247-249, 505-509).
- Only the lead line rises. The strip, background lines and translation lines do not (PL:861-867, 2463, 2505, 2958).

#### Held words: letter-by-letter swell and glow

B60. A word "grows" (each letter swells, lifts and blooms in turn) when all of these hold (LINE:532-557, 589-598):
- it is at most 7 characters;
- it has no hyphen;
- it has no Han, kana, Hangul, Hebrew or Arabic letters;
- it is held long enough: 1 letter ≥ 1100 ms; 2–3 letters ≥ 1360 ms + 140 ms per letter over 2; 4 letters ≥ 1050 ms; 5–7 letters ≥ 900 ms and ≥ 200 ms per letter.

B61. Each letter starts 9% of the word's length after the previous one. The move lasts 1.5× the word's length: up by 25%, held to 30%, settled by 75%, with smoothstep (LINE:367-488, 608-623). The swell is about +4–5% plus up to +8%, earned on a cubed 400–3000 ms ramp and damped for long or barely qualifying words (LINE:401-437, 559-580, 625-638).

B62. Glow is only on growing words. It is a blurred third copy masked per letter: alpha up to 0.62, radius 6 dp. It fades in and out with the active line over 420 ms (PL:214-249, 920-951, 979-1010, 2282-2289). It is off when reduce animation is on, when reduce dynamic blur is on, when lyric blur is off, or when the platform cannot blur. There is no glow on background or translation lines (PL:1870-1871, 2459, 2497-2501).

#### Lines with no word timing

B63. A line-synced line lights **whole** the moment it becomes active, with no sweep. Before that it sits at the same 0.45 dim as unsung words (PL:2643-2665). Plain sheets are always lit (alpha 0.95), set at 30 sp instead of 34 sp, with no blur and no tap-to-seek (PL:2138, 2130, 2235-2249, 2356, 2374).

B64. Genius section headers (`[Chorus]`) on an unsynced sheet are drawn as small uppercase chips (PL:2081-2107; `LyricSections.kt`:3-10).

#### Active, past and future line styles (panel)

| Property | Active row | Others | Ref |
|---|---|---|---|
| Alpha | 1.0 | by distance: tablet/PC/TV centred `1, .8, .7, .58, .46`; phone `1, .74, .6, .48, .4, .34`; 0.8 while browsing | PL:354-373, 402-403, 2136-2147 |
| Blur | 0 | `0, 1, 1, 1.7, 2.4` dp by distance; none while browsing, with lyric blur off or reduce dynamic blur on | PL:363, 2128-2135 |
| Scale | 1.0 | 0.98; 0.96 while pressed (120 ms) | PL:405-409, 2270-2281 |
| Easing | cubic-bezier(0.41, 0, 0.12, 0.99), 400 ms, shared by dim, blur, scale and scroll | | PL:429-437 |

B65. Distance and brightness are measured from the line being **sung** (the scroll line), not from the lead-ahead focus line (PL:2114-2121).

#### Scrolling and centring

B66. The scroll anchor is the uppermost unfinished vocal (PL:1818-1820). The panel heads for the line due at `now + scrollLead`. The lead is the silence before the next line, clamped to 350–500 ms, and that same number is the scroll's duration. The sweep never leads (PL:425-427, 462-485, 1821-1841, 1999-2009).

B67. A target already on screen gets an animated scroll on the shared easing. Rows behind it trail by up to 3 steps × 6% of the duration (PL:439-460, 2261-2329). An off-screen target uses the list's own `animateScrollToItem` (PL:2011-2028). The first placement is instant (PL:1983-1993).

B68. Phone portrait puts the sung line at the top (40 dp padding). Wide layouts (`centerFocus`: TV, landscape, PC) centre it. Centring is used only for synced sheets (PL:1795-1800, 1943-1956, 2068-2077; NPS:2324).

B69. Fork: the first placement waits one frame, so a scroll issued during the list's own measure is not lost (PL:1975-1979).

B70. Following pauses while the user drags or picks lines. It resumes 600 ms after the active line is back on screen, or 5 s after the scroll stops (PL:1852-1856, 1873-1884, 1909-1923). Paused playback does not resume following (PL:1909-1912).

B71. Fork: following also pauses while a finger is down on any line, so a long press picks the line pressed. Blur, dimming and the sweep are not affected (HOLD:1-59; PL:1855, 1965-1966, 2268). Upstream kept scrolling during the press.

#### The one-line strip above the scrubber

B72. The strip shows the line at `indexOfLast(timeMs ≤ clock)`. Before the first sung line it shows a random intro phrase, chosen once per track. In a mid-song gap it shows "Instrumental" with an icon (PL:2853-2881).

B73. Word-synced lines sweep in the strip without rise. Others show as text (PL:2932-2976).

B74. Each strip line fades to 0 as its end nears: 28% of the line's slot, clamped to 160–700 ms, peak alpha 0.78. The next line slides up over 340 ms, or snaps with reduce animation (PL:2786-2789, 2908-2949). Background vocals are not shown in the strip (PL:2871-2876).

B75. An unsynced sheet shows "Open lyrics" in the strip (PL:2819-2850). After a failed lookup, "Lyrics not available" holds for 5 s, then fades over 900 ms (PL:497-498, 3007-3020).

---

### B.4 Seeks, pauses, gaps, bad timing, overlaps, voices, translation

#### Seeks and pauses

B76. A tap on a synced line seeks to that line, offset included (PL:2353-2378; NPS:921-923). A tap on a gap row seeks to the gap, except while picking (PL:2190-2193).

B77. For seeks, see B40. For pauses, see B41. The skeleton shows while a lookup is in flight, and "no lyrics for this track" once it comes back empty (PL:2033-2049).

#### Instrumental gaps

B78. Gap lines come from two places:
- **LRC bare stamps**: kept only when the next line is at least 4000 ms later, or as a trailing outro. An intro gap at 0 is added when the first line is at 4000 ms or later (LRCLIB:84-135; GAPS:4).
- **Measured silence** (TTML, LyricsPlus, Musixmatch rich sync, A2 LRC, QRC/YRC, PaxSenix): a gap is added at a line's `endMs` only if that line has a known end, the silence to the next line is at least 4000 ms, and the end is after the start. Intro rule as above (GAPS:6-34; TTML:81; `LyricsPlus.kt`:142; `Musixmatch.kt`:190; ELRC:61; PROV:116; `PaxSenix.kt`:249).

B79. A line with no known end never produces a measured gap. The distance to the next stamp is not treated as silence (LINE:253-262; GAPS:11-16).

B80. In the panel, a gap is 3 dots (13 dp, 5 dp apart). Each dot fills its third of the span from the gap's stamp to the next line's stamp, from alpha 0.25 to 1 (PL:411-423, 2148-2231). The row opens (40 + 16 dp, scale 0.76→1) only while the gap is active: 400 ms open, 350 ms close. Otherwise it takes no space (PL:342-352, 2167-2185, 2201-2207).

#### Missing or bad timing

B81. A word's end is never before its start (RUNS:43, 64; `LyricSyllable.normalized` LINE:96-109).

B82. In A2/enhanced LRC each word runs until the next word starts. The last word of a line runs until the next line starts. The last word of the song gets 800 ms (ELRC:41-53, 94).

B83. A line's `timeMs` is the earlier of the line stamp and the first word, so a lead-in shows the line early (TTML:170-176; ELRC:57; `LyricsPlus.kt`:113; PROV:110).

B84. Lines are sorted by time on parse (LRCLIB:118; ELRC:30; TTML:77). Fork: a compressed LRC line `[a][b]text` becomes one line at each stamp (LRCLIB:94-96, 99-117).

B85. Two-digit fractions are centiseconds; three digits are milliseconds (LRCLIB:101-106).

B86. HTML entities in rich sync are decoded (ELRC:75-92). NetEase credit lines are cut on the PC (NETEASE:38-40).

#### Overlapping lines

B87. Every earlier line that has a known end and has not finished stays active beside the latest started line. Several rows can sweep at once, and the uppermost owns the scroll (FOCUS:6-15; PL:1813-1820).

#### Background vocals

B88. TTML spans with `ttm:role="x-bg"` become the background line (TTML:38-44, 143-154, 191-216). Every other source has its trailing `( … )` split off. A word-synced line keeps the bracket's own word timings. A line-synced line shares the lead's stamp. A bracket opening mid-word, or a fully bracketed line, is left alone (BG:1-105).

B89. In the panel, the background line is drawn under the lead at 23 sp, alpha 0.72, without parentheses (the data keeps them) (PL:251-260, 2480-2513, 2763-2781).

B90. A word-synced background line opens 250 ms before its first word, over 400 ms, and closes at its end over 450 ms. A row the panel has already left waits for the scroll to land before it closes. A background line without timing opens with the lead (PL:262-286, 2389-2431, 2743-2758).

#### Duets

B91. TTML and LyricsPlus voices are placed on alternating sides whenever the voice changes. A group stays left. The whole song flips if 85% or more of it ended up on the right (ALIGN:12-73; TTML:79-80; `LyricsPlus.kt`:130-141).

B92. End-side lines are right-aligned. Each side keeps a 44 dp lane clear, and does so only when the song has an End line (PL:332-340, 1808-1811, 2234, 2295-2305). A2 files written by the app mark the right side with `<R>` (LRCLIB:137-152).

#### Translation and romanisation

B93. Apple's own in-document translations and romanisations (`x-translation`, `x-roman`) are dropped (TTML:32-36, 197).

B94. Translate and romanise are on-demand buttons using Google endpoints. Results are batched and kept in a bounded disk cache: version 3, 2 MB, 3500 characters per batch, 2 requests in parallel (TRANS:36-44, 68-77). The target is the Settings language, or else the app language (PL:3082-3097).

B95. Only one mode shows at a time. The panel keeps the originals and draws the translation or romanisation under each line: 20 sp, alpha 0.85, opening over 460 ms and closing over 260 ms. The strip swaps to the translated text (PL:3130-3145, 288-300, 2383-2479, 2685-2729). A sub-line identical to the original is not drawn (PL:2448-2450, 2760-2761).

B96. A translated line has no word timing of its own. Its sweep is projected from the original: `revealedChars(original) / original length × translated length` (LINE:152-153, 288-293; TRANS:509-547). Sub-lines do not rise and have no glow (PL:2459-2463).

---

### B.5 PC differences (desktopApp)

B97. The PC uses the same shared UI (`LyricsPanel`, `CurrentLyricStrip`, `LyricClock`) and the same `LyricsRepository` race (NPS:2199-2207, 2307-2326; DLC:18-25, 100-111).

B98. **Readings**: the PC engine reports position from frames the sound device has rendered. It is stamped on the audio thread and published every 250 ms bucket (DENG:2070-2105; DAPP:1906-1917). During a blend, after the display switch, it reports the incoming track's heard position (DENG:2074-2087). A seek bumps `seeks` and sets `awaitingAudio`, which stops the clock until audio arrives (DENG:3096; DAPP:1915; POS:54-61).

B99. **Lookup order** (DLC:65-123):
1. Fork: Navidrome server lyrics.
2. The local file's embedded lyrics (label "Downloaded").
3. The shared race.

Fork: MP3 and Ogg tags are also read (FORK.md row for `DesktopEmbeddedLyrics.kt`).

B100. **Cache**: results are kept in memory for 12 h. The key is video id, duration, title, artist and picked provider. Failures are cached too (DLC:27, 52-54, 85-90, 121).

B101. Fork: NetEase is a PC-only source. It sits by default after LyricsPlus and is not raced: it is asked after the shared race, only when it sits ahead of the winner, or to upgrade a line-synced winner when prioritize is on (NETEASE:22-122; DLC:46, 77-118). It is not in the player's provider picker (NETEASE:45-46).

B102. Fork: "Keep lyrics moving when unfocused" is on by default. With it on, the clock runs while the window is shown and not minimized. With it off, the clock stops when the window loses focus, as upstream did (UNFOC:10-56; ONSCR:6-33).

B103. **Offset**: the same ±5000 ms range and 100 ms step, set from the song menu's "Lyrics offset" stepper and stored in desktop persistence (DHOST:207-209, 251-255; DMENU:554-620).

B104. **Layout**: the PC's wide player calls `LyricsPanel` with `centerFocus = true`, so it uses the centred falloff table and a centred active line (NPS:2307-2326; PL:2126).

---

### Open points

1. Phone output latency: I did not check whether `MediaController.currentPosition` already accounts for AudioTrack or Bluetooth latency. The lyrics code adds nothing (B49).
2. Phone quality-upgrade swap (same video id, new stream): I could not tell whether it raises `onPositionDiscontinuity` or keeps the same `trackKey`. So it is unclear whether the lyric clock jumps, glides or absorbs the change.
3. Phone crossfade: I did not trace whether the session hand-over itself counts as a discontinuity. The new `trackKey` makes this mostly moot.
4. The service subtitle ignores the user offset (B51). This looks unintended, but nothing in the code says so.
5. LRCLIB's timeout is the app-wide `Http.client`, whose values I did not read (B20, timeout table).
6. Provider internals I only skimmed: BiniLyrics identify/search, Genius scrape, Megalobiz, PaxSenix Spotify/Musixmatch routes, and KuGou's lyric-candidate pick after the song hash.
7. The translation sweep projection (B96) is proportional by character count. I did not check how it behaves for RTL or CJK translations.
8. `reduceAnimation` turns off the glow and snaps the reveals. I found no switch that turns off the sweep, rise or letter swell.
9. The desktop `lyricsBlur` is `MutableStateFlow(true)` in `DesktopPlayerHost` (DHOST:206). I did not trace whether the PC Settings row feeds it.
