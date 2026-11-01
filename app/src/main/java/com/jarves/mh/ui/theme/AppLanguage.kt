package com.jarves.mh.ui.theme

enum class AppLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String,
) {
    SYSTEM("system", "System Default", "Default"),
    ENGLISH("en", "English", "English"),
    SPANISH("es", "Spanish", "Español"),
    CHINESE("zh", "Chinese (Simplified)", "简体中文"),
    ARABIC("ar", "Arabic", "العربية"),
    HINDI("hi", "Hindi", "हिन्दी"),
    RUSSIAN("ru", "Russian", "Русский"),
    GREEK("el", "Greek", "Ελληνικά");

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: SYSTEM
    }
}
