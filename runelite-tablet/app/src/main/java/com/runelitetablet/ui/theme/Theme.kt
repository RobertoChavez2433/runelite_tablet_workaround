package com.runelitetablet.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD4A017),
    onPrimary = Color(0xFF1A1A1A),
    primaryContainer = Color(0xFF5C4400),
    onPrimaryContainer = Color(0xFFFFE08A),
    secondary = Color(0xFFBFAE7A),
    onSecondary = Color(0xFF2E2600),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFE6E1D3),
    surfaceVariant = Color(0xFF2D2D2D),
    onSurfaceVariant = Color(0xFFC8C3B5),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF1A1A1A),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E1D3),
)

@Composable
fun RuneLiteTabletTheme(content: @Composable () -> Unit) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            @Suppress("DEPRECATION")
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
