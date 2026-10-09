package com.jarves.mh.network

import android.content.Context
import com.jarves.mh.R
import com.jarves.mh.ui.AppStrings
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

data class GitHubAccount(val login: String, val avatarUrl: String)

data class GitHubRepository(
    val fullName: String,
    val cloneUrl: String,
    val private: Boolean,
    val defaultBranch: String,
    val description: String,
    val updatedAt: String,
)

sealed interface GitHubTokenPoll {
    data class Success(val accessToken: String) : GitHubTokenPoll
    data class Pending(val slowDown: Boolean = false) : GitHubTokenPoll
    data class Failure(val message: String) : GitHubTokenPoll
}

/** [context] is optional; when present, user-facing errors use the language picked in the app. */
class GitHubClient(private val context: Context? = null) {
    private fun text(@androidx.annotation.StringRes id: Int, fallback: String, vararg args: Any): String =
        context?.let { AppStrings.get(it, id, *args) } ?: fallback

    fun requestDeviceCode(clientId: String): GitHubDeviceCode {
        val json = postForm(
            "https://github.com/login/device/code",
            mapOf("client_id" to clientId),
        )
        return GitHubDeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.optString("verification_uri", "https://github.com/login/device"),
            expiresInSeconds = json.optLong("expires_in", 900L),
            intervalSeconds = json.optLong("interval", 5L).coerceAtLeast(5L),
        )
    }

    fun pollDeviceToken(clientId: String, deviceCode: String): GitHubTokenPoll {
        val json = postForm(
            "https://github.com/login/oauth/access_token",
            mapOf(
                "client_id" to clientId,
                "device_code" to deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
        )
        json.optString("access_token").takeIf(String::isNotBlank)?.let { return GitHubTokenPoll.Success(it) }
        return when (val error = json.optString("error")) {
            "authorization_pending" -> GitHubTokenPoll.Pending()
            "slow_down" -> GitHubTokenPoll.Pending(slowDown = true)
            "access_denied" -> GitHubTokenPoll.Failure(text(R.string.rt_gh_cancelled, "GitHub authorization was cancelled"))
            "expired_token" -> GitHubTokenPoll.Failure(text(R.string.rt_gh_code_expired, "The GitHub sign-in code expired. Try again."))
            else -> GitHubTokenPoll.Failure(json.optString("error_description").ifBlank { error.ifBlank { text(R.string.rt_gh_sign_in_failed, "GitHub sign-in failed") } })
        }
    }

    fun account(token: String): GitHubAccount {
        val json = getJson("https://api.github.com/user", token) as JSONObject
        return GitHubAccount(json.getString("login"), json.optString("avatar_url"))
    }

    fun repositories(token: String): List<GitHubRepository> {
        val result = LinkedHashMap<String, GitHubRepository>()
        var page = 1
        while (page <= 10) {
            val array = getJson(
                "https://api.github.com/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=100&page=$page",
                token,
            ) as JSONArray
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val repo = GitHubRepository(
                    fullName = item.getString("full_name"),
                    cloneUrl = item.getString("clone_url"),
                    private = item.optBoolean("private"),
                    defaultBranch = item.optString("default_branch", "main"),
                    description = item.optString("description"),
                    updatedAt = item.optString("updated_at"),
                )
                result[repo.fullName] = repo
            }
            if (array.length() < 100) break
            page++
        }
        return result.values.toList()
    }

    private fun postForm(endpoint: String, values: Map<String, String>): JSONObject {
        val body = values.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}" 
        }.toByteArray()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "PocketDev-Android")
        }
        connection.outputStream.use { it.write(body) }
        return readResponse(connection) as JSONObject
    }

    private fun getJson(endpoint: String, token: String): Any {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            setRequestProperty("User-Agent", "PocketDev-Android")
        }
        return readResponse(connection)
    }

    private fun readResponse(connection: HttpURLConnection): Any {
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
            error(message?.takeIf(String::isNotBlank) ?: text(R.string.rt_gh_http_error, "GitHub returned HTTP $status", status))
        }
        return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
    }
}
