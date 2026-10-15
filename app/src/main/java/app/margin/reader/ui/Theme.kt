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
    val Border = Color(0xFF526168)
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
    inverseSurface = Color(0xFFE4E3DA), inverseOnSurface = Color(0xFF2C332F), inversePrimary = Color(0xFF735731),
    surfaceBright = Color(0xFF344047), surfaceDim = FocusColors.Background,
    surfaceContainerLowest = Color(0xFF10171B), surfaceContainerLow = Color(0xFF1C252B),
    surfaceContainer = FocusColors.Surface, surfaceContainerHigh = Color(0xFF29343B), surfaceContainerHighest = Color(0xFF354047)
)
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
@Composable fun MarginTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = focusScheme, typography = focusTypography, content = content)
}
