package com.jarves.mh.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {
    @Test
    fun httpsIsAlwaysAllowed() {
        assertTrue(LocalNetwork.isPlainHttpAllowed("https://api.example.com/v1"))
    }

    @Test
    fun httpIsAllowedForLocalAndPrivateAddresses() {
        listOf(
            "http://localhost:8080",
            "http://127.0.0.1:11434/v1",
            "http://192.168.1.20:8080",
            "http://10.0.0.5:1234/v1",
            "http://172.20.3.4:8000",
            "http://100.101.102.103:8080", // Tailscale
            "http://my-pc:8080",
            "http://llama.local:8080",
            "http://box.tailnet-abc.ts.net:8080",
            "http://[::1]:8080",
            "http://[fd7a:115c:a1e0::1]:8080",
        ).forEach { assertTrue(it, LocalNetwork.isPlainHttpAllowed(it)) }
    }

    @Test
    fun httpIsRejectedForPublicHosts() {
        listOf(
            "http://api.example.com/v1",
            "http://8.8.8.8:8080",
            "http://172.32.0.1",
            "http://100.128.0.1",
            "http://[2001:db8::1]:8080",
        ).forEach { assertFalse(it, LocalNetwork.isPlainHttpAllowed(it)) }
    }
}
