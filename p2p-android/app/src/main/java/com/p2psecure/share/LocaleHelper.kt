package com.p2psecure.share

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    fun wrap(base: Context): Context {
        return try {
            val lang = Prefs(base).language.ifBlank { "ar" }
            val locale = Locale(lang)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            config.setLayoutDirection(locale)
            base.createConfigurationContext(config)
        } catch (_: Exception) {
            base
        }
    }
}
