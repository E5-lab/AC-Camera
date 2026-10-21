package com.e5lab.accamera.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF1565C0)
private val Teal = Color(0xFF00897B)
private val Orange = Color(0xFFEF6C00)

private val LightScheme = lightColorScheme(
    primary = Blue,
    secondary = Teal,
    tertiary = Orange,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    secondary = Color(0xFF80CBC4),
    tertiary = Color(0xFFFFB74D),
)

@Composable
fun ACCameraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
