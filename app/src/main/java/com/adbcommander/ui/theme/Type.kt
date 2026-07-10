package com.adbcommander.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * v2.3.0 — Typography now accepts a [textSizeMultiplier] so the
 * Settings → Appearance → Text Size slider can scale the entire
 * Material3 type system in real time. Defaults to 1.0 (Medium).
 */
fun Typography(textSizeMultiplier: Float = 1.0f): Typography {
    fun sz(sp: Int) = (sp * textSizeMultiplier).sp
    return Typography(
        displayLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = sz(57), lineHeight = sz(64)),
        displayMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = sz(45), lineHeight = sz(52)),
        displaySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = sz(36), lineHeight = sz(44)),
        headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = sz(32), lineHeight = sz(40)),
        headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = sz(28), lineHeight = sz(36)),
        headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = sz(24), lineHeight = sz(32)),
        titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = sz(22), lineHeight = sz(28)),
        titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = sz(18), lineHeight = sz(24)),
        titleSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = sz(14), lineHeight = sz(20)),
        bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = sz(16), lineHeight = sz(24), letterSpacing = 0.5.sp),
        bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = sz(14), lineHeight = sz(20)),
        bodySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = sz(12), lineHeight = sz(16)),
        labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = sz(14), lineHeight = sz(20)),
        labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = sz(12), lineHeight = sz(16)),
        labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = sz(11), lineHeight = sz(16))
    )
}

/** Backwards-compatible singleton used by code that does not supply a multiplier. */
val Typography = Typography()
