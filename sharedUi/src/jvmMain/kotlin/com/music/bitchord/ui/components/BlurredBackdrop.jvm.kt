package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable

// fork: the phone's blurred strip on the PC too (ForkBlurredBackdrop); was the cover as it is.
@Composable
actual fun rememberBlurredBackdrop(url: String?): Any? = forkRememberBlurredBackdrop(url)
