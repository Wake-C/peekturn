package app.peekturn.reader.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A-direction chrome tokens: a neutral paper canvas, one blue accent, and hairline rules instead of
 * elevation. The exported names stay as they were; only the stock changes from warm gold to the
 * white / paper / sage / charcoal / OLED family.
 */
object FocusColors {
    val Background = Color(0xFF1D1E20)
    val Surface = Color(0xFF292B2E)
    val Text = Color(0xFFD5D6D3)
    val Muted = Color(0xFFA2A6A8)
    val Accent = Color(0xFF8AB1F5)
    val Border = Color(0xFF7A8087)
}

/**
 * Charcoal, the default dark theme. Canvas 1D1E20 and surface 292B2E are two quiet paper stocks;
 * ink D5D6D3 clears 11:1 on both, and the muted A2A6A8 still clears 4.5:1 on the soft fill.
 */
private val focusScheme = darkColorScheme(
    primary = Color(0xFF8AB1F5), onPrimary = Color(0xFF0B1220),
    primaryContainer = Color(0xFF293B54), onPrimaryContainer = Color(0xFFDBE7FF),
    secondary = Color(0xFFAAB9D6), onSecondary = Color(0xFF15202F),
    secondaryContainer = Color(0xFF2F3B4C), onSecondaryContainer = Color(0xFFDDE5F2),
    tertiary = Color(0xFFB7C3CF), onTertiary = Color(0xFF101820),
    tertiaryContainer = Color(0xFF333E49), onTertiaryContainer = Color(0xFFE2E8ED),
    background = Color(0xFF1D1E20), onBackground = Color(0xFFD5D6D3),
    surface = Color(0xFF292B2E), onSurface = Color(0xFFD5D6D3),
    surfaceVariant = Color(0xFF303337), onSurfaceVariant = Color(0xFFA2A6A8),
    surfaceTint = Color(0xFF8AB1F5), outline = Color(0xFF7A8087), outlineVariant = Color(0xFF3D4145),
    error = Color(0xFFEDAAA4), onError = Color(0xFF3F0505),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFD5D6D3), inverseOnSurface = Color(0xFF1D1E20), inversePrimary = Color(0xFF3267C8), scrim = Color.Black,
    surfaceBright = Color(0xFF3D4145), surfaceDim = Color(0xFF151618),
    surfaceContainerLowest = Color(0xFF151618), surfaceContainerLow = Color(0xFF202225),
    surfaceContainer = Color(0xFF292B2E), surfaceContainerHigh = Color(0xFF303337), surfaceContainerHighest = Color(0xFF3A3D42)
)

/**
 * Neutral white. The A reference's muted 71767B sits at 4.09:1 on the soft fill, so the text role
 * is darkened one step to 656A70; dividers keep the untouched E5E7E9 hairline.
 */
private val classicScheme = lightColorScheme(
    primary = Color(0xFF3267C8), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8EFFB), onPrimaryContainer = Color(0xFF0D2A52),
    secondary = Color(0xFF4F6178), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3E9F0), onSecondaryContainer = Color(0xFF1B2A3A),
    tertiary = Color(0xFF596572), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE7EBEF), onTertiaryContainer = Color(0xFF232B33),
    background = Color(0xFFFAFAFA), onBackground = Color(0xFF282C31),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF282C31),
    surfaceVariant = Color(0xFFF0F2F4), onSurfaceVariant = Color(0xFF656A70),
    surfaceTint = Color(0xFF3267C8), outline = Color(0xFF8A9096), outlineVariant = Color(0xFFE5E7E9),
    error = Color(0xFFB3423D), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCE8E6), onErrorContainer = Color(0xFF5F1512),
    inverseSurface = Color(0xFF282C31), inverseOnSurface = Color(0xFFFAFAFA), inversePrimary = Color(0xFF8AB1F5), scrim = Color.Black,
    surfaceBright = Color(0xFFFFFFFF), surfaceDim = Color(0xFFEBEDEF),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF5F6F7),
    surfaceContainer = Color(0xFFF0F2F4), surfaceContainerHigh = Color(0xFFEAECEF), surfaceContainerHighest = Color(0xFFE4E7EA)
)

/**
 * Warm paper. The canvas and sheet keep the reference stocks; the muted role is one step deeper so
 * 12sp metadata still clears 4.5:1 against both the sheet and the soft fill.
 */
private val warmScheme = lightColorScheme(
    primary = Color(0xFF3267C8), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4E7EC), onPrimaryContainer = Color(0xFF0D2A52),
    secondary = Color(0xFF6B5B45), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9DFCC), onSecondaryContainer = Color(0xFF33291B),
    tertiary = Color(0xFF6B604F), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEFE7D8), onTertiaryContainer = Color(0xFF30271A),
    background = Color(0xFFF5F0E5), onBackground = Color(0xFF38352F),
    surface = Color(0xFFFFFAF1), onSurface = Color(0xFF38352F),
    surfaceVariant = Color(0xFFEEE7DA), onSurfaceVariant = Color(0xFF6E675C),
    surfaceTint = Color(0xFF3267C8), outline = Color(0xFF8B8377), outlineVariant = Color(0xFFE0D9CD),
    error = Color(0xFFB3423D), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF8E3DE), onErrorContainer = Color(0xFF5C1612),
    inverseSurface = Color(0xFF38352F), inverseOnSurface = Color(0xFFFFFAF1), inversePrimary = Color(0xFF8AB1F5), scrim = Color.Black,
    surfaceBright = Color(0xFFFFFAF1), surfaceDim = Color(0xFFE7DFD0),
    surfaceContainerLowest = Color(0xFFFFFAF1), surfaceContainerLow = Color(0xFFF8F2E6),
    surfaceContainer = Color(0xFFEEE7DA), surfaceContainerHigh = Color(0xFFE7DFD0), surfaceContainerHighest = Color(0xFFDFD6C6)
)

/**
 * Sage paper. The vegetal cast lives only in the paper stocks; the accent remains the A blue, and
 * the muted role is deepened slightly so it clears the reference's DBE5D4 soft fill.
 */
private val sageScheme = lightColorScheme(
    primary = Color(0xFF3166C7), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE5ED), onPrimaryContainer = Color(0xFF0D2A52),
    secondary = Color(0xFF526049), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E6D5), onSecondaryContainer = Color(0xFF1F271B),
    tertiary = Color(0xFF596357), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE4E9DF), onTertiaryContainer = Color(0xFF1E241B),
    background = Color(0xFFE6EDDF), onBackground = Color(0xFF303A2E),
    surface = Color(0xFFF3F7EE), onSurface = Color(0xFF303A2E),
    surfaceVariant = Color(0xFFDBE5D4), onSurfaceVariant = Color(0xFF5B6756),
    surfaceTint = Color(0xFF3166C7), outline = Color(0xFF7B8678), outlineVariant = Color(0xFFCFDAC8),
    error = Color(0xFFB3423D), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF6E0DD), onErrorContainer = Color(0xFF5B1512),
    inverseSurface = Color(0xFF303A2E), inverseOnSurface = Color(0xFFF3F7EE), inversePrimary = Color(0xFF8AB1F5), scrim = Color.Black,
    surfaceBright = Color(0xFFF9FCF5), surfaceDim = Color(0xFFDCE5D6),
    surfaceContainerLowest = Color(0xFFF9FCF5), surfaceContainerLow = Color(0xFFEDF3E7),
    surfaceContainer = Color(0xFFDBE5D4), surfaceContainerHigh = Color(0xFFD3DECB), surfaceContainerHighest = Color(0xFFCBD7C3)
)

/** OLED: the A charcoal roles placed on a true black canvas, with the same surface stepping. */
private val oledScheme = darkColorScheme(
    primary = Color(0xFF8AB1F5), onPrimary = Color(0xFF0B1220),
    primaryContainer = Color(0xFF1D2B40), onPrimaryContainer = Color(0xFFDBE7FF),
    secondary = Color(0xFFAAB9D6), onSecondary = Color(0xFF0C1523),
    secondaryContainer = Color(0xFF243244), onSecondaryContainer = Color(0xFFDDE5F2),
    tertiary = Color(0xFFB7C3CF), onTertiary = Color(0xFF0C141B),
    tertiaryContainer = Color(0xFF29323B), onTertiaryContainer = Color(0xFFE2E8ED),
    background = Color(0xFF000000), onBackground = Color(0xFFD5D6D3),
    surface = Color(0xFF111214), onSurface = Color(0xFFD5D6D3),
    surfaceVariant = Color(0xFF1B1C1E), onSurfaceVariant = Color(0xFFA2A6A8),
    surfaceTint = Color(0xFF8AB1F5), outline = Color(0xFF70767C), outlineVariant = Color(0xFF2C2E30),
    error = Color(0xFFEDAAA4), onError = Color(0xFF3F0505),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFD5D6D3), inverseOnSurface = Color(0xFF1D1E20), inversePrimary = Color(0xFF3267C8), scrim = Color.Black,
    surfaceBright = Color(0xFF2A2D31), surfaceDim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000), surfaceContainerLow = Color(0xFF0A0B0C),
    surfaceContainer = Color(0xFF16181A), surfaceContainerHigh = Color(0xFF1E2022), surfaceContainerHighest = Color(0xFF26292C)
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

/**
 * A-direction type: screen headings and controls are UI sans, serif is reserved for book covers.
 * Tracking is tightened only at display sizes, and the small tiers keep the A reference's muted
 * hierarchy (11-12sp metadata, 14-15sp row titles, 18sp section titles).
 */
private val focusTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.1.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.15.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
)

/** A controls: 8-10dp fields, 16dp cards, 24dp pills. */
private val peekturnShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable fun PeekturnTheme(theme: String = "dark", content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = peekturnColorScheme(theme), typography = focusTypography, shapes = peekturnShapes, content = content)
}
