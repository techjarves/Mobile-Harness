package dev.pocket.app.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject

data class InstalledRuntime(
    val proot: File,
    val rootfs: File,
    val claude: File,
    val version: String,
)

data class RuntimeInstallProgress(
    val message: String,
    val fraction: Float,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
)

class RuntimeInstaller(private val context: Context) {
    private val runtimeDir = File(context.filesDir, "runtime")
    private val rootfs = File(runtimeDir, "ubuntu")
    private val downloads = File(context.cacheDir, "runtime-downloads")
    private val marker = File(rootfs, ".pocket-runtime-ready")
    private val rootfsMarker = File(rootfs, ".pocket-rootfs-version")

    fun isInstalled(): Boolean {
        val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        return proot.canExecute() &&
            File(rootfs, "usr/bin/bash").exists() &&
            rootfsMarker.readTextOrNull() == ROOTFS_VERSION &&
            File(rootfs, "usr/local/bin/claude").exists() &&
            marker.exists()
    }

    /** Returns the already verified runtime without performing network or update checks. */
    fun installedRuntime(): InstalledRuntime {
        check(isInstalled()) { "Claude Code setup is incomplete. Reopen Pocket Dev to repair it." }
        return InstalledRuntime(
            proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so"),
            rootfs = rootfs,
            claude = File(rootfs, "usr/local/bin/claude"),
            version = marker.readText().trim(),
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

    suspend fun ensureInstalled(onProgress: suspend (RuntimeInstallProgress) -> Unit): InstalledRuntime {
        require(android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "Pocket runtime requires an ARM64 device" }
        val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        require(proot.canExecute()) { "The embedded PRoot launcher is unavailable" }

        if (!File(rootfs, "usr/bin/bash").exists() || rootfsMarker.readTextOrNull() != ROOTFS_VERSION) {
            onProgress(RuntimeInstallProgress("Downloading the private Ubuntu runtime", 0.03f))
            downloads.mkdirs()
            val archive = File(downloads, ROOTFS_FILE)
            downloadVerified(ROOTFS_URL, archive, ROOTFS_SHA256) { downloaded, total ->
                val ratio = if (total > 0) downloaded.toFloat() / total else 0f
                onProgress(RuntimeInstallProgress("Downloading Ubuntu", 0.03f + ratio * 0.22f, downloaded, total.takeIf { it > 0 }))
            }
            onProgress(RuntimeInstallProgress("Verifying and unpacking Ubuntu", 0.28f))
            val staging = File(runtimeDir, "ubuntu.installing")
            staging.deleteRecursively()
            staging.mkdirs()
            extractRootfs(archive, staging)
            File(staging, ".pocket-rootfs-version").writeText(ROOTFS_VERSION)
            rootfs.deleteRecursively()
            check(staging.renameTo(rootfs)) { "Could not activate the Linux environment" }
            writeResolver()
            archive.delete()
        }

        onProgress(RuntimeInstallProgress("Checking the latest Claude Code release", 0.36f))
        val version = fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest")
            .let { JSONObject(it).getString("version") }
            .also { require(it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) }
        val claude = File(rootfs, "usr/local/bin/claude")
        if (!marker.exists() || marker.readText().trim() != version || !claude.exists()) {
            onProgress(RuntimeInstallProgress("Downloading Claude Code $version from Anthropic", 0.40f))
            val base = "https://downloads.claude.ai/claude-code-releases/$version"
            val manifest = JSONObject(fetchText("$base/manifest.json"))
            val checksum = manifest.getJSONObject("platforms").getJSONObject("linux-arm64").getString("checksum")
            val staged = File(downloads, "claude-$version")
            downloadVerified("$base/linux-arm64/claude", staged, checksum) { downloaded, total ->
                val ratio = if (total > 0) downloaded.toFloat() / total else 0f
                onProgress(RuntimeInstallProgress("Downloading Claude Code $version", 0.40f + ratio * 0.54f, downloaded, total.takeIf { it > 0 }))
            }
            onProgress(RuntimeInstallProgress("Verifying Claude Code", 0.96f))
            claude.parentFile?.mkdirs()
            if (claude.exists()) claude.delete()
            check(staged.renameTo(claude)) { "Could not activate Claude Code" }
            Os.chmod(claude.absolutePath, 0b111101101)
            ensureSettingsAndHooks()
            marker.writeText(version)
        }

        // The binary and version manifest were already checksum-verified above. Running a
        // separate `claude --version` probe under PRoot can leave inherited output pipes
        // open on some Android kernels, so the real user session is the launch check.
        onProgress(RuntimeInstallProgress("Setup complete", 1f))
        return InstalledRuntime(proot, rootfs, claude, version)
    }

    suspend fun initializeExisting(onProgress: suspend (RuntimeInstallProgress) -> Unit): InstalledRuntime {
        val installed = installedRuntime()
        val proot = installed.proot
        val claude = installed.claude
        val version = installed.version
        onProgress(RuntimeInstallProgress("Checking private runtime files", 0.15f))
        writeResolver()
        ensureSettingsAndHooks()
        File(context.filesDir, "runtime-bridge").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        onProgress(RuntimeInstallProgress("Preparing the Android runtime bridge", 0.42f))
        val probe = process(proot, rootfs, File(rootfs, "root"), emptyMap(), listOf("/usr/local/bin/claude", "--version"))
        onProgress(RuntimeInstallProgress("Starting Claude Code $version", 0.68f))
        withTimeout(20_000) {
            while (probe.isAlive) delay(50)
        }
        val exit = probe.waitFor()
        val output = (probe as? NativeSpawnProcess)?.outputFile?.readText().orEmpty().trim()
        check(exit == 0) { output.ifBlank { "Claude Code initialization failed (exit $exit)" } }
        onProgress(RuntimeInstallProgress("Claude Code is ready", 1f))
        return installed
    }

    fun process(
        proot: File,
        rootfs: File,
        workspace: File,
        environment: Map<String, String>,
        guestCommand: List<String>,
    ): Process {
        workspace.mkdirs()
        val bridge = File(context.filesDir, "runtime-bridge").apply { mkdirs() }
        val args = buildList {
            add(proot.absolutePath)
            add("--link2symlink")
            add("-0")
            add("-r")
            add(rootfs.absolutePath)
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")
            add("-b")
            add("${workspace.absolutePath}:/workspace")
            add("-b")
            add("${bridge.absolutePath}:/pocket-bridge")
            add("-w")
            add("/workspace")
            addAll(guestCommand)
        }
        val prootTemp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
        return NativeSpawnProcess.start(
            argv = args,
            environment = buildMap {
                put("HOME", "/root")
                put("PATH", "/usr/local/bin:/usr/bin:/bin")
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
            outputFile = File(context.cacheDir, "runtime-output-${System.nanoTime()}.log"),
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
        ensureWorkspaceTrust()
    }

    private fun ensureWorkspaceTrust() {
        val stateFile = File(rootfs, "root/.claude.json")
        val state = runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }
        // Older alpha builds incorrectly wrote settings into Claude's state file.
        // Keep Claude's generated state, but remove only those stale settings keys.
        listOf("disableAllHooks", "permissions", "hooks", "allowedTools", "autoApprove")
            .forEach(state::remove)
        val projects = state.optJSONObject("projects") ?: JSONObject()
        val workspace = projects.optJSONObject("/workspace") ?: JSONObject()
        workspace.put("hasTrustDialogAccepted", true)
        projects.put("/workspace", workspace)
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
        expectedSha256: String,
        onBytes: suspend (downloaded: Long, total: Long) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.part")
        temporary.delete()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        check(connection.responseCode in 200..299) { "Download failed with HTTP ${connection.responseCode}" }
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(128 * 1024)
                var downloaded = 0L
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
        val actual = sha256(temporary)
        check(actual.equals(expectedSha256, ignoreCase = true)) { "Downloaded file checksum did not match" }
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
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
        private const val LEGACY_README = "# Pocket Dev project\n\nThis project is managed locally on Android.\n"
        private const val LEGACY_INDEX = "<!doctype html><title>Pocket Dev</title><h1>Hello from Android</h1>\n"
        private const val ROOTFS_VERSION = "ubuntu-20.04.5-arm64"
        private const val ROOTFS_FILE = "ubuntu-base-20.04.5-base-arm64.tar.gz"
        private const val ROOTFS_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/20.04/release/$ROOTFS_FILE"
        private const val ROOTFS_SHA256 = "f9b999afb4c4b10193087ea8c11be36d688f19e609b05179b571f29357954b52"
    }
}
