package com.music.bitchord.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.canvas.ForkCanvasChoices
import com.music.bitchord.data.canvas.ForkMusicVideoMatch

/**
 * Fork: one video under "Pick another video" (canvas.md §15 rule 5), the same row on the phone and
 * the PC: its picture (YouTube's `mqdefault` still), title and who posted it; the one in use marked
 * ([inUse]). [accent]: the mark's colour, each app's own.
 */
@Composable
fun ForkVideoChoiceRow(video: ForkMusicVideoMatch.Video, inUse: Boolean, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = ForkCanvasChoices.thumbnail(video.id),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                video.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (inUse) FontWeight.SemiBold else null,
                color = if (inUse) accent else LocalContentColor.current,
            )
            val by = listOfNotNull(
                video.channel.takeIf { it.isNotBlank() },
                if (inUse) ForkCanvasChoices.VIDEO_IN_USE else null,
            ).joinToString(" · ")
            if (by.isNotEmpty()) {
                Text(by, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.7f))
            }
        }
    }
}
