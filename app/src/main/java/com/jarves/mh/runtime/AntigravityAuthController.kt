package com.jarves.mh.runtime

import android.content.Context
import com.jarves.mh.R
import com.jarves.mh.ui.AppStrings
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Drives only agy's official interactive OAuth flow; it never reads the resulting credentials. */
class AntigravityAuthController(
    private val context: Context,
    initiallySignedIn: Boolean,
    initialAccountEmail: String,
    private val onSignedInChanged: (Boolean, String?) -> Unit,
) {
    private val installer = RuntimeInstaller(context)
    private val mutableState = MutableStateFlow(
        AntigravityAuthState(
            status = if (initiallySignedIn) AntigravityAuthStatus.SIGNED_IN else AntigravityAuthStatus.SIGNED_OUT,
            message = initialAccountEmail.takeIf(String::isNotBlank)?.let { AppStrings.get(context, R.string.rt_agy_connected_as, it) },
            accountEmail = initialAccountEmail.takeIf(String::isNotBlank),
        ),
    )
    val state: StateFlow<AntigravityAuthState> = mutableState.asStateFlow()
    private fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String = AppStrings.get(context, id, *args)
    private val authOutput = File(context.cacheDir, "antigravity-auth-output.log")
    private val logoutOutput = File(context.cacheDir, "antigravity-logout-output.log")
    @Volatile private var process: Process? = null
    @Volatile private var codeSubmitted = false

    init {
        // Remove output left by an app/process crash before starting a new OAuth flow.
        authOutput.delete()
        logoutOutput.delete()
    }

    private fun officialCredentialFile() = File(
        context.filesDir,
        "runtime/ubuntu/root/.gemini/antigravity-cli/antigravity-oauth-token",
    )

    fun hasOfficialCredential(): Boolean = officialCredentialFile().isFile

    suspend fun beginLogin() = withContext(Dispatchers.IO) {
        if (process?.isAlive == true) return@withContext
        if (!installer.isAgentInstalled(com.jarves.mh.model.AgentKind.ANTIGRAVITY)) {
            mutableState.value = AntigravityAuthState(
                AntigravityAuthStatus.ERROR,
                message = str(R.string.rt_agy_install_first),
            )
            return@withContext
        }
        mutableState.value = AntigravityAuthState(AntigravityAuthStatus.STARTING, message = str(R.string.rt_agy_starting))
        codeSubmitted = false
        authOutput.delete()
        val runtime = installer.installedRuntime()
        val workspace = java.io.File(context.filesDir, "workspaces/antigravity-auth").apply { mkdirs() }
        val running = installer.process(
            runtime.proot,
            runtime.rootfs,
            workspace,
            // SSH selects agy's official manual browser URL + one-time code flow.
            // NativeSpawn supplies a real PTY; PocketDev remains only the terminal.
            mapOf(
                "SSH_CONNECTION" to "127.0.0.1 1 127.0.0.1 1",
                "TERM" to "xterm-256color",
                "NO_COLOR" to "1",
            ),
            listOf(RuntimeInstaller.AGY_GUEST_PATH),
            guestWorkspacePath = "/workspace/antigravity-auth",
            emulateHardLinks = false,
            outputFile = authOutput,
            pseudoTerminal = true,
            ptyRows = 40,
            ptyColumns = 120,
        )
        process = running
        val native = running as? NativeSpawnProcess ?: error("Unsupported Antigravity authentication process")
        var offset = 0L
        val output = StringBuilder()
        var handshakeReplies = 0
        var lastHandshakeReplyLength = 0
        var loginMenuAdvanced = false
        var colorScreenCompleted = false
        var renderingScreenCompleted = false
        var privacyScreenCompleted = false
        var workspaceTrustCompleted = false
        try {
            while (running.isAlive || native.outputFile.length() > offset) {
                val available = native.outputFile.length() - offset
                if (available <= 0) {
                    delay(80)
                    continue
                }
                val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                val count = RandomAccessFile(native.outputFile, "r").use { file ->
                    file.seek(offset)
                    file.read(bytes)
                }
                if (count <= 0) continue
                offset += count
                output.append(bytes.decodeToString(0, count))
                val clean = sanitizeTerminalOutput(output.toString()).takeLast(40_000)
                // Antigravity's renderer asks a real terminal for DEC mode and
                // Kitty keyboard-protocol status before it paints its UI, and it
                // may ask again on every new screen. NativeSpawn is pipe-backed,
                // so answer the standard queries ourselves; `script` forwards
                // this to agy's PTY unchanged.
                if (handshakeReplies < 5 &&
                    output.length - lastHandshakeReplyLength > 200 &&
                    output.substring(lastHandshakeReplyLength).contains("\u001B[?u")
                ) {
                    running.outputStream.write(TERMINAL_HANDSHAKE_REPLY.toByteArray())
                    running.outputStream.flush()
                    handshakeReplies++
                    lastHandshakeReplyLength = output.length
                }
                if (handshakeReplies > 0 &&
                    !loginMenuAdvanced &&
                    clean.contains("Select login method", true) &&
                    clean.contains("> 1. Google OAuth", true) &&
                    extractGoogleOAuthUrl(clean) == null
                ) {
                    // agy's TUI (Ink) runs the PTY in raw mode. In raw mode
                    // the kernel does not translate CR to LF, so we must send
                    // the byte Ink treats as the Enter key (0x0A) explicitly.
                    // A lone CR is mapped to the arrow-right key and only
                    // re-paints the menu, while a real terminal transmits
                    // CR LF on Enter, which Ink accepts even with raw input.
                    running.outputStream.write("\r\n".toByteArray())
                    running.outputStream.flush()
                    loginMenuAdvanced = true
                    if (mutableState.value.status == AntigravityAuthStatus.STARTING) {
                        mutableState.value = mutableState.value.copy(
                            message = str(R.string.rt_agy_oauth_selected),
                        )
                    }
                }
                val url = extractGoogleOAuthUrl(clean)
                if (url != null && mutableState.value.authorizationUrl == null) {
                    mutableState.value = AntigravityAuthState(
                        AntigravityAuthStatus.AWAITING_CODE,
                        authorizationUrl = url,
                        message = str(R.string.rt_agy_finish_sign_in),
                    )
                }
                if (isSignedInScreen(clean)) {
                    val email = extractSignedInEmail(clean)
                    onSignedInChanged(true, email)
                    mutableState.value = AntigravityAuthState(
                        AntigravityAuthStatus.SIGNED_IN,
                        message = email?.let { str(R.string.rt_agy_connected_as, it) } ?: str(R.string.rt_agy_google_connected),
                        accountEmail = email,
                    )
                    // Leave the official CLI cleanly so it has a chance to flush
                    // its own credential/session state before PocketDev closes
                    // the temporary terminal. PocketDev never reads that state.
                    runCatching {
                        running.outputStream.write("/quit\r\n".toByteArray())
                        running.outputStream.flush()
                    }
                    repeat(20) {
                        if (!running.isAlive) return@repeat
                        delay(50)
                    }
                    if (running.isAlive) running.destroy()
                    break
                }
                if (!colorScreenCompleted && clean.contains("Choose your color scheme", true)) {
                    // Keep the official terminal color scheme selected by default.
                    running.outputStream.write("\r\n".toByteArray())
                    running.outputStream.flush()
                    colorScreenCompleted = true
                }
                if (!renderingScreenCompleted &&
                    clean.contains("no flickering (altscreen)", true) &&
                    clean.contains("Native Terminal experience (inline)", true)
                ) {
                    // Select inline rendering, which is the appropriate mode for
                    // PocketDev's captured PTY output.
                    running.outputStream.write("\u001B[B\r\n".toByteArray())
                    running.outputStream.flush()
                    renderingScreenCompleted = true
                }
                if (!privacyScreenCompleted &&
                    clean.contains("Terms of Service & Data Use", true) &&
                    clean.contains("help improve Antigravity CLI", true)
                ) {
                    // Optional interaction-data collection is selected by default.
                    // PocketDev uses the privacy-preserving choice: Space clears
                    // the checkbox, then two Tabs focus Done and Enter confirms.
                    val optOutAndFinish = if (clean.contains("[x] Yes", true)) {
                        " \t\t\r\n"
                    } else {
                        "\t\t\r\n"
                    }
                    running.outputStream.write(optOutAndFinish.toByteArray())
                    running.outputStream.flush()
                    privacyScreenCompleted = true
                    mutableState.value = mutableState.value.copy(
                        message = str(R.string.rt_agy_finishing_setup),
                    )
                }
                if (!workspaceTrustCompleted &&
                    clean.contains("Do you trust the contents of this project", true) &&
                    clean.contains("Yes, I trust this folder", true)
                ) {
                    // The authentication workspace is created and owned privately
                    // by PocketDev and contains no user project files.
                    running.outputStream.write("\r\n".toByteArray())
                    running.outputStream.flush()
                    workspaceTrustCompleted = true
                }
                if (clean.contains("authentication failed", true) || clean.contains("failed to exchange", true)) {
                    error(str(R.string.rt_agy_auth_failed))
                }
            }
            if (!codeSubmitted && mutableState.value.status == AntigravityAuthStatus.STARTING) {
                val exit = running.waitFor()
                error(str(R.string.rt_agy_closed_early, exit))
            }
        } catch (error: Throwable) {
            if (mutableState.value.status != AntigravityAuthStatus.SIGNED_IN) {
                mutableState.value = AntigravityAuthState(
                    AntigravityAuthStatus.ERROR,
                    message = error.message?.take(240) ?: str(R.string.rt_agy_sign_in_failed),
                )
            }
        } finally {
            if (running.isAlive) running.destroy()
            runCatching { running.outputStream.close() }
            native.outputFile.delete() // OAuth terminal output is intentionally ephemeral.
            process = null
            codeSubmitted = false
        }
    }

    fun submitCode(code: String) {
        val value = code.trim()
        require(value.isNotBlank()) { str(R.string.rt_agy_paste_code) }
        val running = process ?: error(str(R.string.rt_agy_start_again))
        check(running.isAlive) { str(R.string.rt_agy_session_expired) }
        // agy's interactive editor runs the PTY in raw mode and treats CR+LF as
        // the Enter key. LF alone inserts/repaints a line without submitting it.
        running.outputStream.write((value + "\r\n").toByteArray())
        running.outputStream.flush()
        codeSubmitted = true
        mutableState.value = mutableState.value.copy(
            status = AntigravityAuthStatus.COMPLETING,
            message = str(R.string.rt_agy_completing),
        )
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        process?.destroy()
        val previousEmail = mutableState.value.accountEmail
        mutableState.value = AntigravityAuthState(
            status = AntigravityAuthStatus.STARTING,
            message = str(R.string.rt_agy_signing_out),
            accountEmail = previousEmail,
        )
        try {
            // Under Android PRoot agy deliberately uses this file instead of a
            // Linux Secret Service keyring. Deleting this exact app-private file
            // is the deterministic equivalent of agy's /logout; its contents are
            // never read, copied, or logged by PocketDev.
            val credential = officialCredentialFile()
            if (credential.exists()) {
                check(credential.delete()) {
                    str(R.string.rt_agy_remove_credential_failed)
                }
            }
            check(!hasOfficialCredential()) { str(R.string.rt_agy_logout_incomplete) }
            onSignedInChanged(false, null)
            mutableState.value = AntigravityAuthState(AntigravityAuthStatus.SIGNED_OUT, message = str(R.string.rt_agy_signed_out))
        } catch (error: Throwable) {
            mutableState.value = AntigravityAuthState(
                status = AntigravityAuthStatus.SIGNED_IN,
                message = error.message?.take(240) ?: str(R.string.rt_agy_logout_failed),
                accountEmail = previousEmail,
            )
            throw error
        } finally {
            process = null
            logoutOutput.delete()
        }
    }

    fun cancel() {
        process?.destroy()
        mutableState.value = AntigravityAuthState(AntigravityAuthStatus.SIGNED_OUT)
    }

    fun invalidateSession(message: String) {
        onSignedInChanged(false, null)
        mutableState.value = AntigravityAuthState(AntigravityAuthStatus.ERROR, message = message)
    }
}

internal fun extractGoogleOAuthUrl(output: String): String? {
    val compact = output.replace(Regex("[\\r\\n\\t ]+"), "")
    // agy's Ink renderer wraps the URL across terminal rows. Removing that
    // whitespace reconstructs it, but the next rendered labels may then become
    // adjacent to the URL. The PKCE `state` value is the final parameter emitted
    // by agy, so terminate at its base64url-safe value instead of consuming TUI
    // copy such as "Copy and paste the URL".
    GOOGLE_OAUTH_URL.find(compact)?.value
        ?.takeIf { "client_id=" in it && "code_challenge=" in it }
        ?.let { return it }
    // Fallback for post-menu screens that print the browser URL in a different
    // shape (wrapped lines, shortened query display). Only apply once the login
    // menu has left the screen so help text cannot produce a false positive.
    if (!output.contains("Select login method", true) &&
        (output.contains("browser", true) || output.contains("visit", true) ||
            output.contains("open", true) || output.contains("code", true) ||
            output.contains("paste", true))
    ) {
        val candidates = Regex("https://[^\\s\"']{20,}")
            .findAll(compact)
            .map { it.value.trimEnd { char -> char !in URL_CHARACTERS } }
            .filter { it.length >= 30 && "." in it }
            .toList()
        return candidates.firstOrNull { "google" in it } ?: candidates.firstOrNull()
    }
    return null
}

private fun isSignedInScreen(output: String): Boolean =
    output.contains("for shortcuts", true) ||
        (output.contains("Antigravity CLI", true) && output.contains("Google AI", true))

private fun extractSignedInEmail(output: String): String? =
    Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
        .findAll(output)
        .map { it.value }
        .firstOrNull { !it.endsWith(".apps.googleusercontent.com", ignoreCase = true) }

private val URL_CHARACTERS = ("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" +
    "-._~:/?#[]@!$&'()*+,;=%").toSet()

private val GOOGLE_OAUTH_URL = Regex(
    "https://accounts\\.google\\.com/[^\\s\\\"'<>]*?[?&]state=[A-Za-z0-9._~-]+",
)

private const val TERMINAL_HANDSHAKE_REPLY = "\u001B[?2026;1\$y\u001B[?2027;1\$y\u001B[?1u\n"

private val AUTH_ANSI = Regex("\\u001B(?:\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)|\\[[0-?]*[ -/]*[@-~]|[()][A-Z0-9])")
private fun sanitizeTerminalOutput(text: String): String = text
    .replace(AUTH_ANSI, "")
    .filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }
