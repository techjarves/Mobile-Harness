package com.jarves.mh.network

import com.jarves.mh.model.ProviderProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DiscoveredModel(val id: String, val displayName: String = id, val isFree: Boolean = false)

sealed interface ModelDiscoveryResult {
    data class Success(val models: List<DiscoveredModel>, val endpoint: String) : ModelDiscoveryResult
    data class Failure(val message: String) : ModelDiscoveryResult
}

sealed interface ConnectionValidation {
    data class Success(val message: String) : ConnectionValidation
    data class Failure(val message: String) : ConnectionValidation
}

class ProviderApiClient {
    suspend fun discoverModels(
        baseUrl: String,
        apiKey: String,
        protocol: ProviderProtocol,
    ): ModelDiscoveryResult = withContext(Dispatchers.IO) {
        val cleanBaseUrl = normalizeBaseUrl(baseUrl)
        val cleanKey = apiKey.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
        if (cleanBaseUrl.isBlank() || cleanKey.isBlank()) {
            return@withContext ModelDiscoveryResult.Failure("Enter a base URL and API key first.")
        }

        var authError = false
        var lastMessage = "This provider did not expose a model list. You can enter a custom model name."
        for (endpoint in modelEndpoints(cleanBaseUrl, protocol)) {
            val response = request(endpoint, "GET", cleanKey, protocol = protocol)
            when {
                response.code == 401 || response.code == 403 -> {
                    authError = true
                    val detail = friendlyHttpError(response.code, response.body)
                    lastMessage = if (response.body.isNotBlank()) detail else "The API key was rejected."
                }
                response.code in 200..299 -> {
                    val models = ModelResponseParser.parse(response.body)
                    if (models.isNotEmpty()) return@withContext ModelDiscoveryResult.Success(models, endpoint)
                    lastMessage = "The provider replied, but its model list was empty or unsupported."
                }
                response.code > 0 && response.code != 404 -> lastMessage = friendlyHttpError(response.code, response.body)
                response.error != null -> lastMessage = response.error
            }
        }
        ModelDiscoveryResult.Failure(if (authError) lastMessage else lastMessage)
    }

    suspend fun validate(
        baseUrl: String,
        model: String,
        apiKey: String,
        protocol: ProviderProtocol,
        discoveredModels: List<DiscoveredModel>,
    ): ConnectionValidation = withContext(Dispatchers.IO) {
        val cleanBaseUrl = normalizeBaseUrl(baseUrl)
        val cleanModel = model.trim()
        val cleanKey = apiKey.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
        if (cleanBaseUrl.isBlank() || cleanModel.isBlank() || cleanKey.isBlank()) {
            return@withContext ConnectionValidation.Failure("Base URL, model, and API key are required.")
        }

        // 1. Try primary protocol candidates
        val primaryResult = probeProtocol(cleanBaseUrl, cleanModel, cleanKey, protocol)
        if (primaryResult is ConnectionValidation.Success) {
            return@withContext primaryResult
        }

        // 2. For custom providers, if primary protocol fails (even with 401/404), try the alternate protocol
        val isCustom = protocol == ProviderProtocol.ANTHROPIC_GATEWAY || protocol == ProviderProtocol.OPENAI_CHAT
        if (isCustom) {
            val altProtocol = if (protocol == ProviderProtocol.OPENAI_CHAT) ProviderProtocol.ANTHROPIC_GATEWAY else ProviderProtocol.OPENAI_CHAT
            val altResult = probeProtocol(cleanBaseUrl, cleanModel, cleanKey, altProtocol)
            if (altResult is ConnectionValidation.Success) {
                val formatName = if (altProtocol == ProviderProtocol.OPENAI_CHAT) "OpenAI" else "Anthropic"
                return@withContext ConnectionValidation.Success(
                    "Connection successful ($formatName format detected). Settings are ready."
                )
            }
        }

        return@withContext primaryResult
    }

    private fun probeProtocol(
        baseUrl: String,
        model: String,
        apiKey: String,
        protocol: ProviderProtocol,
    ): ConnectionValidation {
        val body = validationBody(model, protocol)
        var authRejected = false
        var lastCode = 0
        var lastBody = ""
        var lastError: String? = null
        var modelRejected = false

        for (endpoint in messagesEndpointCandidates(baseUrl, protocol)) {
            val response = request(endpoint, "POST", apiKey, body, protocol, connectTimeoutMs = 8_000, readTimeoutMs = 10_000)
            when {
                response.code in 200..299 -> return ConnectionValidation.Success(
                    if (protocol == ProviderProtocol.ANTHROPIC || protocol == ProviderProtocol.ANTHROPIC_GATEWAY || protocol == ProviderProtocol.OPENROUTER) {
                        "Anthropic Messages endpoint verified. Claude Code settings are ready."
                    } else {
                        "Connection successful. Claude Code settings are ready."
                    },
                )
                response.code == 401 || response.code == 403 -> {
                    authRejected = true
                    lastCode = response.code
                    lastBody = response.body
                }
                response.code == 400 && response.body.contains("model", ignoreCase = true) -> modelRejected = true
                response.code > 0 -> {
                    lastCode = response.code
                    lastBody = response.body
                }
                response.error != null -> lastError = response.error
            }
        }

        return when {
            authRejected -> {
                val detail = friendlyHttpError(if (lastCode > 0) lastCode else 401, lastBody)
                ConnectionValidation.Failure(detail)
            }
            modelRejected -> ConnectionValidation.Failure("The provider did not accept model '$model'. Choose a listed model or check its exact name.")
            lastCode == 404 -> ConnectionValidation.Failure("The API endpoint was not found at $baseUrl. Check the base URL.")
            lastCode > 0 -> ConnectionValidation.Failure(friendlyHttpError(lastCode, lastBody))
            lastError?.contains("timed out", ignoreCase = true) == true ->
                ConnectionValidation.Failure("Connection timed out after 10 seconds.")
            else -> ConnectionValidation.Failure(lastError ?: "Could not connect to the provider.")
        }
    }

    private fun request(
        endpoint: String,
        method: String,
        apiKey: String,
        body: String? = null,
        protocol: ProviderProtocol,
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
    ): HttpResult {
        return runCatching {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("User-Agent", "MobileHarness/1.0 (Android)")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                if (protocol != ProviderProtocol.OPENROUTER && protocol != ProviderProtocol.OPENAI_CHAT && protocol != ProviderProtocol.OPENAI_RESPONSES) {
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", "2023-06-01")
                }
                if (body != null) doOutput = true
            }
            if (body != null) connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            HttpResult(code, responseBody)
        }.getOrElse { HttpResult(0, "", it.message ?: "Network connection failed",) }
    }

    private fun normalizeBaseUrl(raw: String): String {
        var base = raw.trim().trimEnd('/')
        val suffixes = listOf(
            "/chat/completions",
            "/chat",
            "/completions",
            "/messages",
            "/responses",
        )
        for (suffix in suffixes) {
            if (base.endsWith(suffix, ignoreCase = true)) {
                base = base.substring(0, base.length - suffix.length).trimEnd('/')
                break
            }
        }
        return base
    }

    private fun modelEndpoints(baseUrl: String, protocol: ProviderProtocol): List<String> {
        val base = normalizeBaseUrl(baseUrl)
        val withoutAnthropic = base.removeSuffix("/anthropic")
        val candidates = when (protocol) {
            ProviderProtocol.OPENROUTER -> listOf("$base/v1/models")
            ProviderProtocol.OPENAI_CHAT, ProviderProtocol.OPENAI_RESPONSES -> buildList {
                add("$base/models")
                if (!base.endsWith("/v1")) add("$base/v1/models")
            }
            else -> listOf("$base/v1/models", "$base/models", "$withoutAnthropic/models", "$withoutAnthropic/v1/models")
        }
        return candidates.distinct()
    }

    /**
     * Candidate chat endpoints for validation, ordered from most to least
     * likely. Mirrors modelEndpoints() so providers whose base URL already
     * contains /v1 or /anthropic are probed without a doubled path segment.
     */
    private fun messagesEndpointCandidates(baseUrl: String, protocol: ProviderProtocol): List<String> {
        val base = normalizeBaseUrl(baseUrl)
        return when (protocol) {
            ProviderProtocol.OPENROUTER -> buildList {
                if (base.endsWith("/v1")) {
                    add("$base/messages")
                } else {
                    add("$base/v1/messages")
                    add("$base/messages")
                }
            }.distinct()
            ProviderProtocol.OPENAI_CHAT -> buildList {
                if (base.endsWith("/v1")) {
                    add("$base/chat/completions")
                } else {
                    add("$base/v1/chat/completions")
                    add("$base/chat/completions")
                }
            }.distinct()
            ProviderProtocol.OPENAI_RESPONSES -> buildList {
                if (base.endsWith("/v1")) {
                    add("$base/responses")
                } else {
                    add("$base/v1/responses")
                    add("$base/responses")
                }
            }.distinct()
            else -> {
                val withoutAnthropic = base.removeSuffix("/anthropic")
                buildList {
                    if (base.endsWith("/v1")) {
                        add("$base/messages")
                    } else {
                        add("$base/v1/messages")
                        add("$base/messages")
                    }
                    if (withoutAnthropic != base) {
                        if (withoutAnthropic.endsWith("/v1")) {
                            add("$withoutAnthropic/messages")
                        } else {
                            add("$withoutAnthropic/v1/messages")
                            add("$withoutAnthropic/messages")
                        }
                    }
                }.distinct()
            }
        }
    }

    private fun validationBody(model: String, protocol: ProviderProtocol): String = when (protocol) {
        ProviderProtocol.OPENAI_RESPONSES -> JSONObject()
            .put("model", model)
            .put("max_output_tokens", 16)
            .put("input", "Reply OK")
            .toString()
        ProviderProtocol.OPENAI_CHAT -> JSONObject()
            .put("model", model)
            .put("max_tokens", 16)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK")))
            .toString()
        else -> JSONObject()
            .put("model", model)
            .put("max_tokens", 16)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK")))
            .toString()
    }

    private fun friendlyHttpError(code: Int, body: String = ""): String {
        val detail = runCatching {
            val root = JSONObject(body)
            val errObj = root.optJSONObject("error")
            when {
                errObj != null -> errObj.optString("message").ifBlank { errObj.optString("detail") }
                else -> root.optString("message").ifBlank { root.optString("detail") }
            }
        }.getOrNull().orEmpty().ifBlank { if (body.startsWith("{")) "" else body.take(200) }
        val base = when (code) {
            401 -> "The API key was rejected by the provider."
            403 -> "Access forbidden (HTTP 403). Check API key permissions or gateway rules."
            429 -> "The provider rate limit was reached. Wait a moment and try again."
            in 500..599 -> "The provider is temporarily unavailable (HTTP $code)."
            else -> "The provider returned HTTP $code. Check the URL and account access."
        }
        return if (detail.isBlank()) base else "$base Detail: $detail"
    }

    private data class HttpResult(val code: Int, val body: String, val error: String? = null)
}

object ModelResponseParser {
    fun parse(json: String): List<DiscoveredModel> = runCatching {
        val trimmed = json.trim()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            }
        }
        buildList {
            for (index in 0 until array.length()) {
                when (val item = array.opt(index)) {
                    is String -> add(DiscoveredModel(item))
                    is JSONObject -> {
                        val id = item.optString("id").ifBlank { item.optString("name") }
                        if (id.isNotBlank()) {
                            val label = item.optString("display_name").ifBlank { item.optString("displayName") }.ifBlank { id }
                            val pricing = item.optJSONObject("pricing")
                            val free = id.endsWith(":free", ignoreCase = true) || pricing?.let {
                                listOf("prompt", "completion", "request").all { field ->
                                    it.optString(field, "0").toDoubleOrNull() == 0.0
                                }
                            } == true
                            add(DiscoveredModel(id, label, free))
                        }
                    }
                }
            }
        }.distinctBy { it.id }.sortedWith(compareByDescending<DiscoveredModel> { it.isFree }.thenBy { it.displayName.lowercase() })
    }.getOrDefault(emptyList())
}
