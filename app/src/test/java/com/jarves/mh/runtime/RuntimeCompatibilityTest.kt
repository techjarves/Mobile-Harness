package com.jarves.mh.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCompatibilityTest {
    @Test
    fun acceptsNativeArm64Android() {
        assertTrue(supportsArm64Runtime(arrayOf("arm64-v8a", "armeabi-v7a"), "aarch64"))
    }

    @Test
    fun acceptsArm64DevicesReportingVendorOsArch() {
        assertTrue(supportsArm64Runtime(arrayOf("arm64-v8a", "armeabi-v7a", "armeabi"), "armv8l"))
        assertTrue(supportsArm64Runtime(arrayOf("arm64-v8a"), null))
    }

    @Test
    fun rejectsX8664EvenWhenTranslationAdvertisesArm64() {
        assertFalse(supportsArm64Runtime(arrayOf("arm64-v8a", "x86_64"), "x86_64"))
    }

    @Test
    fun rejectsDevicesWithoutArm64Abi() {
        assertFalse(supportsArm64Runtime(arrayOf("x86_64", "x86"), "x86_64"))
        assertFalse(supportsArm64Runtime(arrayOf("armeabi-v7a"), "armv7l"))
    }

    @Test
    fun onlyOffersDeepSeekHarnessVersionValidatedForThisBuild() {
        assertTrue(isSupportedDshVersion(RuntimeInstaller.DSH_VERSION))
        assertFalse(isSupportedDshVersion("0.2.0-rc.2"))
        assertFalse(isSupportedDshVersion(""))
    }
}
