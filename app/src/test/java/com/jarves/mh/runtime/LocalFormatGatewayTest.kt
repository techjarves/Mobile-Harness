package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import java.io.ByteArrayOutputStream
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-009 hardening, verified over real loopback sockets (roadmap 2f):
 *
 *  - a request without (or with a wrong) token is rejected with 403 before
 *    its body is ever read;
 *  - a Content-Length above the 2 MB cap is rejected with 413 without the
 *    allocation ever happening;
 *  - a missing Content-Length is rejected with 411;
 *  - a header line longer than 16 KB drops the connection instead of growing
 *    an unbounded buffer;
 *  - count_tokens works through the tokened path.
 *
 * Tests deliberately avoid the /messages upstream path so no network call and
 * no android.util.Log call ever happens on the JVM.
 */
class LocalFormatGatewayTest {

    private lateinit var gateway: LocalFormatGateway

    @After
    fun tearDown() {
        if (::gateway.isInitialized) gateway.close()
    }

    private fun startGateway(): Pair<Int, String> {
        gateway = LocalFormatGateway(ProviderProfile(kind = ProviderKind.LLM_ROUTER), "test-key").start()
        val match = Regex("""http://127\.0\.0\.1:(\d+)/t/([0-9a-f]{32})""").find(gateway.url)
            ?: error("gateway url has no token: ${gateway.url}")
        return match.groupValues[1].toInt() to match.groupValues[2]
    }

    private fun request(port: Int, rawRequest: String): String? {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().write(rawRequest.toByteArray())
            socket.getOutputStream().flush()
            // Every response carries "Connection: close", so read until the
            // server ends the conversation.
            val output = ByteArrayOutputStream()
            val input = socket.getInputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val read = runCatching { input.read(buffer) }.getOrDefault(-1)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            return output.toString().ifBlank { null }
        }
    }

    @Test
    fun `the gateway url carries a 128-bit token`() {
        val (port, token) = startGateway()
        assertEquals(32, token.length)
        assertTrue(port > 0)
    }

    @Test
    fun `requests without a token are rejected with 403`() {
        val (port, _) = startGateway()
        val response = request(
            port,
            "POST /v1/messages HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2\r\n\r\n",
        )!!
        assertTrue(response.startsWith("HTTP/1.1 403"))
    }

    @Test
    fun `requests with a wrong token are rejected with 403`() {
        val (port, _) = startGateway()
        val response = request(
            port,
            "POST /t/deadbeefdeadbeefdeadbeefdeadbeef/v1/messages HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2\r\n\r\n",
        )!!
        assertTrue(response.startsWith("HTTP/1.1 403"))
    }

    @Test
    fun `an oversized content-length is rejected with 413 before reading`() {
        val (port, token) = startGateway()
        val response = request(
            port,
            "POST /t/$token/v1/messages HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3000000000\r\n\r\n",
        )!!
        assertTrue(response.startsWith("HTTP/1.1 413"))
    }

    @Test
    fun `a missing content-length is rejected with 411`() {
        val (port, token) = startGateway()
        val response = request(
            port,
            "POST /t/$token/v1/messages HTTP/1.1\r\nHost: localhost\r\n\r\n",
        )!!
        assertTrue(response.startsWith("HTTP/1.1 411"))
    }

    @Test
    fun `a huge header line drops the connection instead of buffering it`() {
        val (port, token) = startGateway()
        val hugeLine = "X-Flood: " + "a".repeat(32 * 1024)
        val response = request(
            port,
            "POST /t/$token/v1/messages HTTP/1.1\r\n$hugeLine\r\n\r\n",
        )
        // Connection dropped with no response — readLine hit the cap.
        assertNull(response)
    }

    @Test
    fun `count_tokens answers through the tokened path`() {
        val (port, token) = startGateway()
        val body = """{"model":"m","messages":[]}"""
        val response = request(
            port,
            "POST /t/$token/v1/count_tokens HTTP/1.1\r\nHost: localhost\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n\r\n$body",
        )!!
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertTrue(response.contains("\"input_tokens\""))
    }
}
