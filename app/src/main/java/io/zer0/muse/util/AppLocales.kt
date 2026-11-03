package io.zer0.muse.util

import java.util.Locale

/**
 * Language preference string -> [Locale].
 *
 * "system" or any unknown value returns null, meaning "follow the system locale".
 * Shaheen 2 ships English resources only (the default `values/` tree holds English;
 * Roman Urdu arrives later as a complete `values-ur/`), so "en" is the only
 * explicitly mapped preference; every other stored value safely follows the system.
 *
 * This mapping is shared by cold start (MainActivity.attachBaseContext), the
 * Compose RuntimeLocaleProvider hot-switch, and the web host's localized
 * settings text (HostSettingsStrings), so all three read the same language value.
 */
object AppLocales {

    @Suppress("DEPRECATION") // Locale(language, country) 在新 JDK 标了 deprecated（推荐 Locale.of），
    // 但 project minSdk 上 Locale.of 不可用，沿用构造器是有意的。
    fun parse(language: String): Locale? = when (language) {
        "en" -> Locale.US
        else -> null
    }
}
