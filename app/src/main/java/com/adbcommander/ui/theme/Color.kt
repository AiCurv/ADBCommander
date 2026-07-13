package com.adbcommander.ui.theme

import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════════════
//  v2.3.1 — Premium Teal Palette (ZERO purple)
//  All legacy Purple/Pink constants have been purged. The entire app
//  now uses the teal accent family (#26A69A) seen in the user's mockups.
// ═══════════════════════════════════════════════════════════════════════
//  v2.3.0 — Premium Glassmorphism Palette
//  Anchored on the teal accent (#26A69A) seen in the user's mockups.
//  Light + dark variants ship together so the Appearance → Theme toggle
//  can hot-swap between them without recomposing the color scheme.
// ═══════════════════════════════════════════════════════════════════════

// Accent options surfaced in Settings → Appearance → Accent Color Picker.
// Index 0 is the default (Teal) and matches the mockups.
val AccentTeal = Color(0xFF26A69A)
val AccentTealLight = Color(0xFF81C784)
val AccentBlue = Color(0xFF2196F3)

// ── Light scheme ──────────────────────────────────────────────────────────
val LightBackground = Color(0xFFFFFFFF)
val LightSurface = Color(0xFFFAFAFA)
val LightSurfaceVariant = Color(0xFFF1F3F4)
val LightPrimary = AccentTeal
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFE0F2F1)
val LightOnPrimaryContainer = Color(0xFF003C36)
val LightSecondary = Color(0xFF4A635F)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightTertiary = Color(0xFFB07A00)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)
val LightOnBackground = Color(0xFF191C1B)
val LightOnSurface = Color(0xFF212121)
val LightOnSurfaceVariant = Color(0xFF757575)
val LightOutline = Color(0xFFBFBFBF)

// ── Dark scheme ───────────────────────────────────────────────────────────
val DarkBackground = Color(0xFF0E1413)
val DarkSurface = Color(0xFF161D1C)
val DarkSurfaceVariant = Color(0xFF1F2827)
val DarkPrimary = Color(0xFF6ED4C5)
val DarkOnPrimary = Color(0xFF00302B)
val DarkPrimaryContainer = Color(0xFF1E4F49)
val DarkOnPrimaryContainer = Color(0xFFB6EDE5)
val DarkSecondary = Color(0xFFB1CCC6)
val DarkOnSecondary = Color(0xFF1C352F)
val DarkTertiary = Color(0xFFFFB856)
val DarkOnTertiary = Color(0xFF452B00)
val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)
val DarkOnBackground = Color(0xFFE2E3E0)
val DarkOnSurface = Color(0xFFECECEC)
val DarkOnSurfaceVariant = Color(0xFFB0B8B5)
val DarkOutline = Color(0xFF5A6361)

// ── Glass tint layers (per developer-context.md visual tokens) ────────────
// 0.35f White for light mode, 0.45f Black for dark mode.
val GlassTintLight = Color(0xFFFFFFFF)
val GlassTintDark = Color(0xFF000000)
