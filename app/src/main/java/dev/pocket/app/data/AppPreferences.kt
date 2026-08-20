package dev.pocket.app.data

import android.content.Context
import dev.pocket.app.model.ProviderKind
import dev.pocket.app.model.ProviderProfile

class AppPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("pocket_preferences", Context.MODE_PRIVATE)

    var onboardingComplete: Boolean
        get() = preferences.getBoolean("onboarding_complete", false)
        set(value) { preferences.edit().putBoolean("onboarding_complete", value).apply() }

    var runtimeSetupComplete: Boolean
        get() = preferences.getBoolean("runtime_setup_complete", false)
        set(value) { preferences.edit().putBoolean("runtime_setup_complete", value).apply() }

    var themeMode: String
        get() = preferences.getString("theme_mode", "dark") ?: "dark"
        set(value) { preferences.edit().putString("theme_mode", value).apply() }


    fun saveProvider(profile: ProviderProfile) {
        preferences.edit()
            .putString("provider_kind", profile.kind.name)
            .putString("provider_base_url", profile.baseUrl)
            .putString("provider_model", profile.model)
            .apply()
    }

    fun loadProvider(vault: ApiKeyVault): ProviderProfile {
        val kind = runCatching { ProviderKind.valueOf(preferences.getString("provider_kind", null).orEmpty()) }
            .getOrDefault(ProviderKind.ANTHROPIC)
        return ProviderProfile(
            kind = kind,
            baseUrl = preferences.getString("provider_base_url", kind.defaultBaseUrl) ?: kind.defaultBaseUrl,
            model = preferences.getString("provider_model", kind.defaultModel) ?: kind.defaultModel,
            hasSecret = vault.contains(kind.name),
        )
    }
}
