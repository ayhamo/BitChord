package com.music.bitchord.data

/**
 * Fork (owner, 2026-11-19): the small badge on every settings row the fork adds, so a setting that
 * upstream BitChord does not have can be told apart at a glance, on the phone and the PC alike.
 *
 * One constant for both apps, kept here in `shared` because that is the one module the phone (`app`)
 * and the PC (`desktopApp`) both read: the phone passes it as `SettingsRow(badge = …)`, upstream's
 * own badge slot (the "In use" / "Connected" pill), and the PC's `ForkSettingsMark` draws it in the
 * same pill. Both draw it in capitals, as upstream's badges are, so the text is kept in its natural
 * case here.
 *
 * Which rows carry it: a setting that upstream has on neither app. A PC row that ports one of
 * upstream's phone settings (Loudness, Font, Reduce animation, Your data, Content preferences …)
 * is upstream's setting and is left unmarked, so the same setting reads the same on both.
 */
object ForkSettingsBadge {
    const val TEXT = "Fork"
}
