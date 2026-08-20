package dev.pocket.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PocketOrange = Color(0xFFF28C52)
val PocketBlue = Color(0xFF8EA8FF)
val PocketGreen = Color(0xFF69D69E)
val PocketBackground = Color(0xFF0B0E14)
val PocketSurface = Color(0xFF131821)
val PocketOutline = Color(0xFF2A3240)

private val DarkColors = darkColorScheme(
    primary = PocketOrange,
    onPrimary = Color(0xFF241107),
    secondary = PocketBlue,
    tertiary = PocketGreen,
    background = PocketBackground,
    surface = PocketSurface,
    surfaceVariant = Color(0xFF1B222D),
    outline = PocketOutline,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF9A4318),
    secondary = Color(0xFF405798),
    tertiary = Color(0xFF216943),
)

@Composable
fun PocketTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme || isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
