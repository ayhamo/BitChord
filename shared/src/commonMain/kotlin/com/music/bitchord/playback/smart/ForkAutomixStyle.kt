package com.music.bitchord.playback.smart

/**
 * Fork: the "Automix style" setting, on both apps. Exactly two:
 *
 * - [BITCHORD]: upstream's own planner ([planTransition]'s body, [LoopMix]), untouched.
 * - [V2]: "Automix v2", the YouTube Music extension's version-3 engine (`src/automix/automix.js`
 *   with `version === 3`) ported to Kotlin under `playback/smart/automixv2` and checked against the
 *   extension's golden plans (`ForkV2SpecFixturesTest`). It plans the blend
 *   ([com.music.bitchord.playback.smart.automixv2.ForkV2Automix]) and decides loop or blend
 *   ([com.music.bitchord.playback.smart.automixv2.ForkV2Loop]).
 *
 * [key] is what both apps store. Two older styles, "Web AutoDJ" (`original_autodj`, the extension's
 * version 1) and "Advanced AutoDJ" (`advanced_autodj`, its version 2), were removed on 2026-11-19
 * when v2 replaced them: Automix v2 is Advanced AutoDJ's planning and sound plus Orchard's rules,
 * so a setting or backup that names either one reads as [V2] ([fromKey], [migratedKey]) rather
 * than falling back to BitChord's planner, which would have changed how the owner's mixes sound.
 */
enum class AutomixStyle(val key: String, val label: String) {
    BITCHORD("bitchord", "BitChord"),
    V2("automix_v2", "Automix v2"),
    ;

    companion object {
        /** The removed styles' stored keys; both read as [V2]. */
        const val OLD_WEB_KEY = "original_autodj"
        const val OLD_ADVANCED_KEY = "advanced_autodj"

        /** A stored key as a style: the two removed keys are [V2], anything unknown (or none) is [BITCHORD]. */
        fun fromKey(key: String?): AutomixStyle = when (key) {
            V2.key, OLD_WEB_KEY, OLD_ADVANCED_KEY -> V2
            else -> BITCHORD
        }

        /**
         * A key as it should be stored from now on: a removed key becomes [V2]'s, a current one stays,
         * anything else is null (a backup carrying it is refused for that one setting, as before).
         */
        fun migratedKey(key: String?): String? = when (key) {
            OLD_WEB_KEY, OLD_ADVANCED_KEY -> V2.key
            BITCHORD.key, V2.key -> key
            else -> null
        }
    }
}
