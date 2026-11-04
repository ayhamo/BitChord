package com.music.bitchord.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.sharedui.resources.Res as SharedRes
import com.music.bitchord.sharedui.resources.ic_google_g
import com.music.bitchord.sharedui.resources.ic_logo
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.player.MESH_CYCLE_MILLIS
import com.music.bitchord.ui.player.MeshGradientBackground
import com.music.bitchord.ui.player.MeshPalette
import com.music.bitchord.ui.player.WelcomeMeshPalettes
import com.music.bitchord.ui.player.cyclingMeshPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.painterResource

/**
 * Fork: the phone's welcome pages (`ui/onboarding/OnboardingScreen.kt`) on the PC, shown once over
 * the window: a name for Listen Together, then the YouTube sign-in, skipped when an account is
 * already signed in. The phone has no permission pages in its welcome, so both pages carry over;
 * the wording, the mesh, the pills and the order are the phone's.
 *
 * Shown once, as on the phone, and for the same reason to a PC that already had the app: the flag
 * is new, and a PC user has no party name yet either. The flag ([KEY], the phone's
 * `onboarding_complete`) is this machine's, as the phone keeps its own out of a backup
 * (`AppSettings.DEVICE_LOCAL`): [ForkBackup] neither exports nor overwrites it. Importing a backup
 * counts as having been through it ([markDone]): the phone's file never carries its flag, and
 * whoever restores one already has a BitChord they set up.
 *
 * "Sign in with Google" opens the PC's own sign-in dialog, drawn over this layer, as the phone
 * raises its sign-in WebView over the welcome; the account landing is what ends it.
 */
internal object ForkWelcome {

    const val KEY = "onboarding_complete"

    private val _done = MutableStateFlow(runCatching { DesktopPersistence().boolean(KEY, false) }.getOrDefault(false))
    val done: StateFlow<Boolean> = _done

    fun markDone() {
        runCatching { DesktopPersistence().saveBoolean(KEY, true) }
        _done.value = true
    }

    /** The welcome layer; nothing once it has been through. Call last but the dialogs it opens. */
    @Composable
    fun Host(signedIn: Boolean, onSignIn: () -> Unit) {
        val finished by done.collectAsState()
        // Held through the slide away, as the phone's `onboardingShown`.
        val shown = remember { MutableTransitionState(!finished) }
        shown.targetState = !finished
        if (!shown.currentState && !shown.targetState) return
        AnimatedVisibility(
            visibleState = shown,
            enter = EnterTransition.None,
            exit = slideOutVertically(tween<IntOffset>(durationMillis = 560, easing = DismissEasing)) { it },
        ) {
            WelcomePages(
                signedIn = signedIn,
                initialName = remember {
                    DesktopListenTogether.nickname().ifBlank { DesktopAccounts.active()?.name.orEmpty() }
                },
                onNameChosen = DesktopListenTogether::setNickname,
                onSignIn = onSignIn,
                onFinish = ::markDone,
            )
        }
    }

    /** UIKit's sheet curve, the phone's `OnboardingDismissEasing`. */
    private val DismissEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
}

@Composable
private fun WelcomePages(
    signedIn: Boolean,
    initialName: String,
    onNameChosen: (String) -> Unit,
    onSignIn: () -> Unit,
    onFinish: () -> Unit,
) {
    var page by remember { mutableIntStateOf(PAGE_NAME) }
    var name by remember { mutableStateOf(initialName) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(signedIn, page) {
        if (signedIn && page == PAGE_CONNECT) onFinish()
    }

    val palette = cyclingMeshPalette(if (page == PAGE_NAME) WelcomeMeshPalettes else ConnectPalettes)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Takes every click the pages don't, so nothing reaches the window underneath.
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
            .onPreviewKeyEvent { event ->
                // Esc is the PC's Back.
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && page == PAGE_CONNECT) {
                    page = PAGE_NAME
                    true
                } else {
                    false
                }
            },
    ) {
        MeshGradientBackground(
            palette = palette,
            trackKey = page,
            continuous = true,
            driftMillis = 6_000,
            colorFadeMillis = MESH_CYCLE_MILLIS,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.05f),
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.45f),
                    ),
                ),
        )
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState > initialState
                val slide = tween<IntOffset>(420)
                (slideInHorizontally(slide) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(320, 100)))
                    .togetherWith(slideOutHorizontally(slide) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(200)))
            },
            label = "forkWelcomePage",
            modifier = Modifier.fillMaxSize(),
        ) { current ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.fillMaxSize().widthIn(max = 520.dp).padding(horizontal = 28.dp)) {
                    when (current) {
                        PAGE_NAME -> NamePage(
                            name = name,
                            onNameChange = { name = it.take(NAME_LIMIT) },
                            onNext = next@{
                                val chosen = name.trim()
                                if (chosen.isEmpty()) return@next
                                focusManager.clearFocus()
                                onNameChosen(chosen)
                                if (signedIn) onFinish() else page = PAGE_CONNECT
                            },
                        )
                        else -> ConnectPage(
                            onBack = { page = PAGE_NAME },
                            onSignIn = onSignIn,
                            onSkip = onFinish,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.NamePage(name: String, onNameChange: (String) -> Unit, onNext: () -> Unit) {
    Spacer(Modifier.height(56.dp))
    Image(
        painter = painterResource(SharedRes.drawable.ic_logo),
        contentDescription = null,
        modifier = Modifier.size(width = 46.dp, height = 30.dp),
    )
    Spacer(Modifier.height(24.dp))
    Title(DesktopStrings["onboarding_title", "Millions of songs, stream on BitChord"])
    Spacer(Modifier.weight(1f))
    Text(
        DesktopStrings["onboarding_name_prompt", "What should we call you?"],
        style = MaterialTheme.typography.titleMedium,
        fontSize = 17.sp,
        color = Color.White.copy(alpha = 0.78f),
        modifier = Modifier.padding(start = 4.dp),
    )
    Spacer(Modifier.height(12.dp))
    NameField(name, onNameChange, onNext)
    Spacer(Modifier.height(10.dp))
    Text(
        DesktopStrings["onboarding_name_footer", "Friends see this when you Listen Together. You can change it later in Settings."],
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.55f),
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(28.dp))
    PillButton(DesktopStrings["onboarding_next", "Next"], onNext, primary = true, enabled = name.isNotBlank())
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun ColumnScope.ConnectPage(onBack: () -> Unit, onSignIn: () -> Unit, onSkip: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            BitChordIcons.ChevronRight,
            DesktopStrings["onboarding_back", "Back"],
            tint = Color.White,
            modifier = Modifier.size(18.dp).graphicsLayer { scaleX = -1f },
        )
    }
    Spacer(Modifier.height(20.dp))
    Title(DesktopStrings["onboarding_connect_title", "Connect your YouTube account"])
    Spacer(Modifier.height(14.dp))
    Text(
        DesktopStrings[
            "onboarding_connect_body",
            "Bring your library, playlists and mixes with you, and start a Listen Together party with friends.",
        ],
        style = MaterialTheme.typography.bodyLarge,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        color = Color.White.copy(alpha = 0.72f),
    )
    Spacer(Modifier.weight(1f))
    PillButton(DesktopStrings["onboarding_sign_in_google", "Sign in with Google"], onSignIn, primary = true) {
        Image(painterResource(SharedRes.drawable.ic_google_g), null, Modifier.size(20.dp))
    }
    Spacer(Modifier.height(12.dp))
    PillButton(DesktopStrings["onboarding_skip_sign_in", "Skip sign in"], onSkip, primary = false)
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.displayLarge.copy(fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1.2).sp),
        color = Color.White,
    )
}

/** The phone's name field: the heading's face in a frosted capsule. Enter is Next, as the keyboard's Next is there. */
@Composable
private fun NameField(value: String, onValueChange: (String) -> Unit, onDone: () -> Unit) {
    val style = MaterialTheme.typography.displayLarge.copy(
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.6).sp,
        color = Color.White,
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(DesktopStrings["onboarding_name_hint", "Your name"], style = style, color = Color.White.copy(alpha = 0.38f), maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onDone() }),
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                    onDone()
                    true
                } else {
                    false
                }
            },
        )
    }
}

@Composable
private fun PillButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(CircleShape)
            .background(if (primary) Color.White else Color.White.copy(alpha = 0.16f))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontSize = 17.sp,
            fontWeight = FontWeight.W700,
            color = if (primary) Color.Black else Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

private const val PAGE_NAME = 0
private const val PAGE_CONNECT = 1

/** The phone's: long enough for a full name; the party server keeps the first 80. */
private const val NAME_LIMIT = 40

/** The phone's `ConnectPalettes`: the account page leans into YouTube's red. */
private val ConnectPalettes = listOf(
    MeshPalette(listOf(Color(0xFFFF1E3C), Color(0xFFC2185B), Color(0xFFFF8F3D), Color(0xFF4A1C8C))),
    MeshPalette(listOf(Color(0xFFE53935), Color(0xFFFF6E40), Color(0xFFAD1457), Color(0xFF311B92))),
    MeshPalette(listOf(Color(0xFFFF3D00), Color(0xFFD81B60), Color(0xFF8E24AA), Color(0xFFB71C1C))),
    MeshPalette(listOf(Color(0xFFF50057), Color(0xFFFF9100), Color(0xFFC51162), Color(0xFF4527A0))),
)
