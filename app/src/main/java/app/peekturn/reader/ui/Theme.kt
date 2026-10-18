package app.peekturn.reader.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object FocusColors {
    val Background = Color(0xFF171E23)
    val Surface = Color(0xFF20292F)
    val Text = Color(0xFFE9E8DF)
    val Muted = Color(0xFFA0AAAC)
    val Accent = Color(0xFFD9B783)
    val Border = Color(0xFF7B898E)
}
private val focusScheme = darkColorScheme(
    primary = FocusColors.Accent, onPrimary = FocusColors.Background,
    primaryContainer = Color(0xFF4A3D29), onPrimaryContainer = Color(0xFFF4DEBD),
    secondary = Color(0xFFCBB68F), onSecondary = Color(0xFF3A2F1D),
    secondaryContainer = Color(0xFF453926), onSecondaryContainer = Color(0xFFF4DEBD),
    tertiary = Color(0xFFC9BFA8), onTertiary = Color(0xFF332F21),
    tertiaryContainer = Color(0xFF413C2C), onTertiaryContainer = Color(0xFFEAE2CC),
    background = FocusColors.Background, onBackground = FocusColors.Text,
    surface = FocusColors.Surface, onSurface = FocusColors.Text,
    surfaceVariant = Color(0xFF29353B), onSurfaceVariant = FocusColors.Muted,
    surfaceTint = FocusColors.Accent, outline = FocusColors.Border, outlineVariant = Color(0xFF354147),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE4E3DA), inverseOnSurface = Color(0xFF2C332F), inversePrimary = Color(0xFF735731), scrim = Color.Black,
    surfaceBright = Color(0xFF344047), surfaceDim = FocusColors.Background,
    surfaceContainerLowest = Color(0xFF10171B), surfaceContainerLow = Color(0xFF1C252B),
    surfaceContainer = FocusColors.Surface, surfaceContainerHigh = Color(0xFF29343B), surfaceContainerHighest = Color(0xFF354047)
)

private val classicScheme = lightColorScheme(
    primary = Color(0xFF76521C), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF1DFBA), onPrimaryContainer = Color(0xFF372405),
    secondary = Color(0xFF6B5B3C), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEBDFC6), onSecondaryContainer = Color(0xFF2F2614),
    tertiary = Color(0xFF6B6349), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEEE6CE), onTertiaryContainer = Color(0xFF2F2A16),
    background = Color(0xFFF8F7F2), onBackground = Color(0xFF252923),
    surface = Color(0xFFFFFDF8), onSurface = Color(0xFF252923),
    surfaceVariant = Color(0xFFECE9DF), onSurfaceVariant = Color(0xFF595D53),
    surfaceTint = Color(0xFF76521C), outline = Color(0xFF797B70), outlineVariant = Color(0xFFD3D3C8),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF30352E), inverseOnSurface = Color(0xFFF1F1E7), inversePrimary = Color(0xFFE3C18A), scrim = Color.Black,
    surfaceBright = Color(0xFFFFFDF8), surfaceDim = Color(0xFFDEDED5),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF4F3EC),
    surfaceContainer = Color(0xFFEEEDE5), surfaceContainerHigh = Color(0xFFE8E7DF), surfaceContainerHighest = Color(0xFFE2E1D9)
)

/**
 * Midnight for AMOLED: the canvas is true #000000, so every elevated surface is a very low-chroma
 * warm step above black (surface 7, container 18, high 26) rather than a shadow. Body ink is a warm
 * off-white, 17:1 on the canvas and 16.5:1 on the elevated surface.
 */
private val oledScheme = darkColorScheme(
    primary = Color(0xFFE0C08B), onPrimary = Color(0xFF2C2008),
    primaryContainer = Color(0xFF4A3A1E), onPrimaryContainer = Color(0xFFF5E2BE),
    secondary = Color(0xFFCFBF9F), onSecondary = Color(0xFF2A2418),
    secondaryContainer = Color(0xFF3B3424), onSecondaryContainer = Color(0xFFEDE0C3),
    tertiary = Color(0xFFC9C3B4), onTertiary = Color(0xFF232118),
    tertiaryContainer = Color(0xFF343128), onTertiaryContainer = Color(0xFFE8E3D5),
    background = Color(0xFF000000), onBackground = Color(0xFFEDE7DB),
    surface = Color(0xFF070605), onSurface = Color(0xFFEDE7DB),
    surfaceVariant = Color(0xFF16130F), onSurfaceVariant = Color(0xFFB8B1A4),
    surfaceTint = Color(0xFFE0C08B), outline = Color(0xFF8A8579), outlineVariant = Color(0xFF2A2620),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFEDE7DB), inverseOnSurface = Color(0xFF1A1815), inversePrimary = Color(0xFF6B5326), scrim = Color.Black,
    surfaceBright = Color(0xFF2E2A24), surfaceDim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000), surfaceContainerLow = Color(0xFF0A0907),
    surfaceContainer = Color(0xFF12100E), surfaceContainerHigh = Color(0xFF1A1815),
    surfaceContainerHighest = Color(0xFF23201B)
)
/**
 * Sage is a green-grey paper, not a green theme: the canvas carries only a faint vegetal cast and
 * the ink stays a neutral near-black. The accent is an olive-gold, so it reads as a second paper
 * stock next to Classic White instead of a second identity.
 */
private val sageScheme = lightColorScheme(
    primary = Color(0xFF6B571D), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEFDFB2), onPrimaryContainer = Color(0xFF382D04),
    secondary = Color(0xFF5E6147), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3E6D3), onSecondaryContainer = Color(0xFF22260F),
    tertiary = Color(0xFF5C6257), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE2E8DC), onTertiaryContainer = Color(0xFF1D231C),
    background = Color(0xFFEDF0E7), onBackground = Color(0xFF222A23),
    surface = Color(0xFFF6F8EF), onSurface = Color(0xFF222A23),
    surfaceVariant = Color(0xFFE2E7DA), onSurfaceVariant = Color(0xFF4F594E),
    surfaceTint = Color(0xFF6B571D), outline = Color(0xFF777D72), outlineVariant = Color(0xFFC6CCBF),
    error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF2E342C), inverseOnSurface = Color(0xFFEFF2E7), inversePrimary = Color(0xFFDCC58A), scrim = Color.Black,
    surfaceBright = Color(0xFFFBFDF5), surfaceDim = Color(0xFFDDE2D5),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF1F4EB),
    surfaceContainer = Color(0xFFEBEFE4), surfaceContainerHigh = Color(0xFFE5EADF),
    surfaceContainerHighest = Color(0xFFDFE4D9)
)

private val warmScheme = lightColorScheme(
    primary = Color(0xFF735019), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEBD49F), onPrimaryContainer = Color(0xFF342302),
    secondary = Color(0xFF6A5730), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DCC0), onSecondaryContainer = Color(0xFF2F2614),
    tertiary = Color(0xFF6E5B47), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEFDFCB), onTertiaryContainer = Color(0xFF32200F),
    background = Color(0xFFF3E6BF), onBackground = Color(0xFF302B20),
    surface = Color(0xFFF9EDCD), onSurface = Color(0xFF302B20),
    surfaceVariant = Color(0xFFE9DAB2), onSurfaceVariant = Color(0xFF61563F),
    surfaceTint = Color(0xFF735019), outline = Color(0xFF807155), outlineVariant = Color(0xFFCEBD93),
    error = Color(0xFFA9221B), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF373024), inverseOnSurface = Color(0xFFF7EBCB), inversePrimary = Color(0xFFE3C18A), scrim = Color.Black,
    surfaceBright = Color(0xFFFFF2D5), surfaceDim = Color(0xFFDFD0A8),
    surfaceContainerLowest = Color(0xFFFFF6DE), surfaceContainerLow = Color(0xFFF6E9C7),
    surfaceContainer = Color(0xFFF0E2BD), surfaceContainerHigh = Color(0xFFEBDDB5),
    surfaceContainerHighest = Color(0xFFE5D6AE)
)

internal fun peekturnColorScheme(theme: String): ColorScheme = when (theme) {
    "light" -> classicScheme
    "warm" -> warmScheme
    "oled" -> oledScheme
    "sage" -> sageScheme
    else -> focusScheme
}

/** Themes whose canvas is dark; system bar icons and reader chrome invert against these. */
internal fun isDarkTheme(theme: String) = theme == "dark" || theme == "oled"

private val focusTypography = Typography(
    // Serif carries the editorial voice; display sizes take the negative tracking that keeps a
    // large face from looking stretched, and its small sizes stay loose for legibility.
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp),
    // The sans tiers are stated explicitly so control labels do not fall back to the framework's
    // default metrics and tracking, which read as stock Material next to the serif voice.
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.1.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp)
)
@Composable fun PeekturnTheme(theme: String = "dark", content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = peekturnColorScheme(theme), typography = focusTypography, content = content)
}
