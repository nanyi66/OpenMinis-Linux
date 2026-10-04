package com.openminis.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [T-android-monet-dynamic-color] The merge RULE, not the framework call.
 *
 * `dynamicLightColorScheme(context)` / `dynamicDarkColorScheme(context)` need a
 * real Context and only resolve on API 31+, so they are not unit-testable here.
 * What IS testable — and what actually matters — is the merge contract:
 * wallpaper-derived accents, Minis' neutral grouped chrome, and on-accent
 * colours that pair with the wallpaper accent rather than Minis' hand-tuned
 * teal-on-white (which is exactly the mistake a naive `.copy(primary = …)`
 * would make).
 */
class DynamicMinisSchemeTest {

    // A wallpaper scheme whose accent is deliberately nothing like Minis'
    // desaturated blue, so a leak from either side is unambiguous.
    private fun wallpaperBase(dark: Boolean): ColorScheme {
        val amber = Color(0xFFB58500)
        return if (dark) {
            darkColorScheme(
                primary = amber,
                onPrimary = Color.Black,
                secondary = Color(0xFF8A6D00),
                onSecondary = Color.White,
                tertiary = Color(0xFFC79A00),
                onTertiary = Color.White,
            )
        } else {
            lightColorScheme(
                primary = amber,
                onPrimary = Color.White,
                secondary = Color(0xFF8A6D00),
                onSecondary = Color.Black,
                tertiary = Color(0xFFC79A00),
                onTertiary = Color.Black,
            )
        }
    }

    // Minis' neutral grouped chrome, with an absurd onPrimary that must never
    // reach the result — it exists to catch chrome leaking into accent slots.
    private val chromeLight = lightColorScheme(
        background = Color(0xFFF2F2F7),
        onBackground = Color(0xFF171D1C),
        surface = Color(0xFFF2F2F7),
        onSurface = Color(0xFF171D1C),
        surfaceVariant = Color.White,
        onSurfaceVariant = Color(0xFF3F4947),
        surfaceContainerLow = Color.White,
        surfaceContainer = Color.White,
        surfaceContainerHigh = Color(0xFFF7F7FA),
        surfaceContainerHighest = Color(0xFFF7F7FA),
        surfaceContainerLowest = Color(0xFFF2F2F7),
        outline = Color(0xFFD1D1D6),
        outlineVariant = Color(0xFFD1D1D6),
        onPrimary = Color.Green,
        onSecondary = Color.Green,
        onTertiary = Color.Green,
    )

    private val chromeDark = darkColorScheme(
        background = Color(0xFF000000),
        onBackground = Color(0xFFDEE4E2),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFDEE4E2),
        surfaceContainer = Color(0xFF1C1C1E),
        outline = Color(0xFF38383A),
    )

    @Test
    fun `accent families come from the wallpaper`() {
        val merged = dynamicMinisScheme(wallpaperBase(dark = false), chromeLight)
        assertEquals(Color(0xFFB58500), merged.primary)
        assertEquals(Color(0xFF8A6D00), merged.secondary)
        assertEquals(Color(0xFFC79A00), merged.tertiary)
    }

    @Test
    fun `on-accent pairs with the wallpaper accent, never with Minis chrome`() {
        // chromeLight sets onPrimary = Green precisely so a wrong-side copy
        // cannot pass silently.
        val merged = dynamicMinisScheme(wallpaperBase(dark = false), chromeLight)
        // All three on-accent slots come from the WALLPAPER scheme…
        assertEquals(Color.White, merged.onPrimary)
        assertEquals(Color.Black, merged.onSecondary)
        assertEquals(Color.Black, merged.onTertiary)
        // …which is what makes the chrome's Green markers a real tripwire: any
        // copy of the on-accent slots from the wrong side lands here.
        assertNotEquals(Color.Green, merged.onPrimary)
        assertNotEquals(Color.Green, merged.onSecondary)
        assertNotEquals(Color.Green, merged.onTertiary)
    }

    @Test
    fun `neutral grouped chrome comes from Minis`() {
        val merged = dynamicMinisScheme(wallpaperBase(dark = false), chromeLight)
        // Page + card + hairline: the iOS systemGroupedBackground identity that
        // must survive wallpaper tinting.
        assertEquals(Color(0xFFF2F2F7), merged.background)
        assertEquals(Color(0xFFF2F2F7), merged.surface)
        assertEquals(Color.White, merged.surfaceContainer)
        assertEquals(Color(0xFFF7F7FA), merged.surfaceContainerHigh)
        assertEquals(Color(0xFFD1D1D6), merged.outline)
        assertEquals(Color(0xFF171D1C), merged.onSurface)
        assertEquals(Color(0xFF3F4947), merged.onSurfaceVariant)
    }

    @Test
    fun `dark wallpaper merges onto dark chrome`() {
        val merged = dynamicMinisScheme(wallpaperBase(dark = true), chromeDark)
        assertEquals(Color(0xFFB58500), merged.primary)
        assertEquals(Color.Black, merged.onPrimary)
        assertEquals(Color(0xFF000000), merged.background)
        assertEquals(Color(0xFF1C1C1E), merged.surfaceContainer)
        assertEquals(Color(0xFFDEE4E2), merged.onSurface)
    }

    @Test
    fun `the same wallpaper with different chrome yields different chrome`() {
        val mergedLightChrome = dynamicMinisScheme(wallpaperBase(dark = false), chromeLight)
        val mergedDarkChrome = dynamicMinisScheme(wallpaperBase(dark = false), chromeDark)
        // Same accent…
        assertEquals(mergedLightChrome.primary, mergedDarkChrome.primary)
        // …but the chrome decides the surface, which is the whole point.
        assertNotEquals(mergedLightChrome.background, mergedDarkChrome.background)
    }

    @Test
    fun `error slots stay with the wallpaper scheme`() {
        // error / onError / errorContainer are part of base on purpose: Minis
        // has no error palette of its own to preserve, and an error that the
        // wallpaper re-tints is still an error.
        val err = Color(0xFFBA1A1A)
        val base = lightColorScheme(error = err)
        val merged = dynamicMinisScheme(base, chromeLight)
        assertEquals(err, merged.error)
    }

    @Test
    fun `chat accent slots come from the wallpaper scheme`() {
        val scheme = wallpaperBase(dark = false)
        val merged = dynamicChatPalette(scheme, LightChatPalette)
        assertEquals(scheme.primaryContainer, merged.userBubble)
        assertEquals(scheme.primary, merged.sendButton)
        assertEquals(scheme.primary, merged.link)
        assertEquals(scheme.primary, merged.thinking)
        assertEquals(scheme.primary, merged.fabAccent)
        assertEquals(scheme.onPrimary, merged.fabOnAccent)
        assertEquals(scheme.primary.copy(alpha = 0.18f), merged.toastBg)
    }

    @Test
    fun `chat neutral and semantic slots stay with Minis`() {
        val scheme = wallpaperBase(dark = true)
        val merged = dynamicChatPalette(scheme, DarkChatPalette)
        // Neutral chrome keeps the T-153 dark ramp...
        assertEquals(DarkChatPalette.background, merged.background)
        assertEquals(DarkChatPalette.toolBg, merged.toolBg)
        assertEquals(DarkChatPalette.inlineCodeBg, merged.inlineCodeBg)
        // ...and semantic hues carry meaning, so they never follow the wallpaper.
        assertEquals(DarkChatPalette.codeBlockText, merged.codeBlockText)
        assertEquals(DarkChatPalette.inlineCodeText, merged.inlineCodeText)
        assertEquals(DarkChatPalette.blockquoteBar, merged.blockquoteBar)
        assertEquals(DarkChatPalette.warningBg, merged.warningBg)
        // Mode marker survives the copy.
        assertEquals(true, merged.isDark)
    }

    @Test
    fun `off-path chat palette is untouched`() {
        // dynamicChatPalette must only run behind the same SDK gate as the
        // scheme branch; when it does not run, the palettes are the statics.
        // Pinned here so a future "always derive" shortcut has to argue with it.
        assertEquals(Color.White, LightChatPalette.fabOnAccent)
        assertEquals(Color.White, DarkChatPalette.fabOnAccent)
        assertEquals(Color(0xFFB7AF96), LightChatPalette.fabAccent)
        assertEquals(Color(0xFF504C42), DarkChatPalette.fabAccent)
    }
}
