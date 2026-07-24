package dev.netvalve.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Brand: a controlled "valve" green on deep slate. Distinct from generic Material.
private val Green = Color(0xFF3DDC97)
private val GreenDark = Color(0xFF1FA974)
private val Slate = Color(0xFF0E1621)
private val SlateElevated = Color(0xFF16212F)
private val Amber = Color(0xFFFFB020)
private val Red = Color(0xFFE5484D)

private val DarkColors = darkColorScheme(
    primary = Green,
    onPrimary = Slate,
    secondary = GreenDark,
    tertiary = Amber,
    error = Red,
    background = Slate,
    onBackground = Color(0xFFE6ECF2),
    surface = Slate,
    onSurface = Color(0xFFE6ECF2),
    surfaceVariant = SlateElevated,
    onSurfaceVariant = Color(0xFF9FB0C0),
    outline = Color(0xFF2A3A4B),
)

private val LightColors = lightColorScheme(
    primary = GreenDark,
    onPrimary = Color.White,
    secondary = Green,
    tertiary = Color(0xFFB4791F),
    error = Red,
    background = Color(0xFFF7F9FB),
    onBackground = Color(0xFF101922),
    surface = Color.White,
    onSurface = Color(0xFF101922),
    surfaceVariant = Color(0xFFEDF1F5),
    onSurfaceVariant = Color(0xFF4A5A69),
    outline = Color(0xFFCBD5E0),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

@Composable
fun NetValveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
