package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderProfile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small loopback-only Anthropic-to-OpenAI compatibility bridge for Claude Code.
 *
 * Hardening (ISSUE-009, roadmap 2f):
 *  - a random 128-bit token in the URL path (`/t/<token>/…`) so other apps on
 *    the device cannot silently use the gateway or the provider key;
 *  - request bodies are capped at [MAX_BODY_BYTES] (413) and header lines at
 *    [MAX_HEADER_LINE_BYTES] (connection dropped) — no unbounded allocations;
 *  - connections run on a small fixed thread pool with bounded queue and
 *    caller-runs backpressure instead of one unbounded thread per request.
 */
internal class LocalFormatGateway(
    private val profile: ProviderProfile,
    private val apiKey: String,
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
        { runnable -> Thread(runnable, "mh-format-worker").apply { isDaemon = true } },
        ThreadPoolExecutor.CallerRunsPolicy(),
    )

    fun start(): LocalFormatGateway = apply {
        Thread({ acceptLoop() }, "mh-format-gateway").apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            runCatching { server.accept() }.getOrNull()?.let { socket ->
                runCatching { executor.execute { socket.use(::handle) } }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val path = requestLine.split(' ').getOrNull(1).orEmpty().substringBefore('?')
        val output = BufferedOutputStream(socket.getOutputStream())

        // Token gate (ISSUE-009): checked before anything is read or answered.
        if (!path.startsWith("/t/$token/")) {
            writeJson(output, 403, errorJson("forbidden", "Invalid gateway token"))
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
            // Reject before allocating anything (ISSUE-009: a 2GB header must
            // never become a 2GB allocation).
            writeJson(output, 413, errorJson("payload_too_large", "Request body exceeds ${MAX_BODY_BYTES / (1024 * 1024)} MB"))
            return
        }
        val lengthInt = length.toInt()
        val bodyBytes = ByteArray(lengthInt)
        var offset = 0
        while (offset < lengthInt) {
            val count = input.read(bodyBytes, offset, lengthInt - offset)
            if (count < 0) break
            offset += count
        }
        if (path.endsWith("/count_tokens")) {
            val approximate = bodyBytes.decodeToString().length / 4 + 1
            writeJson(output, 200, JSONObject().put("input_tokens", approximate).toString())
            return
        }
        if (!path.endsWith("/messages")) {
            writeJson(output, 404, errorJson("not_found", "Unsupported gateway endpoint"))
            return
        }
        runCatching {
            val anthropic = JSONObject(bodyBytes.decodeToString())
            val upstream = callProvider(toOpenAi(anthropic))
            if (upstream.first !in 200..299) {
                AppLog.w("FormatGateway", "Provider returned HTTP ${upstream.first}: ${providerError(upstream.second)}")
                writeJson(output, upstream.first, errorJson("api_error", providerError(upstream.second)))
            } else {
                val translated = fromOpenAi(JSONObject(upstream.second), anthropic.optString("model", profile.model))
                if (anthropic.optBoolean("stream", false)) writeStream(output, translated) else writeJson(output, 200, translated.toString())
            }
        }.onFailure { error ->
            writeJson(output, 502, errorJson("api_error", error.message ?: "Provider request failed"))
        }
    }

    private fun toOpenAi(source: JSONObject): JSONObject {
        val target = JSONObject()
            .put("model", normalizeModel(profile.model))
            .put("stream", false)
            .put("max_tokens", source.optInt("max_tokens", 4096))
        if (source.has("temperature")) target.put("temperature", source.get("temperature"))
        val messages = JSONArray()
        source.opt("system")?.let { system ->
            val text = when (system) {
                is JSONArray -> contentText(system)
                else -> system.toString()
            }
            if (text.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", text))
        }
        val sourceMessages = source.optJSONArray("messages") ?: JSONArray()
        for (index in 0 until sourceMessages.length()) {
            val message = sourceMessages.getJSONObject(index)
            val role = message.optString("role")
            val content = message.opt("content")
            if (content !is JSONArray) {
                messages.put(JSONObject().put("role", role).put("content", content ?: ""))
                continue
            }
            val text = contentText(content)
            val toolCalls = JSONArray()
            val toolResults = mutableListOf<JSONObject>()
            for (partIndex in 0 until content.length()) {
                val part = content.optJSONObject(partIndex) ?: continue
                when (part.optString("type")) {
                    "tool_use" -> toolCalls.put(
                        JSONObject().put("id", part.optString("id"))
                            .put("type", "function")
                            .put("function", JSONObject().put("name", part.optString("name")).put("arguments", part.optJSONObject("input")?.toString() ?: "{}")),
                    )
                    "tool_result" -> toolResults += JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", part.optString("tool_use_id"))
                        .put("content", valueText(part.opt("content")))
                }
            }
            if (text.isNotBlank() || toolCalls.length() > 0) {
                val converted = JSONObject().put("role", role).put("content", text.ifBlank { JSONObject.NULL })
                if (toolCalls.length() > 0) converted.put("tool_calls", toolCalls)
                messages.put(converted)
            }
            toolResults.forEach(messages::put)
        }
        target.put("messages", messages)
        source.optJSONArray("tools")?.let { tools ->
            val converted = JSONArray()
            for (index in 0 until tools.length()) {
                val tool = tools.getJSONObject(index)
                converted.put(JSONObject().put("type", "function").put("function", JSONObject()
                    .put("name", tool.optString("name"))
                    .put("description", tool.optString("description"))
                    .put("parameters", tool.optJSONObject("input_schema") ?: JSONObject().put("type", "object"))))
            }
            target.put("tools", converted).put("tool_choice", "auto")
        }
        return target
    }

    private fun fromOpenAi(source: JSONObject, model: String): JSONObject {
        val message = source.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: JSONObject()
        val content = JSONArray()
        val text = message.optString("content")
        if (text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", text))
        val calls = message.optJSONArray("tool_calls") ?: JSONArray()
        for (index in 0 until calls.length()) {
            val call = calls.getJSONObject(index)
            val function = call.optJSONObject("function") ?: JSONObject()
            val arguments = runCatching { JSONObject(function.optString("arguments", "{}")) }.getOrDefault(JSONObject())
            content.put(JSONObject().put("type", "tool_use")
                .put("id", call.optString("id").ifBlank { "tool_${UUID.randomUUID()}" })
                .put("name", function.optString("name"))
                .put("input", arguments))
        }
        val usage = source.optJSONObject("usage") ?: JSONObject()
        return JSONObject().put("id", source.optString("id").ifBlank { "msg_${UUID.randomUUID()}" })
            .put("type", "message").put("role", "assistant").put("model", model)
            .put("content", content).put("stop_reason", if (calls.length() > 0) "tool_use" else "end_turn")
            .put("stop_sequence", JSONObject.NULL)
            .put("usage", JSONObject().put("input_tokens", usage.optInt("prompt_tokens")).put("output_tokens", usage.optInt("completion_tokens")))
    }

    private fun callProvider(body: JSONObject): Pair<Int, String> {
        val endpoint = profile.baseUrl.trimEnd('/') + "/chat/completions"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 180_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }

    private fun writeStream(output: BufferedOutputStream, message: JSONObject) {
        val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
        output.write(headers.toByteArray())
        fun event(name: String, data: JSONObject) { output.write("event: $name\ndata: $data\n\n".toByteArray()) }
        val content = message.getJSONArray("content")
        event("message_start", JSONObject().put("type", "message_start").put("message", JSONObject(message.toString()).put("content", JSONArray()).put("stop_reason", JSONObject.NULL)))
        for (index in 0 until content.length()) {
            val block = content.getJSONObject(index)
            val type = block.getString("type")
            val start = if (type == "text") JSONObject().put("type", "text").put("text", "") else JSONObject().put("type", "tool_use").put("id", block.getString("id")).put("name", block.getString("name")).put("input", JSONObject())
            event("content_block_start", JSONObject().put("type", "content_block_start").put("index", index).put("content_block", start))
            val delta = if (type == "text") JSONObject().put("type", "text_delta").put("text", block.getString("text")) else JSONObject().put("type", "input_json_delta").put("partial_json", block.getJSONObject("input").toString())
            event("content_block_delta", JSONObject().put("type", "content_block_delta").put("index", index).put("delta", delta))
            event("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", index))
        }
        event("message_delta", JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", message.getString("stop_reason")).put("stop_sequence", JSONObject.NULL)).put("usage", message.getJSONObject("usage")))
        event("message_stop", JSONObject().put("type", "message_stop"))
        output.flush()
    }

    private fun writeJson(output: BufferedOutputStream, code: Int, body: String) {
        val bytes = body.toByteArray()
        val reason = if (code in 200..299) "OK" else "Error"
        output.write("HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
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
            // Cap header line length (ISSUE-009): a header with no newline must
            // never grow into an unbounded buffer. Drop the connection instead.
            if (bytes.size > MAX_HEADER_LINE_BYTES) return null
        }
    }

    private fun contentText(array: JSONArray): String = buildString {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("type") == "text") append(item.optString("text"))
        }
    }

    private fun valueText(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> contentText(value).ifBlank { value.toString() }
        null, JSONObject.NULL -> ""
        else -> value.toString()
    }

    private fun providerError(body: String): String = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message").orEmpty().ifBlank { body.take(500) }
    }.getOrDefault(body.take(500))

    private fun normalizeModel(model: String): String = model
        .removePrefix("models/")
        .removePrefix("anthropic/")
        .substringBefore('[')
        .trim()

    private fun errorJson(type: String, message: String) = JSONObject().put("type", "error").put("error", JSONObject().put("type", type).put("message", message)).toString()

    override fun close() {
        running.set(false)
        executor.shutdownNow()
        runCatching { server.close() }
    }

    private companion object {
        /** Request body cap: 2 MB is far above any legitimate messages payload. */
        private const val MAX_BODY_BYTES = 2L * 1024 * 1024

        /** Header line cap: protects readLine from a newline-less byte flood. */
        private const val MAX_HEADER_LINE_BYTES = 16 * 1024
    }
}
