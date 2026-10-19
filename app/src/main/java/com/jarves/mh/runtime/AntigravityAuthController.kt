package com.jarves.mh.runtime

import android.content.Context
import com.jarves.mh.R
import com.jarves.mh.ui.AppStrings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Drives Google's official PKCE OAuth flow for Antigravity and manages the saved session. */
class AntigravityAuthController(
    private val context: Context,
    initiallySignedIn: Boolean,
    initialAccountEmail: String,
    private val onSignedInChanged: (Boolean, String?) -> Unit,
) {
    private val installer = RuntimeInstaller(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(
        AntigravityAuthState(
            status = if (initiallySignedIn) AntigravityAuthStatus.SIGNED_IN else AntigravityAuthStatus.SIGNED_OUT,
            message = initialAccountEmail.takeIf(String::isNotBlank)?.let { AppStrings.get(context, R.string.rt_agy_connected_as, it) },
            accountEmail = initialAccountEmail.takeIf(String::isNotBlank),
        ),
    )
    val state: StateFlow<AntigravityAuthState> = mutableState.asStateFlow()
    private fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String = AppStrings.get(context, id, *args)

    @Volatile private var currentCodeVerifier: String? = null

    init {
        if (hasOfficialCredential() && !initiallySignedIn) {
            val email = initialAccountEmail.takeIf(String::isNotBlank) ?: readEmailFromCredential()
            mutableState.value = AntigravityAuthState(
                status = AntigravityAuthStatus.SIGNED_IN,
                message = email?.let { AppStrings.get(context, R.string.rt_agy_connected_as, it) }
                    ?: AppStrings.get(context, R.string.rt_agy_google_connected),
                accountEmail = email,
            )
            onSignedInChanged(true, email)
        }
    }

    private fun officialCredentialFile() = File(
        context.filesDir,
        "runtime/ubuntu/root/.gemini/antigravity-cli/antigravity-oauth-token",
    )

    fun hasOfficialCredential(): Boolean = officialCredentialFile().let { it.isFile && it.length() > 0L }

    private fun readEmailFromCredential(): String? {
        val file = officialCredentialFile()
        if (!file.isFile || file.length() == 0L) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val idToken = json.optString("id_token")
            extractEmailFromJwt(idToken)
        }.getOrNull()
    }

    suspend fun beginLogin() = withContext(Dispatchers.IO) {
        if (!installer.isAgentInstalled(com.jarves.mh.model.AgentKind.ANTIGRAVITY)) {
            mutableState.value = AntigravityAuthState(
                AntigravityAuthStatus.ERROR,
                message = str(R.string.rt_agy_install_first),
            )
            return@withContext
        }

        mutableState.value = AntigravityAuthState(AntigravityAuthStatus.STARTING, message = str(R.string.rt_agy_starting))

        try {
            val verifier = generatePkceVerifier()
            val challenge = generatePkceChallenge(verifier)
            val stateVal = generateRandomState()
            currentCodeVerifier = verifier

            val params = listOf(
                "access_type" to "offline",
                "client_id" to GOOGLE_CLIENT_ID,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                "prompt" to "consent",
                "redirect_uri" to GOOGLE_REDIRECT_URI,
                "response_type" to "code",
                "scope" to GOOGLE_SCOPES,
                "state" to stateVal,
            )
            val authUrl = "https://accounts.google.com/o/oauth2/auth?" + params.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            }

            android.util.Log.i("AntigravityAuthController", "Generated Google OAuth URL: ${authUrl.take(60)}...")
            mutableState.value = AntigravityAuthState(
                status = AntigravityAuthStatus.AWAITING_CODE,
                authorizationUrl = authUrl,
                message = str(R.string.rt_agy_finish_sign_in),
            )
        } catch (e: Throwable) {
            android.util.Log.e("AntigravityAuthController", "Failed to start Google sign-in", e)
            mutableState.value = AntigravityAuthState(
                status = AntigravityAuthStatus.ERROR,
                message = e.message ?: str(R.string.rt_agy_sign_in_failed),
            )
        }
    }

    fun submitCode(code: String) {
        val value = code.trim()
        require(value.isNotBlank()) { str(R.string.rt_agy_paste_code) }
        val verifier = currentCodeVerifier ?: error(str(R.string.rt_agy_start_again))

        mutableState.value = mutableState.value.copy(
            status = AntigravityAuthStatus.COMPLETING,
            message = str(R.string.rt_agy_completing),
        )

        scope.launch {
            try {
                val tokenResponse = exchangeAuthorizationCode(value, verifier)
                val email = extractEmailFromToken(tokenResponse)
                saveOfficialCredential(tokenResponse)
                currentCodeVerifier = null

                withContext(Dispatchers.Main) {
                    onSignedInChanged(true, email)
                    mutableState.value = AntigravityAuthState(
                        status = AntigravityAuthStatus.SIGNED_IN,
                        message = email?.let { str(R.string.rt_agy_connected_as, it) } ?: str(R.string.rt_agy_google_connected),
                        accountEmail = email,
                    )
                }
            } catch (e: Throwable) {
                android.util.Log.e("AntigravityAuthController", "Token exchange failed", e)
                withContext(Dispatchers.Main) {
                    mutableState.value = AntigravityAuthState(
                        status = AntigravityAuthStatus.ERROR,
                        message = e.message ?: str(R.string.rt_agy_auth_failed),
                    )
                }
            }
        }
    }

    private fun exchangeAuthorizationCode(code: String, verifier: String): JSONObject {
        val url = URL(GOOGLE_TOKEN_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val postBody = listOf(
            "client_id" to GOOGLE_CLIENT_ID,
            "client_secret" to GOOGLE_CLIENT_SECRET,
            "code" to code,
            "code_verifier" to verifier,
            "grant_type" to "authorization_code",
            "redirect_uri" to GOOGLE_REDIRECT_URI,
        ).joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
        conn.outputStream.use { it.write(postBody.toByteArray(Charsets.UTF_8)) }
        val responseCode = conn.responseCode
        val responseStream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        val responseText = responseStream.bufferedReader().use { it.readText() }
        if (responseCode !in 200..299) {
            val errorJson = runCatching { JSONObject(responseText) }.getOrNull()
            val desc = errorJson?.optString("error_description")?.takeIf { it.isNotBlank() }
                ?: errorJson?.optString("error")?.takeIf { it.isNotBlank() }
                ?: "HTTP $responseCode"
            throw IllegalStateException("Google token exchange failed: $desc")
        }
        return JSONObject(responseText)
    }

    private fun saveOfficialCredential(tokenJson: JSONObject) {
        val accessToken = tokenJson.getString("access_token")
        val refreshToken = tokenJson.optString("refresh_token")
        val expiresIn = tokenJson.optLong("expires_in", 3600L)
        val idToken = tokenJson.optString("id_token")
        val expiryTime = Instant.now().plusSeconds(expiresIn).toString()

        val credentialJson = JSONObject().apply {
            put("token", JSONObject().apply {
                put("access_token", accessToken)
                put("token_type", "Bearer")
                put("refresh_token", refreshToken)
                put("expiry", expiryTime)
            })
            put("auth_method", "consumer")
            put("id_token", idToken)
        }

        val target = officialCredentialFile()
        target.parentFile?.mkdirs()
        target.writeText(credentialJson.toString(2))
        runCatching {
            target.setReadable(true, true)
            target.setWritable(true, true)
        }
    }

    private fun extractEmailFromToken(tokenJson: JSONObject): String? {
        val idToken = tokenJson.optString("id_token")
        extractEmailFromJwt(idToken)?.let { return it }

        // Fallback: Google userinfo endpoint
        val accessToken = tokenJson.optString("access_token")
        if (accessToken.isNotBlank()) {
            runCatching {
                val conn = (URL("https://www.googleapis.com/oauth2/v3/userinfo").openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
                if (conn.responseCode == 200) {
                    val info = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val email = info.optString("email")
                    if (email.isNotBlank()) return email
                }
            }
        }
        return null
    }

    private fun extractEmailFromJwt(idToken: String?): String? {
        if (idToken.isNullOrBlank()) return null
        return runCatching {
            val parts = idToken.split(".")
            if (parts.size >= 2) {
                val pad = parts[1].length % 4
                val padded = parts[1] + "=".repeat((4 - pad) % 4)
                val decoded = String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
                val jwtPayload = JSONObject(decoded)
                val email = jwtPayload.optString("email")
                email.takeIf { it.isNotBlank() }
            } else null
        }.getOrNull()
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val previousEmail = mutableState.value.accountEmail
        mutableState.value = AntigravityAuthState(
            status = AntigravityAuthStatus.STARTING,
            message = str(R.string.rt_agy_signing_out),
            accountEmail = previousEmail,
        )
        try {
            val credential = officialCredentialFile()
            if (credential.exists()) {
                check(credential.delete()) {
                    str(R.string.rt_agy_remove_credential_failed)
                }
            }
            check(!hasOfficialCredential()) { str(R.string.rt_agy_logout_incomplete) }
            currentCodeVerifier = null
            withContext(Dispatchers.Main) {
                onSignedInChanged(false, null)
                mutableState.value = AntigravityAuthState(AntigravityAuthStatus.SIGNED_OUT, message = str(R.string.rt_agy_signed_out))
            }
        } catch (error: Throwable) {
            withContext(Dispatchers.Main) {
                mutableState.value = AntigravityAuthState(
                    status = AntigravityAuthStatus.SIGNED_IN,
                    message = error.message?.take(240) ?: str(R.string.rt_agy_logout_failed),
                    accountEmail = previousEmail,
                )
            }
            throw error
        }
    }

    fun cancel() {
        currentCodeVerifier = null
        mutableState.value = AntigravityAuthState(AntigravityAuthStatus.SIGNED_OUT)
    }

    fun invalidateSession(message: String) {
        currentCodeVerifier = null
        onSignedInChanged(false, null)
        mutableState.value = AntigravityAuthState(AntigravityAuthStatus.ERROR, message = message)
    }

    private fun generatePkceVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generatePkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    private fun generateRandomState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private fun xorDecode(bytes: ByteArray, key: Byte = 0x5A): String {
            val decoded = ByteArray(bytes.size) { i -> (bytes[i].toInt() xor key.toInt()).toByte() }
            return String(decoded, Charsets.UTF_8)
        }

        val GOOGLE_CLIENT_ID: String by lazy {
            xorDecode(byteArrayOf(107, 106, 109, 107, 106, 106, 108, 106, 108, 106, 111, 99, 107, 119, 46, 55, 50, 41, 41, 51, 52, 104, 50, 104, 107, 54, 57, 40, 63, 104, 105, 111, 44, 46, 53, 54, 53, 48, 50, 110, 61, 110, 106, 105, 63, 42, 116, 59, 42, 42, 41, 116, 61, 53, 53, 61, 54, 63, 47, 41, 63, 40, 57, 53, 52, 46, 63, 52, 46, 116, 57, 53, 55))
        }
        val GOOGLE_CLIENT_SECRET: String by lazy {
            xorDecode(byteArrayOf(29, 21, 25, 9, 10, 2, 119, 17, 111, 98, 28, 13, 8, 110, 98, 108, 22, 62, 22, 16, 107, 55, 22, 24, 98, 41, 2, 25, 110, 32, 108, 43, 30, 27, 60))
        }
        const val GOOGLE_REDIRECT_URI = "https://antigravity.google/oauth-callback"
        const val GOOGLE_SCOPES = "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile https://www.googleapis.com/auth/cclog https://www.googleapis.com/auth/experimentsandconfigs https://www.googleapis.com/auth/aicode openid"
        const val GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
    }
}

internal fun extractOsc8GoogleOAuthUrl(output: String): String? {
    val match = OSC8_OAUTH_URL.find(output) ?: return null
    val candidate = match.groupValues[1].trimEnd { it !in URL_CHARACTERS }
    return candidate.takeIf {
        it.contains("client_id=", ignoreCase = true) &&
            (it.contains("redirect_uri=", ignoreCase = true) || it.contains("response_type=", ignoreCase = true))
    }
}

internal fun extractGoogleOAuthUrl(output: String): String? {
    // Check OSC 8 escape sequence first if called directly with raw terminal output
    extractOsc8GoogleOAuthUrl(output)?.let { return it }

    val compact = output.replace(Regex("[\\r\\n\\t ]+"), "")

    // 1. Primary match: Google OAuth authorization URL up to terminal formatting / box chars
    val oauthPattern = Regex("https://accounts\\.google\\.com/o/oauth2/[^\\s\"'<>│─→]+")
    val match = oauthPattern.find(compact)
    if (match != null) {
        val candidate = match.value.trimEnd { char -> char !in URL_CHARACTERS }
        if (candidate.contains("client_id=", ignoreCase = true) &&
            (candidate.contains("redirect_uri=", ignoreCase = true) ||
                candidate.contains("response_type=", ignoreCase = true) ||
                candidate.contains("code_challenge=", ignoreCase = true))
        ) {
            return candidate
        }
    }

    // 2. PKCE match targeting state parameter
    GOOGLE_OAUTH_URL.find(compact)?.value
        ?.takeIf { "client_id=" in it && ("code_challenge=" in it || "redirect_uri=" in it) }
        ?.let { return it }

    // 3. Fallback for any Google Accounts URL with auth or code
    val candidates = Regex("https://accounts\\.google\\.com/[^\\s\"'<>│─→]{20,}")
        .findAll(compact)
        .map { it.value.trimEnd { char -> char !in URL_CHARACTERS } }
        .filter {
            (it.contains("oauth", ignoreCase = true) || it.contains("auth", ignoreCase = true)) &&
                it.contains("client_id=", ignoreCase = true) &&
                (it.contains("redirect_uri=", ignoreCase = true) || it.contains("response_type=", ignoreCase = true))
        }
        .toList()
    if (candidates.isNotEmpty()) {
        return candidates.first()
    }

    // 4. Fallback for post-menu screens or generic browser prompts
    if (output.contains("browser", true) || output.contains("visit", true) ||
        output.contains("open", true) || output.contains("code", true) ||
        output.contains("paste", true) || output.contains("authenticate", true)
    ) {
        val generic = Regex("https://[^\\s\"'<>│─→]{20,}")
            .findAll(compact)
            .map { it.value.trimEnd { char -> char !in URL_CHARACTERS } }
            .filter { it.length >= 30 && "." in it && "google" in it && "client_id=" in it }
            .firstOrNull()
        if (generic != null) return generic
    }
    return null
}

private val URL_CHARACTERS = ("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" +
    "-._~:/?#[]@!$&'()*+,;=%").toSet()

private val GOOGLE_OAUTH_URL = Regex(
    "https://accounts\\.google\\.com/[^\\s\\\"'<>]*?[?&]state=[A-Za-z0-9._~-]+",
)

private val OSC8_OAUTH_URL = Regex(
    """\u001B\]8;[^;]*;(https://accounts\.google\.com/o/oauth2/[^\u0007\u001B]+)(?:\u0007|\u001B\\)""",
)

private val AUTH_ANSI = Regex("\\u001B(?:\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)|\\[[0-?]*[ -/]*[@-~]|[()][A-Z0-9])")
private fun sanitizeTerminalOutput(text: String): String = text
    .replace(AUTH_ANSI, "")
    .filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }
