package com.jarves.mh.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class NotEnoughSpaceException(val requiredBytes: Long) : IOException("Not enough free storage to restore this backup")

/** App data the backup reads and replaces that is not plain files (SharedPreferences, Keystore vault). */
interface BackupStores {
    fun exportPrefs(): JSONObject
    fun importPrefs(prefs: JSONObject)
    fun exportSecrets(): JSONObject
    fun importSecrets(secrets: JSONObject)
}

enum class BackupStage { SCANNING, WRITING, READING, APPLYING }

data class BackupProgress(val stage: BackupStage, val doneBytes: Long = 0, val totalBytes: Long = 0, val files: Int = 0)

data class BackupSummary(
    val projects: Int,
    val files: Int,
    val bytes: Long,
    val includesSecrets: Boolean,
    /** Linux login files waiting for the runtime to be installed before they can be copied in. */
    val homePending: Boolean = false,
)

/**
 * Builds and restores `.mhbackup` files: projects, chats, settings and — only when the
 * backup is password protected — API keys plus the Linux login files listed in [HOME_ENTRIES].
 */
class BackupManager(
    private val filesDir: File,
    private val stores: BackupStores,
    private val appVersionName: String,
    private val appVersionCode: Int,
    /** Appended to a restored project's name when the same project is already on the phone. */
    private val restoredNameSuffix: String = " (restored)",
) {
    private data class Item(val name: String, val file: File)

    fun createBackup(
        output: OutputStream,
        password: CharArray?,
        checkCancelled: () -> Unit = {},
        progress: (BackupProgress) -> Unit = {},
    ): BackupSummary {
        progress(BackupProgress(BackupStage.SCANNING))
        val includeSecrets = password != null
        val directories = mutableListOf<String>()
        val items = collect(includeSecrets, directories, checkCancelled)
        val total = items.sumOf { it.file.length() }
        val prefs = stores.exportPrefs()
        val projects = projectCount(prefs)
        val executables = JSONArray()
        var done = 0L
        var written = 0
        ZipOutputStream(BackupCrypto.openWriter(output.buffered(), password)).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            zip.putJson(
                MANIFEST,
                JSONObject()
                    .put("formatVersion", BackupCrypto.FORMAT_VERSION)
                    .put("appVersionName", appVersionName)
                    .put("appVersionCode", appVersionCode)
                    .put("createdAtMillis", System.currentTimeMillis())
                    .put("includesSecrets", includeSecrets)
                    .put("projects", projects)
                    .put("files", items.size)
                    .put("totalBytes", total),
            )
            zip.putJson(PREFS, prefs)
            if (includeSecrets) zip.putJson(SECRETS, stores.exportSecrets())
            directories.forEach { name ->
                zip.putNextEntry(ZipEntry("$name/"))
                zip.closeEntry()
            }
            progress(BackupProgress(BackupStage.WRITING, 0, total, 0))
            items.forEach { item ->
                checkCancelled()
                val input = try {
                    item.file.inputStream()
                } catch (_: FileNotFoundException) {
                    return@forEach // Deleted since the scan.
                }
                zip.putNextEntry(ZipEntry(item.name).apply { time = item.file.lastModified() })
                input.buffered().use { source ->
                    copy(source, zip, checkCancelled) { count ->
                        done += count
                        progress(BackupProgress(BackupStage.WRITING, done, total, written))
                    }
                }
                zip.closeEntry()
                if (item.file.canExecute()) executables.put(item.name)
                written++
            }
            zip.putJson(FINISH, JSONObject().put("files", written).put("executables", executables))
        }
        return BackupSummary(projects, written, done, includeSecrets)
    }

    /**
     * Adds the backup's projects (files, chats, terminal history, build records) next to the
     * ones already here; nothing on the phone is deleted. Settings, API keys and Linux logins
     * replace this phone's only when [includeSettings] is set.
     */
    fun restore(
        input: InputStream,
        password: CharArray?,
        includeSettings: Boolean = false,
        checkCancelled: () -> Unit = {},
        progress: (BackupProgress) -> Unit = {},
    ): BackupSummary {
        progress(BackupProgress(BackupStage.READING))
        val staging = File(filesDir, STAGING_DIR)
        staging.deleteRecursively()
        staging.mkdirs()
        val stagingPath = staging.canonicalFile.toPath()
        try {
            var manifest: JSONObject? = null
            var prefs: JSONObject? = null
            var secrets: JSONObject? = null
            var finish: JSONObject? = null
            var total = 0L
            var done = 0L
            var files = 0
            val plain = BackupCrypto.openReader(input.buffered(), password)
            val zip = ZipInputStream(plain)
            while (true) {
                checkCancelled()
                val entry = zip.nextEntry ?: break
                val name = entry.name
                require(name.isNotBlank() && '\u0000' !in name && !name.startsWith("/") && ".." !in name.split('/')) {
                    "Unsafe path in backup"
                }
                when {
                    name == MANIFEST -> {
                        manifest = zip.readJson().also { value ->
                            if (value.optInt("formatVersion") > BackupCrypto.FORMAT_VERSION) throw NewerBackupException()
                            total = value.optLong("totalBytes")
                            val required = total + total / 10
                            if (staging.usableSpace in 0 until required) throw NotEnoughSpaceException(required)
                        }
                        progress(BackupProgress(BackupStage.READING, 0, total, 0))
                    }
                    name == PREFS -> prefs = zip.readJson()
                    name == SECRETS -> secrets = zip.readJson()
                    name == FINISH -> finish = zip.readJson()
                    name.startsWith("$FILES_PREFIX/") || name.startsWith("$HOME_PREFIX/") -> {
                        if (manifest == null) throw InvalidBackupException("Backup file is damaged")
                        val target = File(staging, name).canonicalFile
                        require(target.toPath().startsWith(stagingPath)) { "Unsafe path in backup" }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { output ->
                                copy(zip, output, checkCancelled) { count ->
                                    done += count
                                    progress(BackupProgress(BackupStage.READING, done, total, files))
                                }
                            }
                            if (entry.time > 0) target.setLastModified(entry.time)
                            files++
                        }
                    }
                }
                zip.closeEntry()
            }
            // Read past the zip's central directory so the final encrypted segment is verified.
            val sink = ByteArray(DEFAULT_BUFFER_SIZE)
            while (plain.read(sink) >= 0) Unit
            plain.close()

            val restoredPrefs = prefs
            if (manifest == null || restoredPrefs == null || finish == null || finish.optInt("files", -1) != files) {
                throw InvalidBackupException("Backup file is incomplete")
            }
            checkCancelled()
            finish.optJSONArray("executables")?.let { list ->
                for (index in 0 until list.length()) {
                    val file = File(staging, list.optString(index)).canonicalFile
                    if (file.toPath().startsWith(stagingPath) && file.isFile) file.setExecutable(true, false)
                }
            }

            // Everything is verified and on disk. From here on the restore must not stop halfway.
            progress(BackupProgress(BackupStage.APPLYING, total, total, files))
            val currentPrefs = stores.exportPrefs()
            val merged = JSONArray()
            projects(currentPrefs).forEach { merged.put(it) }
            val usedIds = projects(currentPrefs).mapTo(mutableSetOf()) { it.optString("id") }
            var added = 0
            projects(restoredPrefs).forEach { project ->
                val oldId = project.optString("id")
                if (oldId.isBlank() || !isSafeId(oldId)) return@forEach
                val copy = oldId in usedIds
                val newId = if (copy) UUID.randomUUID().toString() else oldId
                usedIds += newId
                val entry = JSONObject(project.toString()).put("id", newId)
                if (copy) {
                    entry.put("name", project.optString("name") + restoredNameSuffix)
                    entry.remove("slug") // Re-derived from the new name; the loader keeps slugs unique.
                }
                moveProject(staging, oldId, newId)
                merged.put(entry)
                added++
            }

            val finalPrefs = if (includeSettings) restoredPrefs else currentPrefs
            if (includeSettings) {
                // Keep agent sessions of the projects that were already on this phone.
                currentPrefs.keys().forEach { key ->
                    if (key.startsWith("agent_conversation_") && !finalPrefs.has(key)) finalPrefs.put(key, currentPrefs.get(key))
                }
            }
            finalPrefs.put("projects_json", JSONObject().put("t", "string").put("v", merged.toString()))
            stores.importPrefs(finalPrefs)

            var homePending = false
            if (includeSettings) {
                secrets?.let(stores::importSecrets)
                val stagedHome = File(staging, HOME_PREFIX)
                if (stagedHome.isDirectory) {
                    val pending = File(filesDir, PENDING_HOME_DIR)
                    pending.deleteRecursively()
                    pending.parentFile?.mkdirs()
                    check(stagedHome.renameTo(pending)) { "Could not save Linux login files" }
                    homePending = !applyPendingHome(filesDir)
                }
            }
            return BackupSummary(added, files, done, includeSettings && secrets != null, homePending)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Moves one project's staged data into place under [newId]. */
    private fun moveProject(staging: File, oldId: String, newId: String) {
        val staged = File(staging, FILES_PREFIX)
        listOf(
            "workspaces/$oldId" to "workspaces/$newId",
            "chats/$oldId" to "chats/$newId",
            "chats/$oldId.json" to "chats/$newId.json",
            "terminal-history/$oldId.json" to "terminal-history/$newId.json",
            "android-builds/$oldId.json" to "android-builds/$newId.json",
        ).forEach { (from, to) ->
            val source = File(staged, from)
            val destination = File(filesDir, to)
            if (!source.exists()) return@forEach
            destination.deleteRecursively() // Only a leftover with no project entry can sit here.
            destination.parentFile?.mkdirs()
            if (!source.renameTo(destination)) {
                check(source.copyRecursively(destination, overwrite = true)) { "Could not restore $to" }
            }
        }
        File(filesDir, "workspaces/$newId").mkdirs()
    }

    private fun projects(prefs: JSONObject): List<JSONObject> = runCatching {
        val array = JSONArray(prefs.getJSONObject("projects_json").getString("v"))
        (0 until array.length()).mapNotNull(array::optJSONObject)
    }.getOrDefault(emptyList())

    private fun isSafeId(id: String) = id.none { it == '/' || it == '\\' || it == '\u0000' } && id != "." && id != ".."

    private fun collect(includeHome: Boolean, directories: MutableList<String>, checkCancelled: () -> Unit): List<Item> {
        val items = mutableListOf<Item>()
        DATA_DIRS.forEach { directory ->
            val root = File(filesDir, directory)
            if (!root.isDirectory) return@forEach
            walk(root, "$FILES_PREFIX/$directory", items, directories, checkCancelled) { dir ->
                !(directory == "workspaces" && dir.parentFile == root && dir.name in SCRATCH_WORKSPACES) && !isRegenerable(dir)
            }
        }
        if (includeHome) {
            val home = File(filesDir, RUNTIME_HOME)
            if (home.isDirectory) {
                HOME_ENTRIES.forEach { relative ->
                    val source = File(home, relative)
                    when {
                        Files.isSymbolicLink(source.toPath()) -> Unit
                        source.isFile -> items += Item("$HOME_PREFIX/$relative", source)
                        source.isDirectory -> walk(source, "$HOME_PREFIX/$relative", items, directories, checkCancelled) { dir ->
                            dir.name !in HOME_SKIPPED_DIRECTORIES
                        }
                    }
                }
            }
        }
        return items
    }

    private fun walk(
        root: File,
        prefix: String,
        items: MutableList<Item>,
        directories: MutableList<String>,
        checkCancelled: () -> Unit,
        includeDirectory: (File) -> Boolean,
    ) {
        root.walkTopDown()
            .onEnter { dir -> dir == root || (!Files.isSymbolicLink(dir.toPath()) && includeDirectory(dir)) }
            .forEach { file ->
                checkCancelled()
                if (Files.isSymbolicLink(file.toPath())) return@forEach
                val relative = file.relativeTo(root).invariantSeparatorsPath
                val name = if (relative.isEmpty()) prefix else "$prefix/$relative"
                when {
                    file.isDirectory -> directories += name
                    file.isFile -> items += Item(name, file)
                }
            }
    }

    private fun projectCount(prefs: JSONObject): Int = projects(prefs).size

    private fun ZipOutputStream.putJson(name: String, value: JSONObject) {
        putNextEntry(ZipEntry(name))
        write(value.toString().toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipInputStream.readJson(): JSONObject {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            bytes.write(buffer, 0, count)
            if (bytes.size() > MAX_JSON_BYTES) throw InvalidBackupException("Backup file is damaged")
        }
        return JSONObject(bytes.toString(Charsets.UTF_8.name()))
    }

    private fun copy(input: InputStream, output: OutputStream, checkCancelled: () -> Unit, onBytes: (Int) -> Unit) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            onBytes(count)
            checkCancelled()
        }
    }

    companion object {
        private const val MANIFEST = "manifest.json"
        private const val PREFS = "prefs.json"
        private const val SECRETS = "secrets.json"
        private const val FINISH = "meta/finish.json"
        private const val FILES_PREFIX = "files"
        private const val HOME_PREFIX = "home"
        private const val STAGING_DIR = "restore-staging"
        private const val PENDING_HOME_DIR = "restore-pending/home"
        private const val RUNTIME_HOME = "runtime/ubuntu/root"
        private const val MAX_JSON_BYTES = 64L * 1024 * 1024
        private const val COPY_BUFFER_BYTES = 64 * 1024

        /** App-private folders holding the user's work. */
        val DATA_DIRS = listOf("workspaces", "chats", "terminal-history", "android-builds")

        /** Temporary agent/login sandboxes under workspaces/ that are recreated on demand. */
        private val SCRATCH_WORKSPACES = setOf("github-auth", "antigravity-auth", "antigravity-models")

        /** Linux logins and personal config under /root, restored onto a freshly installed runtime. */
        val HOME_ENTRIES = listOf(
            ".config/gh",
            ".gitconfig",
            ".git-credentials",
            ".ssh",
            ".claude",
            ".claude.json",
            ".gemini/antigravity-cli/antigravity-oauth-token",
            ".bashrc",
            ".profile",
            ".npmrc",
        )
        private val HOME_SKIPPED_DIRECTORIES = setOf("cache", "debug", "statsig", "shell-snapshots", "ide", "downloads")

        /**
         * Folders a build or package manager recreates. Ambiguous names only count when the
         * project file that produces them sits next to them, so a hand-written `build/` survives.
         */
        fun isRegenerable(dir: File): Boolean {
            val parent = dir.parentFile ?: return false
            fun besides(vararg names: String) = names.any { File(parent, it).exists() }
            return when (dir.name) {
                "node_modules", ".gradle", "__pycache__", ".next", ".nuxt", ".dart_tool", ".pytest_cache",
                ".mypy_cache", ".turbo", ".parcel-cache", ".cxx", ".kotlin", ".svelte-kit" -> true
                ".venv", "venv", "env" -> File(dir, "pyvenv.cfg").exists()
                "build" -> besides("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "pubspec.yaml")
                "dist", ".cache" -> besides("package.json")
                "target" -> besides("Cargo.toml", "pom.xml")
                else -> false
            }
        }

        /**
         * Copies restored Linux login files into /root once the runtime exists.
         * Returns false while they are still waiting for runtime setup.
         */
        fun applyPendingHome(filesDir: File): Boolean {
            val pending = File(filesDir, PENDING_HOME_DIR)
            if (!pending.isDirectory) return true
            val home = File(filesDir, RUNTIME_HOME)
            if (!home.isDirectory) return false
            pending.walkTopDown().forEach { source ->
                val target = File(home, source.relativeTo(pending).path)
                if (source.isDirectory) {
                    target.mkdirs()
                    target.setOwnerOnly(directory = true)
                } else {
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                    val executable = source.canExecute()
                    target.setOwnerOnly(directory = false)
                    if (executable) target.setExecutable(true, true)
                    target.setLastModified(source.lastModified())
                }
            }
            pending.parentFile?.deleteRecursively()
            return true
        }

        private fun File.setOwnerOnly(directory: Boolean) {
            setReadable(false, false)
            setReadable(true, true)
            setWritable(false, false)
            setWritable(true, true)
            setExecutable(false, false)
            if (directory) setExecutable(true, true)
        }
    }
}
