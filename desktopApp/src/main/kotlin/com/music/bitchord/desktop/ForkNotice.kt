package com.music.bitchord.desktop

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fork: a short message over the window, the phone's toast. The desktop has nothing like it, so
 * something that quietly didn't happen ("Start radio" on a song the server has no similar songs
 * for) otherwise only shows in the log.
 */
internal object ForkNotice {

    private const val SHOWN_MS = 3_000L
    private val message = MutableStateFlow<String?>(null)

    fun show(text: String) {
        message.value = text
    }

    @Composable
    fun Host() {
        val text by message.collectAsState()
        val shown = text ?: return
        LaunchedEffect(shown) {
            delay(SHOWN_MS)
            message.value = null
        }
        Popup(alignment = Alignment.BottomCenter, offset = IntOffset(0, -120)) {
            val shape = RoundedCornerShape(10.dp)
            Text(
                shown,
                Modifier.clip(shape).desktopCard(shape).padding(horizontal = 16.dp, vertical = 10.dp),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
