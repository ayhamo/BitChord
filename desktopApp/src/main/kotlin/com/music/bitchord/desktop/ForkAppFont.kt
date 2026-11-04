package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchord.desktopapp.generated.resources.Res
import bitchord.desktopapp.generated.resources.sf_pro_display_bold
import bitchord.desktopapp.generated.resources.sf_pro_display_medium
import bitchord.desktopapp.generated.resources.sf_pro_display_regular
import bitchord.desktopapp.generated.resources.sf_pro_display_semibold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.Font as composeFont

/**
 * Fork: the phone's "Font" choice (`AppSettings.appFont`, `ui/theme/Theme.kt`), on the PC.
 *
 * Both are SF Pro Display from the same five cuts, which the PC already bundles
 * (`composeResources/font`), so nothing new ships:
 *
 *  - [ForkFont.DEFAULT] draws each weight one cut lighter (Heavy draws Bold, Bold draws Semibold,
 *    Semibold Medium, Medium Regular) and opens the letter spacing, as the phone matched it to
 *    Apple Music's own screens;
 *  - [ForkFont.LEGACY] is the heavy, tight type the phone shipped with before that, which is
 *    exactly the PC's `desktopTypography()` (its weights and spacings are the phone's legacy
 *    scale), so LEGACY hands that back untouched.
 *
 * The default is the phone's, DEFAULT, so a PC that never chose looks like the phone; anyone who
 * preferred the PC's old type picks Legacy. The PC's sizes and weights stay its own; only the
 * family and the spacings the phone changed are swapped. Same key and names as the phone
 * (`app_font`, "DEFAULT"/"LEGACY") for the backup ([ForkBackup]).
 */
internal enum class ForkFont { DEFAULT, LEGACY }

internal object ForkAppFont {

    const val KEY = "app_font"

    private val _font = MutableStateFlow(read())
    val font: StateFlow<ForkFont> = _font

    private fun read(): ForkFont = runCatching {
        ForkFont.valueOf(DesktopPersistence().string(KEY, ForkFont.DEFAULT.name))
    }.getOrDefault(ForkFont.DEFAULT)

    fun set(value: ForkFont) {
        DesktopPersistence().saveString(KEY, value.name)
        _font.value = value
    }

    fun label(font: ForkFont): String = when (font) {
        ForkFont.DEFAULT -> DesktopStrings["font_default", "Default"]
        ForkFont.LEGACY -> DesktopStrings["font_legacy", "Legacy"]
    }

    fun description(font: ForkFont): String = when (font) {
        ForkFont.DEFAULT -> DesktopStrings["font_default_subtitle", "SF Pro Display · Apple Music weights"]
        ForkFont.LEGACY -> DesktopStrings["font_legacy_subtitle", "SF Pro Display · Original heavy weights"]
    }
}

/**
 * The window's typography under the chosen font: [pc] (the PC's own scale, the phone's legacy one)
 * for Legacy, or the same scale on the phone's lighter family and spacing for Default.
 */
@Composable
internal fun forkTypography(pc: Typography): Typography {
    val font by ForkAppFont.font.collectAsState()
    if (font == ForkFont.LEGACY) return pc
    // The phone's `SFProDisplay`: one cut lighter per weight; no lighter cut than Regular is bundled.
    val family = FontFamily(
        composeFont(Res.font.sf_pro_display_regular, FontWeight.W400),
        composeFont(Res.font.sf_pro_display_regular, FontWeight.W500),
        composeFont(Res.font.sf_pro_display_medium, FontWeight.W600),
        composeFont(Res.font.sf_pro_display_semibold, FontWeight.W700),
        composeFont(Res.font.sf_pro_display_bold, FontWeight.W800),
    )
    // Spacings from the phone's `BitChordTypography`, for the styles it sets; the rest take the family only.
    return pc.copy(
        displayLarge = pc.displayLarge.copy(fontFamily = family, letterSpacing = (-0.5).sp),
        displayMedium = pc.displayMedium.copy(fontFamily = family),
        displaySmall = pc.displaySmall.copy(fontFamily = family),
        headlineLarge = pc.headlineLarge.copy(fontFamily = family, letterSpacing = (-0.4).sp),
        headlineMedium = pc.headlineMedium.copy(fontFamily = family, letterSpacing = (-0.25).sp),
        headlineSmall = pc.headlineSmall.copy(fontFamily = family),
        titleLarge = pc.titleLarge.copy(fontFamily = family, letterSpacing = (-0.2).sp),
        titleMedium = pc.titleMedium.copy(fontFamily = family, letterSpacing = 0.05.sp),
        titleSmall = pc.titleSmall.copy(fontFamily = family),
        bodyLarge = pc.bodyLarge.copy(fontFamily = family, letterSpacing = 0.2.sp),
        bodyMedium = pc.bodyMedium.copy(fontFamily = family, letterSpacing = 0.3.sp),
        bodySmall = pc.bodySmall.copy(fontFamily = family),
        labelLarge = pc.labelLarge.copy(fontFamily = family),
        labelMedium = pc.labelMedium.copy(fontFamily = family, letterSpacing = 0.25.sp),
        labelSmall = pc.labelSmall.copy(fontFamily = family, letterSpacing = 0.3.sp),
    )
}

/** Appearance, before "Reduce animation" as on the phone: the two fonts as chips, the phone's footer under them. */
@Composable
internal fun ForkAppFontRow() {
    val font by ForkAppFont.font.collectAsState()
    val title = DesktopStrings["font", "Font"]
    val footer = DesktopStrings[
        "font_footer",
        "Legacy brings back the heavier, tighter type the app used before it was matched to Apple Music.",
    ]
    if (!settingsRowVisible(title, ForkAppFont.description(font))) return
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        Text(ForkAppFont.description(font), color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForkFont.entries.forEach { option ->
                FilterChip(
                    colors = desktopChipColors(),
                    selected = font == option,
                    onClick = { ForkAppFont.set(option) },
                    label = { Text(ForkAppFont.label(option)) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(footer, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
    }
}
