package com.pn.zenify.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

private val ZenGreen = Color(0xFF2E7D52)
private val ZenGreenDark = Color(0xFF6FD79B)

/**
 * Fixed brand colors for the top bar + FAB, so they always match the leaf logo
 * regardless of the (possibly non-green) Material You dynamic palette.
 */
val ZenBrand = Color(0xFF1B5E3A)
val OnZenBrand = Color(0xFFFFFFFF)

private val LightColors = lightColorScheme(
    primary = ZenGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB6F2CE),
    onPrimaryContainer = Color(0xFF002112),
    secondary = Color(0xFF4F6353),
    background = Color(0xFFF7FBF4),
    surface = Color(0xFFF7FBF4),
    surfaceVariant = Color(0xFFDCE5DC),
    onSurfaceVariant = Color(0xFF404943),
)

private val DarkColors = darkColorScheme(
    primary = ZenGreenDark,
    onPrimary = Color(0xFF003822),
    primaryContainer = Color(0xFF005234),
    onPrimaryContainer = Color(0xFFB6F2CE),
    secondary = Color(0xFFB6CCBA),
    background = Color(0xFF0F1411),
    surface = Color(0xFF0F1411),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFC0C9C1),
)

private val ZenTypography = Typography(
    headlineMedium = Typography().headlineMedium.copy(
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Start,
    ),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = Typography().labelSmall.copy(letterSpacing = 0.8.sp),
)

@Composable
fun ZenifyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = ZenTypography,
        content = content,
    )
}
