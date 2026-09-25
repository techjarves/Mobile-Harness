package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

/** How the proxy authenticates against the real upstream. */
internal enum class ProxyCredentialStyle(val bearer: Boolean, val apiKeyHeader: Boolean) {
    /** OAuth subscription token: `Authorization: Bearer` only. */
    BEARER_ONLY(bearer = true, apiKeyHeader = false),

    /** API key endpoints: both common spellings, so any compatible gateway accepts. */
    KEY_AND_BEARER(bearer = true, apiKeyHeader = true),
}

/** Per-project request/byte counters — the foundation of the cost wall (roadmap §4.6-1). */
internal object ProxyUsageRegistry {
    class Counter {
        val requests = AtomicLong()
        val responseBytes = AtomicLong()
    }

    private val byProject = ConcurrentHashMap<String, Counter>()

    fun record(projectKey: String, responseBytes: Long) {
        val counter = byProject.getOrPut(projectKey, ::Counter)
        counter.requests.incrementAndGet()
        counter.responseBytes.addAndGet(responseBytes)
    }

    fun snapshot(): Map<String, Pair<Long, Long>> =
        byProject.mapValues { (_, counter) -> counter.requests.get() to counter.responseBytes.get() }

    fun reset() = byProject.clear()
}

/**
 * Sovereign proxy MVP (roadmap 3a, ISSUE-010): a loopback-only forwarding
 * proxy that holds the provider credential on the host side and injects it
 * only when talking to the real upstream.
 *
 * The guest agent is pointed at `http://127.0.0.1:<port>/t/<token>` and never
 * receives the real key, so `/proc/<pid>/environ` inside the PRoot guest exposes
 * no secrets. Built on the same hardening as the format gateway (2f):
 *  - random 128-bit path token, 403 before anything is read;
 *  - request bodies capped at [MAX_BODY_BYTES] (413) and header lines at
 *    [MAX_HEADER_LINE_BYTES] (connection dropped);
 *  - small fixed thread pool with a bounded queue and caller-runs backpressure.
 *
 * Responses (including SSE streams) are relayed byte-for-byte as they arrive,
 * so Claude Code's streaming output keeps its incremental behaviour.
 */
internal class SovereignProxy(
    private val upstreamBaseUrl: String,
    private val credentialStyle: ProxyCredentialStyle,
    private val apiKey: String,
    private val projectKey: String,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))

    /** 128-bit random path token; every request must carry it. */
    private val token: String = SecureRandom().let { random ->
        ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    }

    val url: String = "http://127.0.0.1:${server.localPort}/t/$token"

    private val executor = ThreadPoolExecutor(
        2,
        4,
        30_000L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(32),
        { runnable -> Thread(runnable, "mh-sovereign-worker").apply { isDaemon = true } },
        ThreadPoolExecutor.CallerRunsPolicy(),
    )

    fun start(): SovereignProxy = apply {
        Thread({ acceptLoop() }, "mh-sovereign-proxy").apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            runCatching { server.accept() }.getOrNull()?.let { socket ->
                runCatching { executor.execute { socket.use(::handle) } }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 60_000
        socket.tcpNoDelay = true
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val path = requestLine.split(' ').getOrNull(1).orEmpty().substringBefore('?')
        val output = BufferedOutputStream(socket.getOutputStream())

        // Token gate (same rule as the format gateway): checked before anything
        // is read or answered.
        if (!path.startsWith("/t/$token/")) {
            writeJson(output, 403, errorJson("forbidden", "Invalid proxy token"))
            return
        }

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
        }
        val length = headers["content-length"]?.toLongOrNull()
        if (length == null || length <= 0L) {
            writeJson(output, 411, errorJson("length_required", "Content-Length is required"))
            return
        }
        if (length > MAX_BODY_BYTES) {
            // Reject before allocating anything.
            writeJson(output, 413, errorJson("payload_too_large", "Request body exceeds ${MAX_BODY_BYTES / (1024 * 1024)} MB"))
            return
        }
        val lengthInt = length.toInt()
        val bodyBytes = ByteArray(lengthInt)
        var offset = 0
        while (offset < lengthInt) {
            val count = input.read(bodyBytes, offset, lengthInt - offset)
            if (count < 0) return
            offset += count
        }

        forward(output, path.removePrefix("/t/$token"), headers, bodyBytes)
    }

    private fun forward(output: BufferedOutputStream, upstreamPath: String, headers: Map<String, String>, body: ByteArray) {
        val endpoint = upstreamBaseUrl.trimEnd('/') + upstreamPath
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        val relayed = AtomicLong(0)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            // Generous per-read timeout: SSE streams may pause between events.
            connection.readTimeout = 300_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", headers["content-type"] ?: "application/json")
            headers["accept"]?.let { connection.setRequestProperty("Accept", it) }
            // Forward Anthropic protocol headers (version hints, betas) untouched.
            headers.entries
                .filter { it.key.startsWith("anthropic-") }
                .forEach { (name, value) -> connection.setRequestProperty(name.replaceFirstChar { it.uppercaseChar() }, value) }
            // The credential is injected here — never forwarded from the guest.
            if (credentialStyle.bearer) connection.setRequestProperty("Authorization", "Bearer $apiKey")
            if (credentialStyle.apiKeyHeader) connection.setRequestProperty("x-api-key", apiKey)
            connection.outputStream.use { it.write(body) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val contentType = connection.contentType ?: "application/json"
            val head = "HTTP/1.1 $code ${reasonFor(code)}\r\nContent-Type: $contentType\r\nConnection: close\r\n\r\n"
            output.write(head.toByteArray())
            if (stream != null) {
                stream.use { source ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        relayed.addAndGet(count.toLong())
                        output.flush()
                    }
                }
            }
            output.flush()
        } catch (error: Exception) {
            AppLog.w("SovereignProxy", "Upstream request failed: ${error.message}")
            runCatching { writeJson(output, 502, errorJson("api_error", error.message ?: "Upstream request failed")) }
        } finally {
            ProxyUsageRegistry.record(projectKey, relayed.get())
            connection.disconnect()
        }
    }

    private fun writeJson(output: BufferedOutputStream, code: Int, body: String) {
        val bytes = body.toByteArray()
        output.write(
            "HTTP/1.1 $code ${reasonFor(code)}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(),
        )
        output.write(bytes)
        output.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().decodeToString()
            if (value == '\n'.code) return bytes.toByteArray().decodeToString().trimEnd('\r')
            bytes += value.toByte()
            // A header with no newline must never grow into an unbounded buffer.
            if (bytes.size > MAX_HEADER_LINE_BYTES) return null
        }
    }

    private fun reasonFor(code: Int): String = when (code) {
        in 200..299 -> "OK"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        in 500..599 -> "Error"
        else -> "Status"
    }

    private fun errorJson(type: String, message: String): String =
        org.json.JSONObject()
            .put("type", "error")
            .put(
                "error",
                org.json.JSONObject().put("type", type).put("message", message.take(500)),
            )
            .toString()

    override fun close() {
        running.set(false)
        executor.shutdownNow()
        runCatching { server.close() }
    }

    companion object {
        /**
         * Request body cap. Deliberately far above the format gateway's 2 MB:
         * Claude Code requests legitimately carry base64 images and very long
         * contexts, but the allocation must still be bounded.
         */
        private const val MAX_BODY_BYTES = 32L * 1024 * 1024

        /** Header line cap (same rule as the format gateway). */
        private const val MAX_HEADER_LINE_BYTES = 16 * 1024

        /** Upstream base for OAuth subscription traffic. */
        internal const val ANTHROPIC_UPSTREAM = "https://api.anthropic.com"

        /**
         * Creates the proxy for a provider profile, or `null` when the profile
         * needs no proxy (OpenAI-protocol providers go through the format
         * gateway, which already holds the key host-side).
         */
        fun forProvider(profile: ProviderProfile, secret: String, projectKey: String): SovereignProxy? =
            when (profile.kind.protocol) {
                ProviderProtocol.OPENAI_RESPONSES, ProviderProtocol.OPENAI_CHAT -> null
                ProviderProtocol.CLAUDE_LOGIN -> SovereignProxy(
                    upstreamBaseUrl = ANTHROPIC_UPSTREAM,
                    credentialStyle = ProxyCredentialStyle.BEARER_ONLY,
                    apiKey = secret,
                    projectKey = projectKey,
                )
                else -> SovereignProxy(
                    upstreamBaseUrl = profile.resolvedBaseUrl,
                    credentialStyle = ProxyCredentialStyle.KEY_AND_BEARER,
                    apiKey = secret,
                    projectKey = projectKey,
                )
            }
    }
}
