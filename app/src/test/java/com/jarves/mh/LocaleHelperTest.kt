package com.jarves.mh

import com.jarves.mh.ui.LocaleHelper
import com.jarves.mh.ui.theme.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LocaleHelperTest {

    @Test
    fun appLanguageResolutionFromCode() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode("en"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromCode("es"))
        assertEquals(AppLanguage.CHINESE, AppLanguage.fromCode("zh"))
        assertEquals(AppLanguage.ARABIC, AppLanguage.fromCode("ar"))
        assertEquals(AppLanguage.HINDI, AppLanguage.fromCode("hi"))
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromCode("ru"))
        assertEquals(AppLanguage.GREEK, AppLanguage.fromCode("el"))
        assertEquals("el", LocaleHelper.getLocale("el")?.language)
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode("system"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode("unknown_language"))
    }

    @Test
    fun localeHelperGetLocale() {
        assertNull(LocaleHelper.getLocale("system"))
        assertNull(LocaleHelper.getLocale(""))

        val enLocale = LocaleHelper.getLocale("en")
        assertNotNull(enLocale)
        assertEquals("en", enLocale?.language)

        val esLocale = LocaleHelper.getLocale("es")
        assertNotNull(esLocale)
        assertEquals("es", esLocale?.language)

        val zhLocale = LocaleHelper.getLocale("zh")
        assertNotNull(zhLocale)
        assertEquals("zh", zhLocale?.language)

        val arLocale = LocaleHelper.getLocale("ar")
        assertNotNull(arLocale)
        assertEquals("ar", arLocale?.language)

        val hiLocale = LocaleHelper.getLocale("hi")
        assertNotNull(hiLocale)
        assertEquals("hi", hiLocale?.language)

        val ruLocale = LocaleHelper.getLocale("ru")
        assertNotNull(ruLocale)
        assertEquals("ru", ruLocale?.language)
    }

    @Test
    fun appLanguageProperties() {
        for (lang in AppLanguage.entries) {
            assertTrue("Display name should not be blank for ${lang.name}", lang.displayName.isNotBlank())
            assertTrue("Native name should not be blank for ${lang.name}", lang.nativeName.isNotBlank())
            assertTrue("Code should not be blank for ${lang.name}", lang.code.isNotBlank())
        }
    }
}
