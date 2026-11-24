package com.clonsapps.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.clonsapps.data.ThemeMode

// Warna dari logo: navy, cyan, ungu.
val Navy = Color(0xFF03104A)
val NavyDeep = Color(0xFF040B2C)
val Cyan = Color(0xFF22D3EE)
val CyanDeep = Color(0xFF0891B2)
val Purple = Color(0xFF8B5CF6)
val PurpleDeep = Color(0xFF6D28D9)
val Success = Color(0xFF16A34A)
val Warning = Color(0xFFD97706)

private val Dark = darkColorScheme(
    primary = Cyan,
    onPrimary = Color(0xFF002530),
    primaryContainer = Color(0xFF0D3B66),
    onPrimaryContainer = Color(0xFFBDF4FF),
    secondary = Color(0xFFB39DFA),
    onSecondary = Color(0xFF22105C),
    secondaryContainer = Color(0xFF3A2580),
    onSecondaryContainer = Color(0xFFE9DDFF),
    tertiary = Color(0xFF7DD3FC),
    background = NavyDeep,
    onBackground = Color(0xFFE3E7FF),
    surface = Color(0xFF081340),
    onSurface = Color(0xFFE3E7FF),
    surfaceVariant = Color(0xFF15215A),
    onSurfaceVariant = Color(0xFFC2C8EA),
    outline = Color(0xFF5A6599),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0000),
    errorContainer = Color(0xFF5C1111),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0B3A8C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDF5FF),
    onPrimaryContainer = Navy,
    secondary = PurpleDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEADDFF),
    onSecondaryContainer = Color(0xFF22105C),
    tertiary = CyanDeep,
    background = Color(0xFFF5F7FF),
    onBackground = Color(0xFF0B1238),
    surface = Color.White,
    onSurface = Color(0xFF0B1238),
    surfaceVariant = Color(0xFFE3E8F8),
    onSurfaceVariant = Color(0xFF424A6B),
    outline = Color(0xFF8790B5),
)

@Composable
fun ClonAppsTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
