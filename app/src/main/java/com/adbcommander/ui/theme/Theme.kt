package com.adbcommander.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * v2.3.0 — Accent color choice surfaced in Settings → Appearance.
 * The selected accent overrides [LightPrimary] / [DarkPrimary] so the
 * entire Material3 color scheme (and every component that derives from
 * it) re-tints in real time when the user picks a new swatch.
 *
 * Index 0 (Teal) is the default and matches the user's mockups.
 */
enum class AccentChoice(val label: String, val light: Color, val dark: Color) {
    Teal("Teal", AccentTeal, Color(0xFF6ED4C5)),
    TealLight("Light Teal", AccentTealLight, Color(0xFFA5D6A7)),
    Blue("Blue", AccentBlue, Color(0xFF7DA9E8))
}

/**
 * Text-size preference surfaced in Settings → Appearance → Text Size Slider.
 * Multiplier applied on top of the base Material3 typography scale.
 */
enum class TextSizeChoice(val label: String, val multiplier: Float) {
    Small("Small", 0.90f),
    Medium("Medium", 1.00f),
    Large("Large", 1.12f)
}

/**
 * Blur-intensity preference surfaced in Settings → Appearance → Blur Intensity Slider.
 * Drives the radius used by [com.adbcommander.GlassCard]'s `Modifier.cloudy` call.
 */
enum class BlurChoice(val label: String, val radiusDp: Int) {
    Subtle("Subtle", 14),
    Normal("Normal", 24),
    Intense("Intense", 36)
}

/**
 * Theme-mode preference surfaced in Settings → Appearance → Theme Toggle.
 * - System: follows [isSystemInDarkTheme] at composition time
 * - Light:  forces light scheme regardless of system setting
 * - Dark:   forces dark scheme regardless of system setting
 */
enum class ThemeMode(val label: String) {
    System("System"),
    Light("Light"),
    Dark("Dark")
}

/** Composition-local so any composable can read the live accent / blur / text-size. */
data class AppearanceConfig(
    val themeMode: ThemeMode = ThemeMode.System,
    val accent: AccentChoice = AccentChoice.Teal,
    val textSize: TextSizeChoice = TextSizeChoice.Medium,
    val blur: BlurChoice = BlurChoice.Normal
)

val LocalAppearance = compositionLocalOf { AppearanceConfig() }

@Composable
fun ADBCommanderTheme(
    appearance: AppearanceConfig = AppearanceConfig(),
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (appearance.themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    val accentLight = appearance.accent.light
    val accentDark = appearance.accent.dark

    val colorScheme = when {
        // v2.3.0: Dynamic color is intentionally OFF — the user's mockups
        // ship a fixed teal accent that must not be over-ridden by the
        // device's wallpaper. Per developer-context.md §5.5 the baseline
        // Material3 palette was already preferred; we now bias it toward
        // the mockup's teal + light/dark scheme.
        darkTheme -> darkColorScheme(
            primary = accentDark,
            onPrimary = DarkOnPrimary,
            primaryContainer = DarkPrimaryContainer,
            onPrimaryContainer = DarkOnPrimaryContainer,
            secondary = DarkSecondary,
            onSecondary = DarkOnSecondary,
            tertiary = DarkTertiary,
            onTertiary = DarkOnTertiary,
            error = DarkError,
            onError = DarkOnError,
            errorContainer = DarkErrorContainer,
            onErrorContainer = DarkOnErrorContainer,
            background = DarkBackground,
            onBackground = DarkOnBackground,
            surface = DarkSurface,
            onSurface = DarkOnSurface,
            surfaceVariant = DarkSurfaceVariant,
            onSurfaceVariant = DarkOnSurfaceVariant,
            outline = DarkOutline
        )
        else -> lightColorScheme(
            primary = accentLight,
            onPrimary = LightOnPrimary,
            primaryContainer = LightPrimaryContainer,
            onPrimaryContainer = LightOnPrimaryContainer,
            secondary = LightSecondary,
            onSecondary = LightOnSecondary,
            tertiary = LightTertiary,
            onTertiary = LightOnTertiary,
            error = LightError,
            onError = LightOnError,
            errorContainer = LightErrorContainer,
            onErrorContainer = LightOnErrorContainer,
            background = LightBackground,
            onBackground = LightOnBackground,
            surface = LightSurface,
            onSurface = LightOnSurface,
            surfaceVariant = LightSurfaceVariant,
            onSurfaceVariant = LightOnSurfaceVariant,
            outline = LightOutline
        )
    }

    CompositionLocalProvider(LocalAppearance provides appearance) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(textSizeMultiplier = appearance.textSize.multiplier),
            content = content
        )
    }
}
