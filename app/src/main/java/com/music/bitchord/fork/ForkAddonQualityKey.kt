package com.music.bitchord.fork

import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver

/**
 * Fork: the addon quality pick is part of the audio cache's key (protocol.md 8.4.2, a6edfe9).
 *
 * The picker itself is upstream's (v1.9.1: `SourceConfig.qualityOptions` / `pickedQuality` /
 * `activeQuality`, the quality line under an addon's Sources row and its list in `MainActivity`): shown
 * for an addon whose manifest's `quality` select lists 2 or more values, greyed with its row when the
 * addon is off or passed over (never hidden), sent as the addon's `quality` parameter at the top tier
 * (`AddonClient.settingsFor`), the first option when the pick is no longer listed. A new pick rebuilds
 * the addon's client, so its answers are asked afresh.
 *
 * What upstream missed is the file: a YouTube song's addon copy is cached under `videoId#alt`
 * whichever pick fetched it, and a cached entry is played without a resolve, so a new pick changed
 * nothing for any song already cached. [suffix] adds the picks in force to that key: nothing while
 * every addon ranked above YouTube is on its first option (so entries cached before keep their key),
 * else `-q` and a short hash of the picks. Old entries stay on disk under their own key (the offline
 * fallback can still play a complete one) and age out of the cache's size limit.
 */
object ForkAddonQualityKey {

    /** Read as the key is built, like `canSubstituteForYouTube` beside it: the addons in force right now. */
    fun suffix(): String = of(
        SourceResolver.directAddons().mapNotNull { source ->
            SourceRegistry.config(source.configId)?.takeIf { it.kind == SourceKind.ADDON }?.let(::pickOf)
        },
    )

    /** The same for a picked addon row (`source|track`), which only its own addon serves. */
    fun rowSuffix(sourceId: String): String =
        of(listOfNotNull(SourceRegistry.config(sourceId)?.takeIf { it.kind == SourceKind.ADDON }?.let(::pickOf)))

    /** "id=value" for an addon whose pick in force isn't its first option, else null. */
    internal fun pickOf(config: SourceConfig): String? {
        val active = config.activeQuality ?: return null
        if (active == config.qualityOptions.firstOrNull()) return null
        return "${config.id}=${active.value}"
    }

    /** The key suffix for [picks] (in rank order): "" for none. */
    internal fun of(picks: List<String>): String =
        if (picks.isEmpty()) "" else "-q" + Integer.toHexString(picks.joinToString("\u0000").hashCode())
}
