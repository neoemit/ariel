package com.thomaslamendola.ariel.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColors: ColorScheme = darkColorScheme(
    primary = ArielRed,
    onPrimary = ArielOnRed,
    primaryContainer = ArielRedDark,
    onPrimaryContainer = ArielOnRed,
    secondary = SurfaceBright,
    onSecondary = SurfaceDim,
    background = SurfaceDim,
    onBackground = ArielOnRed,
    surface = SurfaceDim,
    onSurface = ArielOnRed,
    surfaceVariant = ArielSurfaceVariant,
    onSurfaceVariant = ArielOnSurfaceVariant,
    error = ArielError,
    onError = ArielOnError,
    errorContainer = ArielErrorContainer,
    onErrorContainer = ArielOnErrorContainer,
)

@Composable
fun ArielTheme(
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        DarkColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content
    )
}
