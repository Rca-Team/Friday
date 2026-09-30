package com.friday.connect.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Calm iOS-inspired palette for the mobile companion.
private val FridayDarkColors = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    secondary = Color(0xFF64D2FF),
    onSecondary = Color(0xFF001018),
    tertiary = Color(0xFF5E5CE6),
    onTertiary = Color.Black,
    background = Color(0xFF070A12),
    onBackground = Color(0xFFF5F7FF),
    surface = Color(0xFF121826),
    surfaceVariant = Color(0xFF202938),
    onSurface = Color(0xFFF5F7FF),
    onSurfaceVariant = Color(0xFFAEB9CC),
    error = Color(0xFFFF3B30),
)

@Composable
fun FridayConnectTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FridayDarkColors,
        typography = androidx.compose.material3.Typography(),
        content = content,
    )
}
