package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import java.net.Socket
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFormatGatewayTest {

    /** Sends a bodyless request and returns the HTTP status line. */
    private fun status(headers: List<String>): String {
        val gateway = LocalFormatGateway(
            ProviderProfile(ProviderKind.CUSTOM, "https://provider.invalid/v1", "test-model", true),
            apiKey = KEY,
        ).start()
        try {
            val port = gateway.url.substringAfterLast(':').toInt()
            return Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                val request = "POST /v1/messages HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    headers.joinToString("") { "$it\r\n" } + "\r\n"
                socket.getOutputStream().apply { write(request.toByteArray()); flush() }
                socket.getInputStream().bufferedReader().readLine().orEmpty()
            }
        } finally {
            gateway.close()
        }
    }

    @Test
    fun rejectsRequestWithoutCredentials() {
        assertTrue(status(listOf("Content-Length: 0")).contains("401"))
    }

    @Test
    fun rejectsRequestWithWrongCredentials() {
        assertTrue(status(listOf("x-api-key: wrong-key", "Content-Length: 0")).contains("401"))
    }

    @Test
    fun rejectsOversizedRequestBeforeAllocating() {
        assertTrue(status(listOf("x-api-key: $KEY", "Content-Length: 2000000000")).contains("413"))
    }

    private companion object {
        const val KEY = "test-provider-key"
    }
}
