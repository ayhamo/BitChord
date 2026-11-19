package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.ForkSettingsBadge
import java.util.Locale

/**
 * Fork (owner, 2026-11-19): the "Fork" mark on every settings row the fork adds, the PC's twin of the
 * phone's `SettingsRow(badge = ForkSettingsBadge.TEXT)`. Drawn as the phone draws upstream's badges
 * ("In use", "Connected"): the text in capitals, small, in the accent colour on a faint accent pill,
 * after the row's title. The text is [ForkSettingsBadge.TEXT], shared with the phone.
 *
 * Which rows: a setting upstream BitChord has on neither app (see [ForkSettingsBadge]). A PC row that
 * ports one of upstream's phone settings (Loudness, Font, Reduce animation, Your data …) is not marked.
 */
@Composable
internal fun ForkSettingsMark(modifier: Modifier = Modifier) {
    Text(
        ForkSettingsBadge.TEXT.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelSmall,
        color = DesktopAccent,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(DesktopAccent.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Whether the settings rows drawn inside are the fork's ([ForkMarked]). A composition local rather
 * than a parameter on upstream's `SettingsToggle` / `SettingsRow`: those are called from some forty
 * places, upstream's and the fork's, and a new parameter would have to be threaded through every
 * fork call site and every row that wraps them. With the local the upstream primitives carry one
 * hook line each (their title is drawn by [ForkSettingsTitle]) and a fork composable marks all of
 * its rows by wrapping them once.
 */
internal val LocalForkSettingsRow = compositionLocalOf { false }

/** Draws [content]'s settings rows with the [ForkSettingsMark] after their titles. */
@Composable
internal fun ForkMarked(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalForkSettingsRow provides true, content = content)
}

/**
 * A settings row's title, followed by the [ForkSettingsMark] on a fork row ([fork], by default
 * whether the row is inside [ForkMarked]). Upstream's `SettingsToggle` and `SettingsRow` draw their
 * title with it; the fork's own hand-built rows (chips, radio lists) call it with their own style.
 * The title gives way (ellipsis) before the mark does, so a long title never pushes it off the row.
 */
@Composable
internal fun ForkSettingsTitle(
    title: String,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    fontWeight: FontWeight? = null,
    fork: Boolean = LocalForkSettingsRow.current,
) {
    if (!fork) {
        Text(title, style = style, fontWeight = fontWeight)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = style,
            fontWeight = fontWeight,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        ForkSettingsMark()
    }
}
