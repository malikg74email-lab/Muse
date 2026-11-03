package io.zer0.muse.ui.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.zer0.muse.R
import io.zer0.muse.ui.theme.*

// 13. Shaheen 主题 (午夜海军蓝 + 青蓝 + 银,Jarvis 风)
// ─────────────────────────────────────────────────────────────────────────────
private val ShaheenCyan = Color(0xFF38D6F5)
private val ShaheenCyanDeep = Color(0xFF0091C8)
private val ShaheenPrimaryLightContainer = Color(0xFFCDEFFB)
private val ShaheenPrimaryDarkContainer = Color(0xFF0E3A4D)
private val ShaheenOnPrimaryDark = Color(0xFFCDEFFB)
private val ShaheenLightBg = Color(0xFFF2F7FB)
private val ShaheenDarkBg = Color(0xFF001124)
private val ShaheenLightInk = Color(0xFF0A1A2A)
private val ShaheenDarkInk = Color(0xFFE6F0F8)
private val ShaheenLightAiBubble = Color(0xFFE2EDF5)
private val ShaheenDarkAiBubble = Color(0xFF0A2036)
private val ShaheenSilverDark = Color(0xFFA9C3D9)
private val ShaheenGold = Color(0xFFD9BC7A)
private val ShaheenGoldDeep = Color(0xFF9A7B24)

val ShaheenTheme = PresetTheme(
    id = "shaheen",
    nameResId = R.string.theme_shaheen,
    lightScheme = lightColorScheme(
        primary = ShaheenCyanDeep,
        onPrimary = Color.White,
        primaryContainer = ShaheenPrimaryLightContainer,
        onPrimaryContainer = Color(0xFF06324A),
        secondary = Color(0xFF54748E),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDCE8F1),
        onSecondaryContainer = Color(0xFF16324A),
        tertiary = ShaheenGoldDeep,
        onTertiary = Color.White,
        // tertiary 为金色 VIP 点缀色(稀有高亮),container/onContainer 同步配套。
        tertiaryContainer = Color(0xFFF1E5C3),
        onTertiaryContainer = Color(0xFF4A3A08),
        // M-2: surface 容器梯度,surfaceContainer 复用 AiBubble(银蓝调略深于 surface)。
        surfaceContainer = ShaheenLightAiBubble,
        surfaceContainerLow = ShaheenLightBg,
        surfaceContainerHigh = Color(0xFFD3E2EE),
        surfaceDim = Color(0xFFCBD9E6),
        surfaceBright = Color(0xFFFFFFFF),
        background = ShaheenLightBg,
        onBackground = ShaheenLightInk,
        surface = ShaheenLightBg,
        onSurface = ShaheenLightInk,
        surfaceVariant = ShaheenLightAiBubble,
        onSurfaceVariant = ShaheenLightInk,
        surfaceTint = ShaheenCyanDeep,
        inverseSurface = ShaheenLightInk,
        inverseOnSurface = ShaheenLightBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerLightContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF6E879C),
        outlineVariant = Color(0xFFD3E0EA),
        scrim = Color.Black,
    ),
    darkScheme = darkColorScheme(
        primary = ShaheenCyan,
        onPrimary = Color(0xFF00222E),
        primaryContainer = ShaheenPrimaryDarkContainer,
        onPrimaryContainer = ShaheenOnPrimaryDark,
        secondary = ShaheenSilverDark,
        onSecondary = Color(0xFF0A1E30),
        secondaryContainer = Color(0xFF16344E),
        onSecondaryContainer = Color(0xFFD7E6F2),
        tertiary = ShaheenGold,
        onTertiary = Color(0xFF2E2303),
        // tertiary 为金色 VIP 点缀色(稀有高亮),container/onContainer 同步配套。
        tertiaryContainer = Color(0xFF3A2F14),
        onTertiaryContainer = Color(0xFFF1E2B0),
        // M-2: surface 容器梯度(深色越 high 越亮),surfaceContainer 复用 AiBubble。
        surfaceContainer = ShaheenDarkAiBubble,
        surfaceContainerLow = ShaheenDarkBg,
        surfaceContainerHigh = Color(0xFF12314F),
        surfaceDim = Color(0xFF000A18),
        surfaceBright = Color(0xFF1D3A56),
        background = ShaheenDarkBg,
        onBackground = ShaheenDarkInk,
        surface = ShaheenDarkBg,
        onSurface = ShaheenDarkInk,
        surfaceVariant = ShaheenDarkAiBubble,
        onSurfaceVariant = ShaheenDarkInk,
        surfaceTint = ShaheenCyan,
        inverseSurface = ShaheenDarkInk,
        inverseOnSurface = ShaheenDarkBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerDarkContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF5F7E99),
        outlineVariant = Color(0xFF16324C),
        scrim = Color.Black,
    ),
)

// ─────────────────────────────────────────────────────────────────────────────
