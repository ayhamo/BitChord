package com.music.bitchord.playback.smart.automixv2

import java.io.File

/**
 * The spec's ready-made mix file named [stem] (spec/automix/mixes/), whatever its `.json` name ends in: mix files are
 * plain `.json`, decided by their content (mix-file.md rules 1 and 8, the user 2026-11-26), and the spec's copies may be
 * renamed. Throws when there is none.
 */
fun mixFixture(spec: File, stem: String): File =
    File(spec, "automix/mixes").listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith("$stem.") && it.name.endsWith(".json", ignoreCase = true) }
        .minByOrNull { it.name.length } ?: error("no $stem mix file in $spec/automix/mixes")
