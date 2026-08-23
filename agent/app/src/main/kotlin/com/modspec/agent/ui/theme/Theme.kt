package com.modspec.agent.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = ColorAccent,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = ColorAccentSoft,
    onPrimaryContainer = ColorTextPrimary,
    secondary = androidx.compose.ui.graphics.Color(0xFF556E6B),
    onSecondary = androidx.compose.ui.graphics.Color.White,
    background = ColorBgApp,
    onBackground = ColorTextPrimary,
    surface = ColorSurface,
    onSurface = ColorTextPrimary,
    surfaceVariant = ColorSurfaceMuted,
    onSurfaceVariant = ColorTextSecondary,
    outline = ColorDivider,
    error = ColorStatusFail,
    onError = androidx.compose.ui.graphics.Color.White,
)

private val DarkColors = darkColorScheme(
    primary = ColorAccent,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = ColorAccentSoft,
    onPrimaryContainer = ColorTextPrimary,
    secondary = androidx.compose.ui.graphics.Color(0xFF8FB5B1),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF0B1F1D),
    background = androidx.compose.ui.graphics.Color(0xFF111318),
    onBackground = androidx.compose.ui.graphics.Color(0xFFE2E8F0),
    surface = androidx.compose.ui.graphics.Color(0xFF1A1D23),
    onSurface = androidx.compose.ui.graphics.Color(0xFFE2E8F0),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF252A33),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF9AA7B5),
    outline = androidx.compose.ui.graphics.Color(0xFF3A4250),
    error = ColorStatusFail,
    onError = androidx.compose.ui.graphics.Color.White,
)

@Composable
fun ModspecTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
