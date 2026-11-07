package com.music.bitchord.desktop

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Fork (owner, 2026-11-06): the heading of a Settings group. Upstream set it as a small grey
 * all-caps label (`labelSmall`), smaller than the rows under it, so the page read as one long list
 * and the groups (Account, Navidrome, Listen Together …) were hard to find. Now it is a real
 * heading: larger than the rows' titles, bold, in the text colour, with room above it.
 */
@Composable
internal fun ForkSettingsGroupTitle(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}
