package com.jarves.mh.runtime

internal fun supportsArm64Runtime(supportedAbis: Array<String>, osArchitecture: String?): Boolean {
    // x86 emulators with ARM translation advertise arm64-v8a too, but PRoot needs a native ARM64
    // kernel. Reject x86 explicitly rather than requiring os.arch == "aarch64": some vendor
    // builds (e.g. vivo OriginOS) report other values on genuine ARM64 hardware.
    val x86 = supportedAbis.any { it.startsWith("x86", ignoreCase = true) } ||
        osArchitecture.orEmpty().let { it.startsWith("x86", ignoreCase = true) || it.startsWith("i686", ignoreCase = true) }
    return !x86 && supportedAbis.any { it.equals("arm64-v8a", ignoreCase = true) }
}
