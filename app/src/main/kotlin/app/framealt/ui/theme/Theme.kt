package app.framealt.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// A muted teal, so the app reads as a photo utility rather than a brand. Only used where
// the device cannot supply a dynamic scheme.
private val Seed = Color(0xFF1F4E5F)
private val SeedLight = Color(0xFF4C7B8B)
private val Accent = Color(0xFFD98A4E)

private val LightScheme = lightColorScheme(
    primary = Seed,
    secondary = SeedLight,
    tertiary = Accent,
)

private val DarkScheme = darkColorScheme(
    primary = SeedLight,
    secondary = Seed,
    tertiary = Accent,
)

@Composable
fun FrameAltTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
