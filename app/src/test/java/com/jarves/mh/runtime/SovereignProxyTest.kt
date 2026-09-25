package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ISSUE-010 (roadmap 3a): the sovereign proxy keeps the provider credential on
 * the host side. The guest token must gate every request, the placeholder from
 * the guest env must never reach the upstream, and streaming responses must be
 * relayed byte-for-byte.
 */
class SovereignProxyTest {

    private class Upstream {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val hits = AtomicInteger(0)
        val lastHeaders = LinkedHashMap<String, String>()
        val lastBody = StringBuilder()
        @Volatile var responseChunks: List<String> = listOf("""{"ok":true}""")
        private val ready = CountDownLatch(1)

        init {
            thread(isDaemon = true) {
                ready.countDown()
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (e: Exception) { break }
                    thread(isDaemon = true) { handle(socket) }
                }
            }
            ready.await(2, TimeUnit.SECONDS)
        }

        private fun handle(socket: Socket) {
            socket.use { s ->
                s.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val method = requestLine.split(' ')[0]
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val split = line.indexOf(':')
                    if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
                }
                val body = StringBuilder()
                headers["content-length"]?.toIntOrNull()?.let { length ->
                    val buffer = CharArray(length)
                    var read = 0
                    while (read < length) {
                        val count = reader.read(buffer, read, length - read)
                        if (count < 0) break
                        read += count
                    }
                    body.append(buffer, 0, read)
                }
                synchronized(this) {
                    hits.incrementAndGet()
                    lastHeaders.clear()
                    lastHeaders.putAll(headers)
                    lastBody.setLength(0)
                    lastBody.append(body)
                }
                val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
                writer.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n")
                responseChunks.forEach { chunk ->
                    writer.write(chunk)
                    writer.flush()
                }
                writer.flush()
            }
        }

        val url: String get() = "http://127.0.0.1:${server.localPort}"
    }

    private lateinit var upstream: Upstream

    @Before
    fun setUp() {
        ProxyUsageRegistry.reset()
        upstream = Upstream()
    }

    @After
    fun tearDown() {
        upstream.server.close()
    }

    private fun proxy(style: ProxyCredentialStyle = ProxyCredentialStyle.KEY_AND_BEARER): SovereignProxy =
        SovereignProxy(upstream.url, style, apiKey = "real-secret-key", projectKey = "demo-project")

    /** Sends one request to the proxy and returns (status, body). */
    private fun request(proxy: SovereignProxy, path: String? = null, body: String = """{"m":"hi"}""", tokenOverride: String? = null): Pair<Int, String> {
        Socket("127.0.0.1", proxy.url.substringAfterLast(":").substringBefore("/").toInt()).use { socket ->
            socket.soTimeout = 15_000
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
            val target = path ?: "/t/${proxy.url.substringAfter("/t/")}/v1/messages"
            val effectiveTarget = tokenOverride?.let { target.replaceFirst("/t/${proxy.url.substringAfter("/t/")}", "/t/$it") } ?: target
            writer.write("POST $effectiveTarget HTTP/1.1\r\n")
            writer.write("Host: 127.0.0.1\r\n")
            writer.write("Content-Type: application/json\r\n")
            writer.write("Authorization: Bearer guest-placeholder\r\n")
            writer.write("anthropic-version: 2023-06-01\r\n")
            writer.write("Content-Length: ${body.toByteArray().size}\r\n\r\n")
            writer.write(body)
            writer.flush()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val statusLine = reader.readLine() ?: return -1 to ""
            val status = statusLine.split(' ').getOrNull(1)?.toInt() ?: -1
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val split = line.indexOf(':')
                if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
            }
            val bodyBuilder = StringBuilder()
            if (headers["content-length"]?.toIntOrNull() != null) {
                var remaining = headers["content-length"]!!.toInt()
                val buffer = CharArray(remaining)
                var read = 0
                while (remaining > 0) {
                    val count = reader.read(buffer, read, remaining)
                    if (count < 0) break
                    read += count
                    remaining -= count
                }
                bodyBuilder.append(buffer, 0, read)
            } else {
                while (true) {
                    val line = reader.readLine() ?: break
                    bodyBuilder.append(line).append('\n')
                }
            }
            return status to bodyBuilder.toString().trim()
        }
    }

    @Test
    fun `a request without the token is rejected before the upstream sees anything`() {
        proxy().use { proxy ->
            proxy.start()
            val (status, body) = request(proxy, path = "/v1/messages")
            assertEquals(403, status)
            assertTrue(body.contains("Invalid proxy token"))
            assertEquals(0, upstream.hits.get())
        }
    }

    @Test
    fun `a request with a wrong token is rejected`() {
        proxy().use { proxy ->
            proxy.start()
            val (status, _) = request(proxy, tokenOverride = "0000000000000000000000000000dead")
            assertEquals(403, status)
            assertEquals(0, upstream.hits.get())
        }
    }

    @Test
    fun `the real credential is injected and the placeholder never leaks upstream`() {
        proxy().use { proxy ->
            proxy.start()
            val (status, _) = request(proxy)
            assertEquals(200, status)

            synchronized(upstream) {
                assertEquals("Bearer real-secret-key", upstream.lastHeaders["authorization"])
                assertEquals("real-secret-key", upstream.lastHeaders["x-api-key"])
                assertEquals("2023-06-01", upstream.lastHeaders["anthropic-version"])
                assertEquals("""{"m":"hi"}""", upstream.lastBody.toString())
            }
        }
    }

    @Test
    fun `bearer-only style omits the x-api-key header`() {
        proxy(style = ProxyCredentialStyle.BEARER_ONLY).use { proxy ->
            proxy.start()
            val (status, _) = request(proxy)
            assertEquals(200, status)
            synchronized(upstream) {
                assertEquals("Bearer real-secret-key", upstream.lastHeaders["authorization"])
                assertNull(upstream.lastHeaders["x-api-key"])
            }
        }
    }

    @Test
    fun `an oversized declared body is rejected before allocation`() {
        proxy().use { proxy ->
            proxy.start()
            // Claim 40 MB without sending it: the proxy must answer 413 from
            // the header alone.
            Socket("127.0.0.1", proxy.url.substringAfterLast(":").substringBefore("/").toInt()).use { socket ->
                socket.soTimeout = 15_000
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
                writer.write("POST /t/${proxy.url.substringAfter("/t/")}/v1/messages HTTP/1.1\r\n")
                writer.write("Content-Length: ${40L * 1024 * 1024}\r\n\r\n")
                writer.flush()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val statusLine = reader.readLine() ?: fail("no response")
                assertEquals("413", statusLine.split(' ').getOrNull(1))
            }
            assertEquals(0, upstream.hits.get())
        }
    }

    @Test
    fun `streamed upstream chunks are relayed in order`() {
        upstream.responseChunks = listOf("event: message_start\n\n", "data: {\"t\":1}\n\n", "data: {\"t\":2}\n\n")
        proxy().use { proxy ->
            proxy.start()
            val (status, body) = request(proxy)
            assertEquals(200, status)
            assertTrue(body.contains("message_start"))
            assertTrue(body.contains("\"t\":1"))
            assertTrue(body.contains("\"t\":2"))
        }
    }

    @Test
    fun `requests and response bytes are counted per project`() {
        proxy().use { proxy ->
            proxy.start()
            request(proxy)
            request(proxy)
            val usage = ProxyUsageRegistry.snapshot()["demo-project"]
            assertNotNull(usage)
            assertEquals(2L, usage!!.first)
            assertTrue(usage.second > 0L)
        }
    }

    @Test
    fun `openai protocol providers use the format gateway instead of a proxy`() {
        val openAiProfile = ProviderProfile(ProviderKind.OPENCODE_ZEN)
        assertNull(SovereignProxy.forProvider(openAiProfile, "key", "p"))

        val claudeLogin = ProviderProfile(ProviderKind.CLAUDE)
        val loginProxy = SovereignProxy.forProvider(claudeLogin, "oauth-token", "p")
        assertNotNull(loginProxy)

        val anthropic = SovereignProxy.forProvider(ProviderProfile(ProviderKind.ANTHROPIC), "key", "p")
        assertNotNull(anthropic)
    }

    private fun fail(message: String): Nothing = kotlin.error(message)
}
