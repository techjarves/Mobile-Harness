package com.jarves.mh.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import com.jarves.mh.BuildConfig
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import com.jarves.mh.model.DevStack
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.json.JSONObject

data class InstalledRuntime(
    val proot: File,
    val rootfs: File,
)

data class RuntimeInstallProgress(
    val message: String,
    val fraction: Float,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val terminalLine: String? = null,
    val indeterminate: Boolean = false,
    val event: RuntimeInstallEvent = RuntimeInstallEvent.STAGE,
)

data class AgentUpdateInfo(
    val installedVersion: String,
    val latestVersion: String,
)

enum class RuntimeInstallEvent { STAGE, COMMAND, OUTPUT, DOWNLOAD, COMMAND_COMPLETED, COMPLETED }

private data class RuntimeBundle(
    val label: String,
    val fileName: String,
    val sha256: String,
    val compressedBytes: Long,
)

class RuntimeInstaller(private val context: Context) {
    private val runtimeDir = File(context.filesDir, "runtime")
    private val rootfs = File(runtimeDir, "ubuntu")
    private val downloads = File(context.cacheDir, "runtime-downloads")
    private val coreReadyMarker = File(rootfs, ".pocket-runtime-ready")
    private val claudeMarker = File(rootfs, ".pocket-claude-version")
    // Read only for migration from Core bundles that embedded Claude Code.
    private val bundledClaudeMarker = File(rootfs, ".pocket-bundled-claude-version")
    private val rootfsMarker = File(rootfs, ".pocket-rootfs-version")
    private val languageToolsMarker = File(rootfs, ".pocket-language-tools-version")
    private val coreToolsMarker = File(rootfs, ".pocket-core-tools-version")
    private val systemUpgradeMarker = File(rootfs, ".pocket-system-upgrade-version")
    private val devStacksFile = File(rootfs, ".pocket-dev-stacks.json")
    private val dshMarker = File(rootfs, ".pocket-dsh-version")
    private val agyMarker = File(rootfs, ".pocket-agy-version")
    private val githubCliMarker = File(rootfs, ".pocket-github-cli-version")
    private val dshAndroidCompatibilityMarker = File(rootfs, ".pocket-dsh-android-compat-version")
    private val macosMetadataRepairMarker = File(rootfs, ".pocket-macos-metadata-repair")

    fun isInstalled(): Boolean {
        val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        // Devices set up before staged toolchains keep working through the legacy marker;
        // fresh installs require the new core-tools marker instead.
        val legacyLanguageTools = languageToolsMarker.readTextOrNull() == LANGUAGE_TOOLS_VERSION
        val coreToolsReady = File(rootfs, "usr/bin/git").exists() && isSupportedCoreToolsVersion()
        val ready = proot.canExecute() &&
            File(rootfs, "usr/bin/bash").exists() &&
            rootfsMarker.readTextOrNull() == ROOTFS_VERSION &&
            File(rootfs, "usr/local/bin/node").exists() &&
            (legacyLanguageTools || coreToolsReady) &&
            coreReadyMarker.exists()
        if (ready) repairLegacyMacosMetadata()
        return ready
    }

    /**
     * One-shot cleanup for devices that already extracted a tarball built on
     * macOS without the `--no-mac-metadata` flag. Such bundles contain
     * `._filename` AppleDouble metadata files that crash Python 3.8 when it
     * reads every `.pth` file in site-packages. After the first successful
     * cleanup we write a marker so we never walk the entire rootfs again.
     */
    private fun repairLegacyMacosMetadata() {
        if (macosMetadataRepairMarker.isFile) return
        if (!rootfs.isDirectory) return
        stripMacosMetadataArtifacts(rootfs)
        macosMetadataRepairMarker.parentFile?.mkdirs()
        macosMetadataRepairMarker.writeText("1")
    }

    /** Returns the already verified runtime without performing network or update checks. */
    fun installedRuntime(): InstalledRuntime {
        check(isInstalled()) { "Core runtime setup is incomplete. Reopen Mobile Harness to repair it." }
        return InstalledRuntime(
            proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so"),
            rootfs = rootfs,
        )
    }

    /** Removes only scaffolding written automatically by earlier PocketDev alpha builds. */
    fun cleanupLegacyWorkspaceScaffolding() {
        val workspaces = File(context.filesDir, "workspaces")
        workspaces.listFiles { file -> file.isDirectory }.orEmpty().forEach { workspace ->
            File(workspace, "README.md").deleteIfExact(LEGACY_README)
            File(workspace, "index.html").deleteIfExact(LEGACY_INDEX)

            listOf(
                File(workspace, ".claude/settings.json"),
                File(workspace, ".claude.json"),
            ).forEach { settings ->
                if (settings.isFile && settings.readTextOrNull()?.contains("/opt/pocket/permission-hook.sh") == true) {
                    settings.delete()
                }
            }
            File(workspace, ".claude").takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
        }
    }

    private fun File.deleteIfExact(expected: String) {
        if (isFile && runCatching { readText() }.getOrNull() == expected) delete()
    }

    suspend fun ensureInstalled(
        selectedStacks: Set<DevStack> = emptySet(),
        agent: com.jarves.mh.model.AgentKind = com.jarves.mh.model.AgentKind.CLAUDE_CODE,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): InstalledRuntime {
        require(android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "Pocket runtime requires an ARM64 device" }
        val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        require(proot.canExecute()) { "The embedded PRoot launcher is unavailable" }

        if (!File(rootfs, "usr/bin/bash").exists() || rootfsMarker.readTextOrNull() != ROOTFS_VERSION) {
            onProgress(RuntimeInstallProgress("Preparing the private development runtime", 0.03f))
            val archive = obtainRuntimeBundle(
                CORE_BUNDLE,
                preferEmbedded = BuildConfig.OFFLINE_RUNTIME_BUNDLES,
                from = 0.03f,
                to = 0.25f,
                onProgress,
            )
            onProgress(RuntimeInstallProgress("Verifying and unpacking the Core runtime", 0.28f))
            val staging = File(runtimeDir, "ubuntu.installing")
            staging.deleteRecursively()
            staging.mkdirs()
            extractZstdTar(archive, staging)
            stripMacosMetadataArtifacts(staging)
            require(File(staging, "usr/bin/bash").isFile) { "Core bundle is missing Bash" }
            rootfs.deleteRecursively()
            check(staging.renameTo(rootfs)) { "Could not activate the Linux environment" }
            writeResolver()
            if (archive.parentFile == downloads) archive.delete()
        }

        migrateLegacyClaudeMarker()
        ensureSettingsAndHooks()

        // Node.js and Git are always available in the Core runtime. Python,
        // C/C++, PHP, and Android remain opt-in stacks during onboarding.
        val coreNeeded = !File(rootfs, "usr/bin/git").exists() || !isSupportedCoreToolsVersion()
        if (coreNeeded) {
            installNodeIfNeeded(proot, 0.58f, 0.66f, onProgress)
        }
        if (systemUpgradeMarker.readTextOrNull() != SYSTEM_UPGRADE_VERSION) {
            runSystemMaintenance(proot, onProgress)
            systemUpgradeMarker.writeText(SYSTEM_UPGRADE_VERSION)
        }
        if (coreNeeded) {
            aptInstall(
                proot,
                listOf("git", "ca-certificates"),
                "Installing Git and base tools",
                0.70f,
                onProgress,
            )
            writeResolver()
            verifyGuest(proot, "git --version", "Base tools could not be verified")
            coreToolsMarker.writeText(CORE_TOOLS_VERSION)
        }

        val missingStacks = selectedStacks.filterNot(::isStackInstalled)
        missingStacks.forEachIndexed { index, stack ->
            val slice = 0.26f / maxOf(1, missingStacks.size)
            val from = 0.72f + index * slice
            applyStack(proot, stack, from, from + slice, onProgress)
        }

        when (agent) {
            com.jarves.mh.model.AgentKind.CLAUDE_CODE -> ensureClaudeInstalled(proot, 0.985f, onProgress)
            com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS -> ensureDshInstalled(proot, 0.985f, onProgress)
            com.jarves.mh.model.AgentKind.ANTIGRAVITY -> ensureAgyInstalled(proot, 0.985f, onProgress)
        }
        onProgress(RuntimeInstallProgress("Setup complete", 1f))
        return InstalledRuntime(proot, rootfs)
    }

    /**
     * Installs one coding agent on demand. Safe to call again: an already-installed
     * agent returns immediately without network access. Every coding agent is a
     * separate overlay and is fetched or loaded only when selected.
     */
    suspend fun ensureAgentInstalled(
        agent: com.jarves.mh.model.AgentKind,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        when (agent) {
            com.jarves.mh.model.AgentKind.CLAUDE_CODE -> ensureClaudeInstalled(runtime.proot, 0.05f, onProgress)
            com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS -> ensureDshInstalled(runtime.proot, 0.05f, onProgress)
            com.jarves.mh.model.AgentKind.ANTIGRAVITY -> ensureAgyInstalled(runtime.proot, 0.05f, onProgress)
        }
        onProgress(RuntimeInstallProgress("${agent.title} is ready", 1f))
    }

    fun isAgentInstalled(agent: com.jarves.mh.model.AgentKind): Boolean {
        return when (agent) {
            com.jarves.mh.model.AgentKind.CLAUDE_CODE -> {
                migrateLegacyClaudeMarker()
                isInstalled() && File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/")).canExecute() &&
                    !claudeMarker.readTextOrNull().isNullOrBlank()
            }
            com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS -> isInstalled() &&
                // /usr/local/bin/dsh is an absolute guest symlink. File.exists() follows it
                // against Android's host root and therefore reports false outside PRoot.
                File(rootfs, "usr/local/lib/dsh/node_modules/.bin/dsh").isFile &&
                !dshMarker.readTextOrNull().isNullOrBlank()
            com.jarves.mh.model.AgentKind.ANTIGRAVITY -> isInstalled() &&
                File(rootfs, AGY_GUEST_PATH.removePrefix("/")).canExecute() &&
                !agyMarker.readTextOrNull().isNullOrBlank()
        }
    }

    val dshVersion: String get() = dshMarker.readTextOrNull().orEmpty()

    val claudeVersion: String get() {
        migrateLegacyClaudeMarker()
        return claudeMarker.readTextOrNull().orEmpty()
    }

    val agyVersion: String get() = agyMarker.readTextOrNull().orEmpty()

    val githubCliVersion: String get() = githubCliMarker.readTextOrNull().orEmpty()

    fun isGitHubCliInstalled(): Boolean = isInstalled() &&
        File(rootfs, GITHUB_CLI_GUEST_PATH.removePrefix("/")).canExecute() &&
        githubCliMarker.readTextOrNull() == GITHUB_CLI_VERSION

    /** Installs GitHub's official ARM64 CLI on demand; it is not bundled in the APK. */
    suspend fun ensureGitHubCliInstalled(onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        if (isGitHubCliInstalled()) return
        check(!BuildConfig.OFFLINE_RUNTIME_BUNDLES) {
            "GitHub sign-in needs the PocketDev online APK."
        }
        writeResolver()
        downloads.mkdirs()
        val downloaded = File(downloads, "gh-$GITHUB_CLI_VERSION-linux-arm64.tar.gz")
        onProgress(RuntimeInstallProgress("Downloading official GitHub CLI", 0.05f))
        downloadVerified(GITHUB_CLI_RELEASE_URL, downloaded, GITHUB_CLI_RELEASE_SHA256) { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(
                RuntimeInstallProgress(
                    message = "Downloading GitHub CLI $GITHUB_CLI_VERSION",
                    fraction = 0.05f + ratio * 0.75f,
                    downloadedBytes = bytes,
                    totalBytes = total.takeIf { it > 0L },
                    event = RuntimeInstallEvent.DOWNLOAD,
                ),
            )
        }
        onProgress(RuntimeInstallProgress("Installing GitHub CLI $GITHUB_CLI_VERSION", 0.85f, indeterminate = true))
        val destination = File(rootfs, GITHUB_CLI_GUEST_PATH.removePrefix("/"))
        destination.parentFile?.mkdirs()
        var found = false
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(downloaded.inputStream()))).use { archive ->
            var entry = archive.nextEntry
            while (entry != null) {
                if (entry.isFile && entry.name.removePrefix("./").endsWith("/bin/gh")) {
                    val staged = File(destination.parentFile, ".gh-$GITHUB_CLI_VERSION.installing")
                    FileOutputStream(staged).use { archive.copyTo(it) }
                    Os.chmod(staged.absolutePath, 0b111101101)
                    Os.rename(staged.absolutePath, destination.absolutePath)
                    found = true
                    break
                }
                entry = archive.nextEntry
            }
        }
        check(found) { "Official GitHub CLI archive did not contain the expected binary" }
        downloaded.delete()
        verifyGuest(proot = installedRuntime().proot, command = "$GITHUB_CLI_GUEST_PATH --version", failureMessage = "GitHub CLI verification failed")
        githubCliMarker.writeText(GITHUB_CLI_VERSION)
        check(isGitHubCliInstalled()) { "GitHub CLI installation is incomplete" }
        onProgress(RuntimeInstallProgress("GitHub CLI is ready", 1f))
    }

    /**
     * Versions recorded after each real agent binary has been installed and verified.
     * This intentionally reports what is present in PRoot, even when a newer app build
     * would subsequently offer an agent update.
     */
    fun installedAgentVersions(): Map<com.jarves.mh.model.AgentKind, String> = buildMap {
        migrateLegacyClaudeMarker()
        claudeMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { isAgentInstalled(com.jarves.mh.model.AgentKind.CLAUDE_CODE) && it.matches(CLAUDE_VERSION_PATTERN) }
            ?.let { put(com.jarves.mh.model.AgentKind.CLAUDE_CODE, it) }

        dshMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && File(rootfs, "usr/local/lib/dsh/node_modules/.bin/dsh").isFile }
            ?.let { put(com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS, it) }

        agyMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && File(rootfs, AGY_GUEST_PATH.removePrefix("/")).canExecute() }
            ?.let { put(com.jarves.mh.model.AgentKind.ANTIGRAVITY, it) }
    }

    /** Checks each installed agent against its own authoritative release source. */
    suspend fun checkAgentUpdates(): Map<com.jarves.mh.model.AgentKind, AgentUpdateInfo> {
        val installed = installedAgentVersions()
        return buildMap {
            installed[com.jarves.mh.model.AgentKind.CLAUDE_CODE]?.let { current ->
                runCatching {
                    JSONObject(fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest")).getString("version")
                }.getOrNull()?.takeIf { isVersionNewer(it, current) }?.let { latest ->
                    put(com.jarves.mh.model.AgentKind.CLAUDE_CODE, AgentUpdateInfo(current, latest))
                }
            }
            installed[com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS]?.let { current ->
                runCatching {
                    JSONObject(fetchText("https://registry.npmjs.org/@deepseek-ai/dsh/latest")).getString("version")
                }.getOrNull()?.takeIf { isVersionNewer(it, current) }?.let { latest ->
                    put(com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS, AgentUpdateInfo(current, latest))
                }
            }
            installed[com.jarves.mh.model.AgentKind.ANTIGRAVITY]?.let { current ->
                runCatching { fetchAgyManifest().getString("version") }.getOrNull()
                    ?.takeIf { isVersionNewer(it, current) }?.let { latest ->
                        put(com.jarves.mh.model.AgentKind.ANTIGRAVITY, AgentUpdateInfo(current, latest))
                    }
            }
        }
    }

    suspend fun updateAgent(
        agent: com.jarves.mh.model.AgentKind,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        when (agent) {
            com.jarves.mh.model.AgentKind.CLAUDE_CODE -> updateClaude(runtime, expectedVersion, onProgress)
            com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS -> updateDsh(runtime, expectedVersion, onProgress)
            com.jarves.mh.model.AgentKind.ANTIGRAVITY -> updateAgy(runtime, expectedVersion, onProgress)
        }
        onProgress(RuntimeInstallProgress("${agent.title} $expectedVersion is ready", 1f, event = RuntimeInstallEvent.COMPLETED))
    }

    private suspend fun updateClaude(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val latest = JSONObject(fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest")).getString("version")
        check(latest == expectedVersion) { "A newer Claude Code release appeared. Check again before updating." }
        val base = "https://downloads.claude.ai/claude-code-releases/$latest"
        val manifest = JSONObject(fetchText("$base/manifest.json"))
        val checksum = manifest.getJSONObject("platforms").getJSONObject("linux-arm64").getString("checksum")
        val downloaded = File(downloads, "claude-$latest")
        downloadVerified("$base/linux-arm64/claude", downloaded, checksum) { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress("Downloading Claude Code $latest", ratio * 0.9f, bytes, total.takeIf { it > 0L }, event = RuntimeInstallEvent.DOWNLOAD))
        }
        val claude = File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/"))
        claude.parentFile?.mkdirs()
        val staged = File(claude.parentFile, ".claude-$latest.installing")
        downloaded.copyTo(staged, overwrite = true)
        Os.chmod(staged.absolutePath, 0b111101101)
        Os.rename(staged.absolutePath, claude.absolutePath)
        downloaded.delete()
        verifyGuest(runtime.proot, "$CLAUDE_GUEST_PATH --version", "Claude Code update verification failed")
        claudeMarker.writeText(latest)
    }

    private suspend fun updateAgy(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val manifest = fetchAgyManifest()
        val latest = manifest.getString("version")
        check(latest == expectedVersion) { "A newer Antigravity release appeared. Check again before updating." }
        val downloaded = File(downloads, "antigravity-$latest-linux-arm64.tar.gz")
        downloadVerified(manifest.getString("url"), downloaded, manifest.getString("sha512"), algorithm = "SHA-512") { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress("Downloading Antigravity CLI $latest", ratio * 0.9f, bytes, total.takeIf { it > 0L }, event = RuntimeInstallEvent.DOWNLOAD))
        }
        val destination = File(rootfs, AGY_GUEST_PATH.removePrefix("/"))
        var found = false
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(downloaded.inputStream()))).use { archive ->
            var entry = archive.nextEntry
            while (entry != null) {
                if (entry.isFile && entry.name.removePrefix("./") == "antigravity") {
                    val staged = File(destination.parentFile, ".agy-$latest.installing")
                    FileOutputStream(staged).use { archive.copyTo(it) }
                    Os.chmod(staged.absolutePath, 0b111101101)
                    Os.rename(staged.absolutePath, destination.absolutePath)
                    found = true
                    break
                }
                entry = archive.nextEntry
            }
        }
        downloaded.delete()
        check(found) { "Antigravity update archive is incomplete" }
        verifyGuest(runtime.proot, "$AGY_GUEST_PATH --version", "Antigravity update verification failed")
        agyMarker.writeText(latest)
    }

    private suspend fun updateDsh(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val latest = JSONObject(fetchText("https://registry.npmjs.org/@deepseek-ai/dsh/latest")).getString("version")
        check(latest == expectedVersion) { "A newer DeepSeek Harness release appeared. Check again before updating." }
        val quotedVersion = latest.replace(Regex("[^0-9A-Za-z.+-]"), "")
        check(quotedVersion == latest) { "Invalid DeepSeek Harness version" }
        runGuestCommand(
            proot = runtime.proot,
            command = "set -e; next=/usr/local/lib/dsh.updating; old=/usr/local/lib/dsh.previous; " +
                "rm -rf \"${'$'}next\" \"${'$'}old\"; mkdir -p \"${'$'}next\"; " +
                "cd \"${'$'}next\"; npm init -y >/dev/null; " +
                "npm install --omit=dev --no-audit --no-fund @deepseek-ai/dsh@$quotedVersion; " +
                "mv /usr/local/lib/dsh \"${'$'}old\"; " +
                "if mv \"${'$'}next\" /usr/local/lib/dsh; then rm -rf \"${'$'}old\"; " +
                "else mv \"${'$'}old\" /usr/local/lib/dsh; exit 1; fi",
            displayCommand = "npm install @deepseek-ai/dsh@$quotedVersion",
            fraction = 0.55f,
            timeoutMs = 20 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "DeepSeek Harness update failed; the installed version was preserved",
        )
        dshMarker.writeText(latest)
        dshAndroidCompatibilityMarker.delete()
        ensureDshAndroidCompatibility()
        verifyGuest(runtime.proot, "/usr/local/bin/dsh --profile headless --help", "DeepSeek Harness update verification failed")
    }

    private fun fetchAgyManifest(): JSONObject = JSONObject(
        fetchText("https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json"),
    )

    private fun isVersionNewer(candidate: String, current: String): Boolean {
        fun parts(value: String) = Regex("\\d+").findAll(value).map { it.value.toIntOrNull() ?: 0 }.toList()
        val left = parts(candidate)
        val right = parts(current)
        repeat(maxOf(left.size, right.size)) { index ->
            val comparison = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
            if (comparison != 0) return comparison > 0
        }
        return candidate != current && !candidate.contains("alpha", true) && !candidate.contains("rc", true)
    }

    /**
     * Older Core bundles stored Claude Code and its version in Core-owned markers.
     * Preserve that verified installation when upgrading the app, while all fresh
     * installs use the independent Claude overlay and marker.
     */
    private fun migrateLegacyClaudeMarker() {
        if (claudeMarker.isFile) return
        val claude = File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/"))
        if (!claude.isFile) return
        val legacyVersion = sequenceOf(
            coreReadyMarker.readTextOrNull(),
            bundledClaudeMarker.readTextOrNull(),
        ).mapNotNull { it?.trim() }.firstOrNull { it.matches(CLAUDE_VERSION_PATTERN) } ?: return
        claudeMarker.writeText(legacyVersion)
    }

    private fun isSupportedCoreToolsVersion(): Boolean = coreToolsMarker.readTextOrNull() in setOf(
        CORE_TOOLS_VERSION,
        LEGACY_CORE_TOOLS_VERSION,
    )

    private suspend fun ensureClaudeInstalled(
        proot: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        migrateLegacyClaudeMarker()
        if (isAgentInstalled(com.jarves.mh.model.AgentKind.CLAUDE_CODE)) return
        installRuntimeOverlay(
            bundle = CLAUDE_BUNDLE,
            message = "Installing Claude Code $CLAUDE_BUNDLED_VERSION",
            from = fraction,
            to = 0.995f,
            onProgress = onProgress,
        )
        verifyGuest(proot, "$CLAUDE_GUEST_PATH --version", "Claude Code verification failed")
        require(claudeMarker.readTextOrNull() == CLAUDE_BUNDLED_VERSION) {
            "The Claude Code runtime bundle is incomplete"
        }
    }

    private suspend fun ensureAgyInstalled(
        proot: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isAgentInstalled(com.jarves.mh.model.AgentKind.ANTIGRAVITY)) return
        installRuntimeOverlay(
            bundle = AGY_BUNDLE,
            message = "Installing Antigravity CLI $AGY_VERSION",
            from = fraction,
            to = 0.995f,
            onProgress = onProgress,
            forceEmbedded = true,
        )
        verifyGuest(proot, "$AGY_GUEST_PATH --version", "Antigravity CLI verification failed")
        agyMarker.writeText(AGY_VERSION)
        require(isAgentInstalled(com.jarves.mh.model.AgentKind.ANTIGRAVITY)) {
            "Antigravity CLI installation is incomplete"
        }
    }

    private suspend fun ensureDshInstalled(
        proot: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isAgentInstalled(com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS)) {
            ensureDshAndroidCompatibility()
            return
        }
        installRuntimeOverlay(
            bundle = DSH_BUNDLE,
            message = "Installing DeepSeek Harness $DSH_VERSION",
            from = fraction,
            to = 0.995f,
            onProgress = onProgress,
        )
        ensureDshAndroidCompatibility()
        verifyGuest(proot, "/usr/local/bin/dsh --profile headless --help", "DeepSeek Harness verification failed")
        require(isAgentInstalled(com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS)) {
            "The DeepSeek Harness runtime bundle is incomplete"
        }
    }

    /**
     * DSH uses POSIX hard links for no-clobber publication of new session and
     * workspace files. Android blocks that syscall inside PRoot. PRoot's
     * `--link2symlink` workaround is unsuitable here because DSH immediately
     * deletes its staging file, leaving the published symlink dangling.
     *
     * The bundled, pinned DSH build can use COPYFILE_EXCL for the same
     * no-clobber guarantee. Existing-file edits continue to use atomic rename.
     */
    fun ensureDshAndroidCompatibility() {
        if (!isAgentInstalled(com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS)) return
        val persistence = File(
            rootfs,
            "usr/local/lib/dsh/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
        )
        val localFs = File(
            rootfs,
            "usr/local/lib/dsh/node_modules/@deepseek-ai/dsh-fs-local/lib/index.js",
        )
        patchDshHardLinkPublication(
            file = persistence,
            importBefore = "import { link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";",
            importAfter = "import { copyFile, link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";",
            callBefore = "await link(tmp, finalPath);",
            callAfter = "await copyFile(tmp, finalPath, 1);",
        )
        patchDshHardLinkPublication(
            file = localFs,
            importBefore = "import { chmod, link, lstat, mkdir, open, readFile, readdir, realpath, rename, rm, stat } from \"node:fs/promises\";",
            importAfter = "import { chmod, copyFile, link, lstat, mkdir, open, readFile, readdir, realpath, rename, rm, stat } from \"node:fs/promises\";",
            callBefore = "await linkFile(tempPath, absolutePath);",
            callAfter = "await copyFile(tempPath, absolutePath, 1);",
        )
        dshAndroidCompatibilityMarker.writeText(DSH_ANDROID_COMPATIBILITY_VERSION)
    }

    private fun patchDshHardLinkPublication(
        file: File,
        importBefore: String,
        importAfter: String,
        callBefore: String,
        callAfter: String,
    ) {
        check(file.isFile) { "DeepSeek Harness compatibility file is missing: ${file.name}" }
        var source = file.readText()
        if (callAfter in source && importAfter in source) return
        check(callBefore in source && importBefore in source) {
            "DeepSeek Harness $DSH_VERSION is not compatible with this PocketDev build"
        }
        source = source.replace(importBefore, importAfter).replace(callBefore, callAfter)
        file.writeText(source)
    }

    /**
     * Installs one optional development stack inside Ubuntu. Safe to call again:
     * already-installed stacks return immediately without network access.
     */
    suspend fun ensureStackInstalled(
        stack: DevStack,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        if (isStackInstalled(stack)) return
        applyStack(runtime.proot, stack, 0.05f, 0.95f, onProgress)
        onProgress(RuntimeInstallProgress("${stack.label} tools are ready", 1f))
    }

    private suspend fun applyStack(
        proot: File,
        stack: DevStack,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        var verified = true
        when (stack) {
            DevStack.WEB -> {
                onProgress(RuntimeInstallProgress("Checking Node.js and npm", from))
                verifyGuest(proot, "node --version && npm --version", "Node.js tools could not be verified")
            }
            DevStack.PYTHON -> {
                installRuntimeOverlay(PYTHON_BUNDLE, "Installing Python, pip, and venv", from, to, onProgress)
                runCatching {
                    verifyGuest(proot, "python3 --version && pip3 --version", "Python tools could not be verified")
                }.onFailure { error ->
                    verified = false
                    onProgress(
                        RuntimeInstallProgress(
                            "Python verify failed (will retry on next launch): ${error.message?.take(120)}",
                            to,
                        ),
                    )
                }
            }
            DevStack.ANDROID -> {
                installAndroidToolchain(proot, from, to, onProgress)
            }
            DevStack.CPP -> {
                aptInstall(
                    proot,
                    listOf("build-essential", "cmake", "gdb"),
                    "Installing C/C++ compilers and build tools",
                    from,
                    onProgress,
                )
                verifyGuest(
                    proot,
                    "gcc --version && g++ --version && make --version && cmake --version",
                    "C/C++ tools could not be verified",
                )
            }
            DevStack.PHP -> {
                aptInstall(
                    proot,
                    listOf("php-cli", "php-mbstring", "php-xml", "php-curl", "php-zip", "unzip"),
                    "Installing PHP and common extensions",
                    from,
                    onProgress,
                )
                installComposer(proot, from, onProgress)
                verifyGuest(proot, "php --version && composer --version", "PHP tools could not be verified")
            }
        }
        if (!verified) return
        writeDevStackState(readDevStackState().apply { put(stack.name, true) })
        onProgress(RuntimeInstallProgress("${stack.label} installed", to))
    }

    /**
     * Installs Composer into Ubuntu from the official latest-stable release,
     * verified against getcomposer.org's published SHA-256 checksum.
     */
    private suspend fun installComposer(
        proot: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val composer = File(rootfs, "usr/local/bin/composer")
        if (composer.isFile) return
        onProgress(RuntimeInstallProgress("Downloading Composer", fraction))
        downloads.mkdirs()
        val staged = File(downloads, "composer.phar")
        val checksum = fetchText("https://getcomposer.org/download/latest-stable/composer.phar.sha256sum")
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.substringBefore(' ')
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: error("Composer checksum was not found")
        downloadVerified(
            "https://getcomposer.org/download/latest-stable/composer.phar",
            staged,
            checksum,
        ) { _, _ -> }
        composer.parentFile?.mkdirs()
        if (composer.exists()) composer.delete()
        // cacheDir and filesDir can live on different mounts: copy instead of rename.
        staged.inputStream().use { input -> FileOutputStream(composer).use { input.copyTo(it) } }
        staged.delete()
        Os.chmod(composer.absolutePath, 0b111101101)
        onProgress(RuntimeInstallProgress("Installing Composer", fraction))
    }

    private suspend fun installAndroidToolchain(
        proot: File,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val marker = File(rootfs, "root/.pocket-android-tools-version")
        val androidHome = File(rootfs, "root/android-sdk")
        val gradleHome = File(rootfs, "opt/gradle")
        val localMaven = File(rootfs, "root/maven/localMvnRepository")
        if (marker.readTextOrNull() != ANDROID_TOOLS_VERSION ||
            !File(androidHome, "platforms/android-36/android.jar").isFile ||
            !File(androidHome, "build-tools/35.0.0/aapt2").isFile ||
            !File(gradleHome, "gradle-8.14.3/bin/gradle").isFile ||
            !localMaven.isDirectory) {
            installRuntimeOverlay(
                ANDROID_BUNDLE,
                "Installing the Android development tools",
                from,
                to,
                onProgress,
            )
            makeAndroidToolsExecutable(androidHome, gradleHome)
            marker.parentFile?.mkdirs()
            marker.writeText(ANDROID_TOOLS_VERSION)
        }
        // Keep this outside the download/install branch so app updates repair
        // existing Android toolchains without downloading the bundles again.
        writeAndroidGradleConfiguration(rootfs)

        verifyGuest(
            proot,
            "java -version 2>&1 | grep -E '\"17\\.|version 17' && " +
                "gradle --version && aapt2 version && test -f \"${'$'}ANDROID_HOME/platforms/android-36/android.jar\"",
            "Android SDK, Gradle, or Java could not be verified",
        )
    }

    private suspend fun installRuntimeOverlay(
        bundle: RuntimeBundle,
        message: String,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
        forceEmbedded: Boolean = false,
    ) {
        // Stack overlays honor the same offline/online flavor as the Core bundle:
        // the offline APK ships every stack bundle inside its assets, while the
        // online APK fetches each one from the release URL on demand.
        val archive = obtainRuntimeBundle(bundle, preferEmbedded = forceEmbedded || BuildConfig.OFFLINE_RUNTIME_BUNDLES, from, to * 0.8f + from * 0.2f, onProgress)
        onProgress(RuntimeInstallProgress(message, to * 0.8f + from * 0.2f, indeterminate = true))
        extractZstdTar(archive, rootfs)
        stripMacosMetadataArtifacts(rootfs)
        if (archive.parentFile == downloads) archive.delete()
        onProgress(RuntimeInstallProgress("${bundle.label} tools installed", to))
    }

    /**
     * Removes AppleDouble-style metadata files (`._*`) that some on-device
     * tar builders and macOS tarballs embed alongside the real files. They
     * are harmless to most tools, but Python's site module reads every
     * `.pth` file in site-packages and crashes when one of them is a
     * binary metadata blob.
     */
    private fun stripMacosMetadataArtifacts(root: File) {
        if (!root.isDirectory) return
        val queue = ArrayDeque<File>()
        queue.add(root)
        var removed = 0
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.name.startsWith("._")) {
                    if (child.isDirectory) child.deleteRecursively() else child.delete()
                    removed++
                } else if (child.isDirectory && !java.nio.file.Files.isSymbolicLink(child.toPath())) {
                    queue.add(child)
                }
            }
        }
        if (removed > 0) {
            android.util.Log.i("RuntimeInstaller", "Stripped $removed macOS metadata artifacts")
        }
    }

    private suspend fun obtainRuntimeBundle(
        bundle: RuntimeBundle,
        preferEmbedded: Boolean,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): File {
        downloads.mkdirs()
        val destination = File(downloads, bundle.fileName)
        val useEmbedded = preferEmbedded || BuildConfig.OFFLINE_RUNTIME_BUNDLES
        if (useEmbedded) {
            onProgress(RuntimeInstallProgress("Loading ${bundle.label} bundle", from, 0, bundle.compressedBytes))
            val temporary = File(downloads, "${bundle.fileName}.part")
            context.assets.open("runtime/${bundle.fileName}").use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        val ratio = (copied.toFloat() / bundle.compressedBytes).coerceIn(0f, 1f)
                        onProgress(RuntimeInstallProgress("Loading ${bundle.label} bundle", from + ratio * (to - from), copied, bundle.compressedBytes))
                    }
                }
            }
            require(digest(temporary, "SHA-256").equals(bundle.sha256, ignoreCase = true)) {
                "${bundle.label} bundle checksum mismatch"
            }
            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) { "Could not stage the ${bundle.label} bundle" }
            return destination
        }

        val url = "${BuildConfig.RUNTIME_RELEASE_BASE_URL}/${bundle.fileName}"
        downloadVerified(url, destination, bundle.sha256) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress("Downloading ${bundle.label} bundle", from + ratio * (to - from), downloaded, total.takeIf { it > 0 }))
        }
        return destination
    }

    private suspend fun installZipAsset(
        url: String,
        checksum: String,
        archiveName: String,
        destination: File,
        message: String,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        downloads.mkdirs()
        val archive = File(downloads, archiveName)
        downloadVerified(url, archive, checksum) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(message, from + ratio * (to - from), downloaded, total.takeIf { it > 0 }))
        }
        onProgress(RuntimeInstallProgress("Installing ${archiveName.removeSuffix(".zip")}", to, indeterminate = true))
        val staging = File(destination.parentFile, "${destination.name}.installing")
        staging.deleteRecursively()
        staging.mkdirs()
        extractZipArchive(archive, staging)
        destination.deleteRecursively()
        check(staging.renameTo(destination)) { "Could not activate ${destination.name}" }
        archive.delete()
    }

    private fun extractZipArchive(archive: File, destination: File) {
        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.removePrefix("./").trimStart('/')
                if (name.isNotBlank()) {
                    val target = safeChild(destination, name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output -> zip.copyTo(output) }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun makeAndroidToolsExecutable(androidHome: File, gradleHome: File) {
        val buildTools = File(androidHome, "build-tools/35.0.0")
        listOf("aapt", "aapt2", "aidl", "apksigner", "d8", "dexdump", "split-select", "zipalign")
            .map { File(buildTools, it) }
            .plus(File(gradleHome, "gradle-8.14.3/bin/gradle"))
            .filter(File::isFile)
            .forEach { Os.chmod(it.absolutePath, 0b111101101) }
    }

    private fun writeAndroidGradleInitScript(runtimeRootfs: File) {
        val script = File(runtimeRootfs, "root/.gradle/init.d/pocketdev-android.gradle")
        script.parentFile?.mkdirs()
        script.writeText(
            """
            def pocketMaven = uri('/root/maven/localMvnRepository')
            beforeSettings { settings ->
                settings.pluginManagement.repositories {
                    maven { url = pocketMaven }
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            settingsEvaluated { settings ->
                settings.dependencyResolutionManagement.repositories {
                    maven { url = pocketMaven }
                }
            }
            gradle.beforeProject { project ->
                project.extensions.extraProperties.set(
                    'android.aapt2FromMavenOverride',
                    '/root/android-sdk/build-tools/35.0.0/aapt2'
                )
                project.buildscript.repositories {
                    maven { url = pocketMaven }
                }
            }
            """.trimIndent() + "\n",
        )
    }

    @Synchronized
    private fun writeAndroidGradleConfiguration(runtimeRootfs: File) {
        val aapt2 = File(runtimeRootfs, ANDROID_AAPT2_HOST_PATH)
        if (!aapt2.isFile) return

        writeAndroidGradleInitScript(runtimeRootfs)
        val gradleDir = File(runtimeRootfs, "root/.gradle").apply { mkdirs() }
        val properties = File(gradleDir, "gradle.properties")
        val propertyPattern = Regex("^\\s*${Regex.escape(ANDROID_AAPT2_PROPERTY)}\\s*[:=].*$")
        val existingLines = properties.readTextOrNull()?.lineSequence()?.toList().orEmpty()
        val expectedLine = "$ANDROID_AAPT2_PROPERTY=$ANDROID_AAPT2_GUEST_PATH"
        val updatedLines = existingLines.filterNot { propertyPattern.matches(it) } + expectedLine
        if (existingLines == updatedLines) return

        val temporary = File(gradleDir, "gradle.properties.pocketdev.tmp")
        temporary.writeText(updatedLines.joinToString("\n").trimEnd() + "\n")
        Os.rename(temporary.absolutePath, properties.absolutePath)
    }

    /**
     * One-time upgrade path from the old single-bundle layout: devices that already
     * installed every tool keep all stacks without re-downloading anything.
     */
    fun migrateLegacyToolMarkers() {
        if (!File(rootfs, "usr/bin/bash").isFile) return
        if (languageToolsMarker.readTextOrNull() != LANGUAGE_TOOLS_VERSION) return
        if (coreToolsMarker.readTextOrNull() != CORE_TOOLS_VERSION) coreToolsMarker.writeText(CORE_TOOLS_VERSION)
        val state = readDevStackState()
        DevStack.entries.forEach { stack -> if (!state.containsKey(stack.name)) state[stack.name] = true }
        writeDevStackState(state)
    }

    fun installedStacks(): Set<DevStack> = readDevStackState()
        .filterValues { it }
        .keys
        .mapNotNull { name -> runCatching { DevStack.valueOf(name) }.getOrNull() }
        .filter(::isStackInstalled)
        .toSet()

    fun isStackInstalled(stack: DevStack): Boolean {
        if (readDevStackState()[stack.name] != true) return false
        if (stack != DevStack.ANDROID) return true
        return File(rootfs, "root/.pocket-android-tools-version").readTextOrNull() == ANDROID_TOOLS_VERSION &&
            File(rootfs, "root/android-sdk/platforms/android-36/android.jar").isFile &&
            File(rootfs, "root/android-sdk/build-tools/35.0.0/aapt2").isFile &&
            File(rootfs, "opt/gradle/gradle-8.14.3/bin/gradle").isFile &&
            File(rootfs, "root/maven/localMvnRepository").let { it.isDirectory && !it.list().isNullOrEmpty() } &&
            File(rootfs, "root/.gradle/init.d/pocketdev-android.gradle").isFile
    }

    private fun readDevStackState(): MutableMap<String, Boolean> {
        if (!devStacksFile.isFile) return mutableMapOf()
        return runCatching {
            val obj = JSONObject(devStacksFile.readText())
            mutableMapOf<String, Boolean>().apply {
                DevStack.entries.forEach { stack ->
                    if (obj.has(stack.name)) put(stack.name, obj.optBoolean(stack.name))
                }
            }
        }.getOrDefault(mutableMapOf())
    }

    private fun writeDevStackState(state: Map<String, Boolean>) {
        devStacksFile.parentFile?.mkdirs()
        val obj = JSONObject()
        state.forEach { (name, value) -> obj.put(name, value) }
        devStacksFile.writeText(obj.toString())
    }

    private suspend fun installNodeIfNeeded(
        proot: File,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (File(rootfs, "usr/local/bin/node").exists()) return
        onProgress(RuntimeInstallProgress("Downloading Node.js $NODE_VERSION LTS", from))
        downloads.mkdirs()
        val nodeFileName = "node-$NODE_VERSION-linux-arm64.tar.gz"
        val nodeBaseUrl = "https://nodejs.org/dist/$NODE_VERSION"
        val checksum = fetchText("$nodeBaseUrl/SHASUMS256.txt")
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.endsWith("  $nodeFileName") }
            ?.substringBefore(' ')
            ?: error("Node.js checksum was not found")
        val nodeArchive = File(downloads, nodeFileName)
        downloadVerified("$nodeBaseUrl/$nodeFileName", nodeArchive, checksum) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(
                RuntimeInstallProgress(
                    "Downloading Node.js $NODE_VERSION LTS",
                    from + ratio * (to - from),
                    downloaded,
                    total.takeIf { it > 0 },
                ),
            )
        }
        onProgress(RuntimeInstallProgress("Installing Node.js and npm", to))
        val nodeStaging = File(runtimeDir, "node.installing")
        nodeStaging.deleteRecursively()
        nodeStaging.mkdirs()
        extractNodeArchive(nodeArchive, nodeStaging)
        val nodeHome = File(rootfs, "usr/local/lib/nodejs")
        nodeHome.deleteRecursively()
        nodeHome.parentFile?.mkdirs()
        check(nodeStaging.renameTo(nodeHome)) { "Could not activate Node.js" }
        val localBin = File(rootfs, "usr/local/bin").apply { mkdirs() }
        listOf("node", "npm", "npx", "corepack").forEach { command ->
            val link = File(localBin, command)
            if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
            Os.symlink("../lib/nodejs/bin/$command", link.absolutePath)
        }
        nodeArchive.delete()
    }

    private suspend fun aptInstall(
        proot: File,
        packages: List<String>,
        message: String,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        onProgress(RuntimeInstallProgress(message, fraction))
        aptInstallInternal(proot, packages, fraction, onProgress)
    }

    private suspend fun runSystemMaintenance(
        proot: File,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        onProgress(
            RuntimeInstallProgress(
                message = "Updating the private Ubuntu environment",
                fraction = 0.69f,
                indeterminate = true,
            ),
        )
        writeResolver()
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg --configure -a && " +
            "apt-get -o DPkg::Lock::Timeout=120 -f install -y && " +
            "apt-get -o DPkg::Lock::Timeout=120 update && " +
            "apt-get -o DPkg::Lock::Timeout=120 upgrade -y"
        runGuestCommand(
            proot = proot,
            command = command,
            displayCommand = "dpkg --configure -a && apt-get -f install -y && apt-get update && apt-get upgrade -y",
            fraction = 0.69f,
            timeoutMs = 35 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Ubuntu maintenance could not be completed",
        )
    }

    private suspend fun aptInstallInternal(
        proot: File,
        packages: List<String>,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(packages.isNotEmpty()) { "No packages selected" }
        writeResolver()
        val packageNames = packages.joinToString(" ")
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg --configure -a && " +
            "apt-get -o DPkg::Lock::Timeout=120 -f install -y && " +
            "apt-get -o DPkg::Lock::Timeout=120 update && " +
            "apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends $packageNames && " +
            "apt-get clean && rm -rf /var/lib/apt/lists/*"
        runGuestCommand(
            proot = proot,
            command = command,
            displayCommand = "dpkg --configure -a && apt-get -f install -y && apt-get install -y $packageNames",
            fraction = fraction,
            timeoutMs = 30 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Could not install: $packageNames",
        )
    }

    private suspend fun runGuestCommand(
        proot: File,
        command: String,
        displayCommand: String,
        fraction: Float,
        timeoutMs: Long,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
        failureMessage: String,
    ) {
        onProgress(
            RuntimeInstallProgress(
                message = displayCommand,
                fraction = fraction,
                terminalLine = "root@pocket:~# $displayCommand",
                indeterminate = true,
                event = RuntimeInstallEvent.COMMAND,
            ),
        )
        val running = process(
            proot = proot,
            rootfs = rootfs,
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
        )
        val native = running as? NativeSpawnProcess
        val collected = StringBuilder()
        try {
            withTimeout(timeoutMs) {
                var offset = 0L
                var pending = ""
                while (running.isAlive || (native?.outputFile?.length() ?: 0L) > offset) {
                    coroutineContext.ensureActive()
                    val file = native?.outputFile
                    if (file != null && file.length() > offset) {
                        RandomAccessFile(file, "r").use { input ->
                            input.seek(offset)
                            val available = (input.length() - offset).coerceAtMost(256 * 1024).toInt()
                            val bytes = ByteArray(available)
                            input.readFully(bytes)
                            offset += available
                            pending += bytes.toString(Charsets.UTF_8).replace('\r', '\n')
                        }
                        val parts = pending.split('\n')
                        pending = parts.last()
                        for (raw in parts.dropLast(1)) {
                            val line = sanitizeTerminalLine(raw)
                            if (line.isNotBlank()) {
                                collected.appendLine(line)
                                if (collected.length > MAX_COLLECTED_OUTPUT) collected.delete(0, collected.length - MAX_COLLECTED_OUTPUT)
                                onProgress(
                                    RuntimeInstallProgress(
                                        message = line,
                                        fraction = fraction,
                                        terminalLine = line,
                                        indeterminate = true,
                                        event = RuntimeInstallEvent.OUTPUT,
                                    ),
                                )
                            }
                        }
                    } else {
                        delay(80)
                    }
                }
                sanitizeTerminalLine(pending).takeIf(String::isNotBlank)?.let { line ->
                    collected.appendLine(line)
                    onProgress(RuntimeInstallProgress(line, fraction, terminalLine = line, indeterminate = true, event = RuntimeInstallEvent.OUTPUT))
                }
            }
        } finally {
            if (running.isAlive) running.destroy()
        }
        val exit = running.waitFor()
        onProgress(
            RuntimeInstallProgress(
                message = if (exit == 0) "Command completed" else "Command failed (exit $exit)",
                fraction = fraction,
                terminalLine = "[exit $exit] $displayCommand",
                event = RuntimeInstallEvent.COMMAND_COMPLETED,
            ),
        )
        check(exit == 0) { collected.toString().trim().takeLast(1_000).ifBlank { failureMessage } }
    }

    private fun sanitizeTerminalLine(raw: String): String = raw
        .replace(ANSI_ESCAPE, "")
        .filter { it == '\t' || it.code >= 32 }
        .take(MAX_TERMINAL_LINE)

    private suspend fun verifyGuest(proot: File, command: String, failureMessage: String) {
        val verify = process(
            proot = proot,
            rootfs = rootfs,
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
        )
        withTimeout(60_000L) {
            while (verify.isAlive) delay(50)
        }
        val exit = verify.waitFor()
        val output = (verify as? NativeSpawnProcess)?.outputFile
            ?.let(::readProcessOutputSafely)
            .orEmpty()
            .trim()
        check(exit == 0) { output.ifBlank { failureMessage } }
    }

    /**
     * Reads a captured PRoot process output file as UTF-8 with replacement,
     * so a stray non-UTF-8 byte in the bundled runtime (for example a binary
     * .pth file from a tarball built on a non-UTF-8 filesystem) does not
     * abort setup.
     */
    private fun readProcessOutputSafely(file: File): String = runCatching {
        val decoder = java.nio.charset.StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
        val bytes = file.readBytes()
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        decoder.decode(buffer).toString()
    }.getOrElse { error ->
        android.util.Log.w("RuntimeInstaller", "Could not decode process output as UTF-8: ${error.message}")
        ""
    }

    suspend fun initializeExisting(onProgress: suspend (RuntimeInstallProgress) -> Unit): InstalledRuntime {
        val installed = installedRuntime()
        onProgress(RuntimeInstallProgress("Checking private runtime files", 0.15f))
        writeResolver()
        ensureSettingsAndHooks()
        File(context.filesDir, "runtime-bridge").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        onProgress(RuntimeInstallProgress("Preparing the Android runtime bridge", 0.42f))
        check(File(rootfs, "usr/local/bin/node").canExecute()) { "Core runtime is missing Node.js" }
        check(File(rootfs, "usr/bin/git").canExecute()) { "Core runtime is missing Git" }
        onProgress(RuntimeInstallProgress("Private runtime is ready", 1f))
        return installed
    }

    fun process(
        proot: File,
        rootfs: File,
        workspace: File,
        environment: Map<String, String>,
        guestCommand: List<String>,
        guestWorkspacePath: String = "/workspace",
        emulateHardLinks: Boolean = true,
        outputFile: File = File(context.cacheDir, "runtime-output-${System.nanoTime()}.log"),
        pseudoTerminal: Boolean = false,
        ptyRows: Int = 40,
        ptyColumns: Int = 120,
    ): Process {
        require(
            guestWorkspacePath == "/workspace" ||
                Regex("^/workspace/[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$").matches(guestWorkspacePath),
        ) { "Invalid project workspace path" }
        workspace.mkdirs()
        File(rootfs, guestWorkspacePath.removePrefix("/")).mkdirs()
        // Self-heal devices whose Android tools were installed by an older app
        // version before the global AAPT2 override was persisted.
        writeAndroidGradleConfiguration(rootfs)
        ensureWorkspaceTrust(guestWorkspacePath)
        val bridge = File(context.filesDir, "runtime-bridge").apply { mkdirs() }
        val args = buildList {
            add(proot.absolutePath)
            if (emulateHardLinks) add("--link2symlink")
            add("-0")
            add("-r")
            add(rootfs.absolutePath)
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")
            // ARM64 Android build tools (notably aapt2) use Bionic's
            // /system/bin/linker64 and, on newer releases, APEX libraries.
            listOf("/system", "/apex", "/vendor", "/product").forEach { hostPath ->
                if (File(hostPath).exists()) {
                    File(rootfs, hostPath.removePrefix("/")).mkdirs()
                    add("-b")
                    add(hostPath)
                }
            }
            add("-b")
            add("${workspace.absolutePath}:$guestWorkspacePath")
            add("-b")
            add("${bridge.absolutePath}:/pocket-bridge")
            add("-w")
            add(guestWorkspacePath)
            addAll(guestCommand)
        }
        val prootTemp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
        return NativeSpawnProcess.start(
            argv = args,
            environment = buildMap {
                put("HOME", "/root")
                val androidReady = File(rootfs, "root/.pocket-android-tools-version").readTextOrNull() == ANDROID_TOOLS_VERSION
                val basePath = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                if (androidReady) {
                    put("ANDROID_HOME", "/root/android-sdk")
                    put("ANDROID_SDK_ROOT", "/root/android-sdk")
                    put("GRADLE_HOME", "/opt/gradle/gradle-8.14.3")
                    put("GRADLE_USER_HOME", "/root/.gradle")
                    put("ORG_GRADLE_PROJECT_android.aapt2FromMavenOverride", "/root/android-sdk/build-tools/35.0.0/aapt2")
                    put("PATH", "/opt/gradle/gradle-8.14.3/bin:/root/android-sdk/build-tools/35.0.0:/root/android-sdk/cmdline-tools/latest/bin:$basePath")
                } else {
                    put("PATH", basePath)
                }
                put("LANG", "C.UTF-8")
                put("TERM", "xterm-256color")
                put("LD_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir)
                put("PROOT_NO_SECCOMP", "1")
                put("PROOT_TMP_DIR", prootTemp.absolutePath)
                put("PROOT_LOADER", File(context.applicationInfo.nativeLibraryDir, "libprootloader.so").absolutePath)
                // Also protects any glibc helper Claude starts later.
                put("GLIBC_TUNABLES", "glibc.pthread.rseq=0")
                putAll(environment)
            },
            cwd = context.filesDir.absolutePath,
            outputFile = outputFile,
            pseudoTerminal = pseudoTerminal,
            ptyRows = ptyRows,
            ptyColumns = ptyColumns,
        )
    }

    fun ensureSettingsAndHooks() {
        val hook = File(rootfs, "opt/pocket/permission-hook.sh")
        hook.parentFile?.mkdirs()
        hook.writeText(
            """#!/bin/sh
cat > /dev/null
printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}'
""",
        )
        Os.chmod(hook.absolutePath, 0b111101101)

        val settingsContent = JSONObject()
            .put("disableAllHooks", false)
            .put(
                "permissions",
                JSONObject()
                    .put("allow", claudeWorkspaceToolRules())
                    .put("defaultMode", "acceptEdits"),
            )
            .put(
                "hooks",
                JSONObject().put(
                    "PermissionRequest",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("matcher", "Bash|Edit|Write|NotebookEdit")
                            .put(
                                "hooks",
                                org.json.JSONArray().put(
                                    JSONObject().put("type", "command").put("command", "/opt/pocket/permission-hook.sh"),
                                ),
                            ),
                    ),
                ),
            )
            .toString()

        val settingsPaths = listOf(
            File(rootfs, "root/.claude/pocket-settings.json"),
            File(rootfs, "root/.claude/settings.json"),
            File(rootfs, "etc/claude/settings.json"),
        )
        for (target in settingsPaths) {
            target.parentFile?.mkdirs()
            target.writeText(settingsContent)
        }
        ensureWorkspaceTrust("/workspace")
    }

    private fun ensureWorkspaceTrust(workspacePath: String) {
        val stateFile = File(rootfs, "root/.claude.json")
        val state = runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }
        // Older alpha builds incorrectly wrote settings into Claude's state file.
        // Keep Claude's generated state, but remove only those stale settings keys.
        listOf("disableAllHooks", "permissions", "hooks", "allowedTools", "autoApprove")
            .forEach(state::remove)
        val projects = state.optJSONObject("projects") ?: JSONObject()
        val workspace = projects.optJSONObject(workspacePath) ?: JSONObject()
        workspace.put("hasTrustDialogAccepted", true)
        projects.put(workspacePath, workspace)
        state.put("projects", projects)
        stateFile.writeText(state.toString())
    }

    private fun claudeWorkspaceToolRules() = org.json.JSONArray().apply {
        put("Bash")
        put("Edit")
        put("Write")
        put("NotebookEdit")
        put("Read")
        put("Glob")
        put("Grep")
    }

    private fun writeResolver() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val dns = manager.getLinkProperties(manager.activeNetwork)?.dnsServers.orEmpty()
        val servers = dns.mapNotNull { it.hostAddress }.ifEmpty { listOf("8.8.8.8", "1.1.1.1") }
        File(rootfs, "etc/resolv.conf").writeText(servers.joinToString("\n") { "nameserver $it" } + "\n")
    }

    private fun extractRootfs(archive: File, destination: File) {
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val cleanName = entry.name.removePrefix("./")
                val target = safeChild(destination, cleanName)
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                        Os.symlink(entry.linkName, target.absolutePath)
                    }
                    entry.isLink -> {
                        target.parentFile?.mkdirs()
                        val linkTarget = safeChild(destination, entry.linkName.removePrefix("./"))
                        if (linkTarget.exists()) {
                            linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                        } else {
                            deferredLinks += target to linkTarget
                        }
                    }
                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output -> tar.copyTo(output) }
                        runCatching { Os.chmod(target.absolutePath, entry.mode and 0b111111111) }
                    }
                }
                entry = tar.nextEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile) { "Archive hard-link target is missing" }
            target.parentFile?.mkdirs()
            linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            runCatching { Os.chmod(target.absolutePath, android.system.Os.stat(linkTarget.absolutePath).st_mode) }
        }
    }

    private fun extractZstdTar(archive: File, destination: File) {
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(
            ZstdCompressorInputStream(BufferedInputStream(archive.inputStream())),
        ).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val cleanName = entry.name.removePrefix("./")
                if (cleanName.isNotBlank()) {
                    val target = safeChild(destination, cleanName)
                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            Os.symlink(entry.linkName, target.absolutePath)
                        }
                        entry.isLink -> {
                            target.parentFile?.mkdirs()
                            val linkTarget = safeChild(destination, entry.linkName.removePrefix("./"))
                            if (linkTarget.exists()) {
                                linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                            } else {
                                deferredLinks += target to linkTarget
                            }
                        }
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output -> tar.copyTo(output) }
                            runCatching { Os.chmod(target.absolutePath, entry.mode and 0b111111111) }
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile) { "Archive hard-link target is missing" }
            target.parentFile?.mkdirs()
            linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            runCatching { Os.chmod(target.absolutePath, android.system.Os.stat(linkTarget.absolutePath).st_mode) }
        }
    }

    private fun extractNodeArchive(archive: File, destination: File) {
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val relative = entry.name.removePrefix("./").substringAfter('/', "")
                if (relative.isNotBlank()) {
                    val target = safeChild(destination, relative)
                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            Os.symlink(entry.linkName, target.absolutePath)
                        }
                        entry.isLink -> {
                            val relativeLink = entry.linkName.removePrefix("./").substringAfter('/', "")
                            val linkTarget = safeChild(destination, relativeLink)
                            target.parentFile?.mkdirs()
                            if (linkTarget.exists()) {
                                linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                            } else {
                                deferredLinks += target to linkTarget
                            }
                        }
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output -> tar.copyTo(output) }
                            runCatching { Os.chmod(target.absolutePath, entry.mode and 0b111111111) }
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile) { "Node.js archive hard-link target is missing" }
            target.parentFile?.mkdirs()
            linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            runCatching { Os.chmod(target.absolutePath, android.system.Os.stat(linkTarget.absolutePath).st_mode) }
        }
    }

    private fun safeChild(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/')) { "Unsafe archive path" }
        val file = File(root, relative)
        val rootPath = root.canonicalFile.toPath()
        val parentPath = (file.parentFile ?: root).canonicalFile.toPath()
        require(parentPath.startsWith(rootPath)) { "Archive path escapes runtime" }
        return file
    }

    private suspend fun downloadVerified(
        url: String,
        destination: File,
        expectedChecksum: String,
        algorithm: String = "SHA-256",
        onBytes: suspend (downloaded: Long, total: Long) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        if (destination.isFile && digest(destination, algorithm).equals(expectedChecksum, ignoreCase = true)) {
            onBytes(destination.length(), destination.length())
            return
        }
        val temporary = File(destination.parentFile, "${destination.name}.part")
        var existing = temporary.takeIf(File::isFile)?.length() ?: 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        if (existing > 0L) connection.setRequestProperty("Range", "bytes=$existing-")
        check(connection.responseCode in 200..299) { "Download failed with HTTP ${connection.responseCode}" }
        val resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL && existing > 0L
        if (!resumed) {
            temporary.delete()
            existing = 0L
        }
        val total = connection.contentLengthLong.takeIf { it >= 0L }?.plus(existing) ?: -1L
        connection.inputStream.use { input ->
            FileOutputStream(temporary, resumed).use { output ->
                val buffer = ByteArray(128 * 1024)
                var downloaded = existing
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    onBytes(downloaded, total)
                }
            }
        }
        connection.disconnect()
        val actual = digest(temporary, algorithm)
        if (!actual.equals(expectedChecksum, ignoreCase = true)) {
            temporary.delete()
            error("Downloaded file checksum did not match")
        }
        destination.delete()
        check(temporary.renameTo(destination)) { "Could not finish download" }
    }

    private fun fetchText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/json")
        check(connection.responseCode in 200..299) { "Request failed with HTTP ${connection.responseCode}" }
        return connection.inputStream.bufferedReader().use { it.readText() }.also { connection.disconnect() }
    }

    private fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    companion object {
        const val AGY_GUEST_PATH = "/root/.local/bin/agy"
        const val GITHUB_CLI_GUEST_PATH = "/root/.local/bin/gh"
        private const val AGY_VERSION = "1.1.27"
        private const val AGY_RELEASE_URL = "https://storage.googleapis.com/antigravity-public/antigravity-cli/1.1.27-5211191891591168/linux-arm/cli_linux_arm64.tar.gz"
        private const val AGY_RELEASE_SHA512 = "ed45f6930785aa4b42f14e07ace1c9d91a94fb76e760f54acbd7d3d3951e1f957fd456a0dae2a3124dd9a3b689bf7afb7c9303a3e4ba95037fc10063424d9bf9"
        private const val GITHUB_CLI_VERSION = "2.100.0"
        private const val GITHUB_CLI_RELEASE_URL = "https://github.com/cli/cli/releases/download/v2.100.0/gh_2.100.0_linux_arm64.tar.gz"
        private const val GITHUB_CLI_RELEASE_SHA256 = "ea4e7a581a32ccad6cc7923cb1576ac5859ba4b9a16ab22eb8f8a96e78e2e961"
        private const val LEGACY_README = "# Pocket Dev project\n\nThis project is managed locally on Android.\n"
        private const val LEGACY_INDEX = "<!doctype html><title>Pocket Dev</title><h1>Hello from Android</h1>\n"
        private const val ROOTFS_VERSION = "ubuntu-20.04.5-arm64"
        private const val ROOTFS_FILE = "ubuntu-base-20.04.5-base-arm64.tar.gz"
        private const val ROOTFS_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/20.04/release/$ROOTFS_FILE"
        private const val ROOTFS_SHA256 = "f9b999afb4c4b10193087ea8c11be36d688f19e609b05179b571f29357954b52"
        private const val NODE_VERSION = "v24.19.0"
        private const val LANGUAGE_TOOLS_VERSION = "node-v24.19.0-python3-v1"
        private const val CORE_TOOLS_VERSION = "core-bundle-2026.09.5"
        private const val LEGACY_CORE_TOOLS_VERSION = "core-bundle-2026.09.4"
        private const val SYSTEM_UPGRADE_VERSION = "ubuntu-maintenance-v1"
        private const val ANDROID_TOOLS_VERSION = "sdk36-build-tools35-gradle8.14.3-maven-2026.09"
        private const val ANDROID_ASSET_BASE = "https://appdevforall.org/dev-assets/debug"
        private const val ANDROID_SDK_URL = "$ANDROID_ASSET_BASE/android-sdk-arm64-v8a.zip"
        private const val ANDROID_SDK_SHA256 = "bfe5bc940a7ede14735817a40962256666ce4152b9f3135f34a4ab9bccb87c3f"
        private const val ANDROID_GRADLE_URL = "$ANDROID_ASSET_BASE/gradle-8.14.3-bin.zip"
        private const val ANDROID_GRADLE_SHA256 = "8e228b640319a7c739c0a93d002facdeb08e8f3ba394d57d74ee355a5e93072c"
        private const val ANDROID_MAVEN_URL = "$ANDROID_ASSET_BASE/localMvnRepository.zip"
        private const val ANDROID_MAVEN_SHA256 = "3ba89892b43377d60743568d1b9004f172c2eab75dac059064f82dec497819f9"
        private const val ANDROID_AAPT2_PROPERTY = "android.aapt2FromMavenOverride"
        private const val ANDROID_AAPT2_GUEST_PATH = "/root/android-sdk/build-tools/35.0.0/aapt2"
        private const val ANDROID_AAPT2_HOST_PATH = "root/android-sdk/build-tools/35.0.0/aapt2"
        private val CLAUDE_VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
        /** Pinned DeepSeek Harness release installed via npm inside the guest (verified 2026-09-06). */
        const val DSH_VERSION = "0.1.2-rc.1"
        private const val DSH_ANDROID_COMPATIBILITY_VERSION = "copyfile-excl-v1"
        private val CORE_BUNDLE = RuntimeBundle(
            label = "Core",
            fileName = "pocketdev-core-arm64-2026.09.5.tar.zst",
            sha256 = "df0cf7251c74f82d424231e3804114a4ca66b16130eea9abab11e220dc7ac012",
            compressedBytes = 72_185_773L,
        )
        private const val CLAUDE_BUNDLED_VERSION = "2.1.263"
        private const val CLAUDE_GUEST_PATH = "/usr/local/bin/claude"
        private val CLAUDE_BUNDLE = RuntimeBundle(
            label = "Claude Code",
            fileName = "pocketdev-claude-arm64-2026.09.1.tar.zst",
            sha256 = "0f68e15630e8c0fc941afe3f61ab5a3eb4407b334018de6dbdabfa5eca627724",
            compressedBytes = 75_289_800L,
        )
        private val PYTHON_BUNDLE = RuntimeBundle(
            label = "Python",
            fileName = "pocketdev-python-arm64-2026.09.2.tar.zst",
            sha256 = "6b3f56f7743fec142bc045db3ea561ee83fe89af87c2799165ef60351164ef85",
            compressedBytes = 55_419_626L,
        )
        private val ANDROID_BUNDLE = RuntimeBundle(
            label = "Android",
            fileName = "pocketdev-android-arm64-2026.09.1.tar.zst",
            sha256 = "01bea058ebcb17416d1eb08c0211b3782da3228eb3c8348eb3719f2d61dd3ec6",
            compressedBytes = 569_652_007L,
        )
        private val DSH_BUNDLE = RuntimeBundle(
            label = "DeepSeek Harness",
            fileName = "pocketdev-dsh-arm64-2026.09.1.tar.zst",
            sha256 = "88e6a23ba74e1cd74a2c923b7e0d6bd78ba4b7e5f8a9b649f12bf4ffe158cce5",
            compressedBytes = 27_752_194L,
        )
        private val AGY_BUNDLE = RuntimeBundle(
            label = "Antigravity CLI",
            fileName = "pocketdev-agy-arm64-2026.09.1.tar.zst",
            sha256 = "a659ab9188956fc4721ca86fb21b5118e0e489f47a5e02ae6b4f2fb423659d78",
            compressedBytes = 41_870_025L,
        )
        private const val MAX_TERMINAL_LINE = 500
        private const val MAX_COLLECTED_OUTPUT = 24_000
        private val ANSI_ESCAPE = Regex("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))")
    }
}
