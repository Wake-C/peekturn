package app.margin.reader.ui

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
    secondary = Color(0xFFB8CCBC), onSecondary = Color(0xFF24372B),
    secondaryContainer = Color(0xFF35463A), onSecondaryContainer = Color(0xFFD8E8DA),
    tertiary = Color(0xFFC5C4AA), onTertiary = Color(0xFF313222),
    tertiaryContainer = Color(0xFF484934), onTertiaryContainer = Color(0xFFE5E5C9),
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
    secondary = Color(0xFF4F6654), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E7D8), onSecondaryContainer = Color(0xFF182D1D),
    tertiary = Color(0xFF656044), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFECE5C5), onTertiaryContainer = Color(0xFF302C16),
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
private val warmScheme = lightColorScheme(
    primary = Color(0xFF735019), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEBD49F), onPrimaryContainer = Color(0xFF342302),
    secondary = Color(0xFF536044), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDEE3BF), onSecondaryContainer = Color(0xFF202B11),
    tertiary = Color(0xFF79513C), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3D8C4), onTertiaryContainer = Color(0xFF31190C),
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

internal fun marginColorScheme(theme: String): ColorScheme = when (theme) {
    "light" -> classicScheme
    "warm" -> warmScheme
    else -> focusScheme
}
private val focusTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 34.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp)
)
@Composable fun MarginTheme(theme: String = "dark", content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = marginColorScheme(theme), typography = focusTypography, content = content)
}
