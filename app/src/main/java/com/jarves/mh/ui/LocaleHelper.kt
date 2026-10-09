package com.jarves.mh.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

class LocalizedContext(
    base: Context,
    private val configContext: Context,
) : ContextWrapper(base) {
    override fun getResources(): Resources = configContext.resources
    override fun getAssets(): AssetManager = configContext.assets
}

object LocaleHelper {

    fun getLocale(languageCode: String): Locale? = when (languageCode.lowercase()) {
        "system", "" -> null
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "en" -> Locale.ENGLISH
        else -> Locale.forLanguageTag(languageCode)
    }

    fun applyLanguage(context: Context, languageCode: String): Context {
        val targetLocale = getLocale(languageCode) ?: run {
            // Back to the device language: undo any default set by an earlier in-app choice.
            Locale.setDefault(Resources.getSystem().configuration.locales[0])
            return context
        }

        Locale.setDefault(targetLocale)
        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(targetLocale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = targetLocale
        }
        config.setLayoutDirection(targetLocale)
        val configContext = context.createConfigurationContext(config)
        return LocalizedContext(context, configContext)
    }
}

/**
 * Resolves strings in the language picked inside the app for code outside Compose
 * (ViewModel toasts, services, notifications, runtime status), where LocalContext isn't available.
 */
object AppStrings {
    fun languageCode(context: Context): String =
        context.getSharedPreferences("pocket_preferences", Context.MODE_PRIVATE)
            .getString("language_code", "system") ?: "system"

    fun context(context: Context): Context = LocaleHelper.applyLanguage(context, languageCode(context))

    fun get(context: Context, @androidx.annotation.StringRes id: Int, vararg args: Any): String =
        context(context).getString(id, *args)
}
