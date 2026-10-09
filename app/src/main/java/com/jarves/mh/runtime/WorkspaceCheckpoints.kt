package com.jarves.mh.runtime

import android.content.Context
import com.jarves.mh.R
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.ui.AppStrings
import com.jarves.mh.model.DiffLine
import com.jarves.mh.model.DiffLineType
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray

/**
 * Workspace checkpoint / snapshot / diff store shared by agent bridges.
 *
 * Each project workspace gets a baseline copy before a session runs; after the
 * run the baseline is diffed to produce reviewable [ChangeItem]s with
 * per-file Undo/Keep. Semantics mirror the original Claude bridge store so
 * both agents behave identically in the Changes tab.
 */
class WorkspaceCheckpoints(
    private val filesDir: File,
    /** Optional: when present, informational diff rows use the language picked in the app. */
    private val context: Context? = null,
) {
    private fun text(@androidx.annotation.StringRes id: Int, fallback: String, vararg args: Any): String =
        context?.let { AppStrings.get(it, id, *args) } ?: fallback

    private val projectRoots = ConcurrentHashMap<String, String>()

    fun ensureWorkspace(projectId: String): File {
        val base = File(filesDir, "workspaces/$projectId").apply { mkdirs() }.canonicalFile
        val rootPath = projectRoots[projectId].orEmpty()
        if (rootPath.isBlank()) return base
        val selected = File(base, rootPath).canonicalFile
        require(selected.toPath().startsWith(base.toPath())) { "Unsafe project root" }
        return selected.apply { mkdirs() }
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        val normalized = rootPath.trim().trim('/')
        require(normalized.isBlank() || (!normalized.contains("..") && !normalized.startsWith('/'))) {
            "Unsafe project root"
        }
        val previous = projectRoots.put(projectId, normalized).orEmpty()
        if (previous != normalized) checkpointDir(projectId).deleteRecursively()
    }

    fun checkpointDir(projectId: String) = File(filesDir, "checkpoints/$projectId/latest")

    fun createCheckpoint(projectId: String, workspace: File) {
        val checkpoint = checkpointDir(projectId)
        // Keep the original baseline until every pending file is accepted or undone.
        if (File(checkpoint, "project").isDirectory && File(checkpoint, "changes.json").isFile) return
        checkpoint.deleteRecursively()
        val backup = File(checkpoint, "project").apply { mkdirs() }
        val workspacePath = workspace.canonicalFile.toPath()
        workspace.walkTopDown()
            .onEnter { directory ->
                val relative = if (directory == workspace) "" else directory.relativeTo(workspace).invariantSeparatorsPath
                directory == workspace || (!isInternalRuntimePath(relative) &&
                    !java.nio.file.Files.isSymbolicLink(directory.toPath()) &&
                        runCatching { directory.canonicalFile.toPath().startsWith(workspacePath) }.getOrDefault(false)
                    )
            }
            .filter {
                it.isFile &&
                    !isInternalRuntimePath(it.relativeTo(workspace).invariantSeparatorsPath) &&
                    !java.nio.file.Files.isSymbolicLink(it.toPath())
            }
            .forEach { source ->
                val relative = source.relativeTo(workspace).invariantSeparatorsPath
                val destination = safeWorkspaceFile(backup, relative)
                destination.parentFile?.mkdirs()
                source.copyTo(destination, overwrite = true)
            }
    }

    fun saveChangedPaths(projectId: String, paths: List<String>) {
        val manifest = File(checkpointDir(projectId), "changes.json")
        manifest.parentFile?.mkdirs()
        val merged = (readChangedPaths(projectId) + paths)
            .filterNot(::isInternalRuntimePath)
            .distinct()
            .sorted()
        manifest.writeText(JSONArray(merged).toString())
    }

    fun readChangedPaths(projectId: String): List<String> {
        val manifest = File(checkpointDir(projectId), "changes.json")
        if (!manifest.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(manifest.readText())
            (0 until array.length()).map(array::getString)
        }.getOrDefault(emptyList())
    }

    fun removeChangedPath(projectId: String, path: String) {
        val remaining = readChangedPaths(projectId).filterNot { it == path }
        if (remaining.isEmpty()) {
            checkpointDir(projectId).deleteRecursively()
        } else {
            File(checkpointDir(projectId), "changes.json").writeText(JSONArray(remaining).toString())
        }
    }

    fun buildChangeDetails(projectId: String, workspace: File, paths: List<String>): List<ChangeItem> {
        val backup = File(checkpointDir(projectId), "project")
        return paths.map { path ->
            val beforeFile = safeWorkspaceFile(backup, path).takeIf(File::isFile)
            val afterFile = safeWorkspaceFile(workspace, path).takeIf(File::isFile)
            if (listOfNotNull(beforeFile, afterFile).any { it.length() > MAX_DIFF_FILE_BYTES }) {
                return@map ChangeItem(
                    path = path,
                    additions = 0,
                    deletions = 0,
                    diffLines = listOf(
                        DiffLine(
                            DiffLineType.INFO,
                            text(R.string.rt_diff_file_too_large, "File is too large to preview safely. Undo and Keep still work."),
                        ),
                    ),
                )
            }
            val before = beforeFile?.readBytes() ?: ByteArray(0)
            val after = afterFile?.readBytes() ?: ByteArray(0)
            val binary = before.any { it == 0.toByte() } || after.any { it == 0.toByte() }
            val (additions, deletions) = lineChanges(before, after)
            ChangeItem(
                path = path,
                additions = additions,
                deletions = deletions,
                diffLines = buildDiffLines(before, after),
                binary = binary,
            )
        }
    }

    fun buildDiffLines(beforeBytes: ByteArray, afterBytes: ByteArray): List<DiffLine> {
        if (beforeBytes.any { it == 0.toByte() } || afterBytes.any { it == 0.toByte() }) {
            return listOf(DiffLine(DiffLineType.INFO, text(R.string.rt_diff_binary_changed, "Binary file changed")))
        }
        val before = textLines(beforeBytes)
        val after = textLines(afterBytes)
        if (before.size > MAX_RENDERED_DIFF_LINES || after.size > MAX_RENDERED_DIFF_LINES) {
            return listOf(
                DiffLine(
                    DiffLineType.INFO,
                    text(
                        R.string.rt_diff_too_large,
                        "Diff is too large to display (${before.size} → ${after.size} lines). Undo and Keep still work.",
                        before.size,
                        after.size,
                    ),
                ),
            )
        }

        val lcs = Array(before.size + 1) { IntArray(after.size + 1) }
        for (oldIndex in before.lastIndex downTo 0) {
            for (newIndex in after.lastIndex downTo 0) {
                lcs[oldIndex][newIndex] = if (before[oldIndex] == after[newIndex]) {
                    lcs[oldIndex + 1][newIndex + 1] + 1
                } else {
                    maxOf(lcs[oldIndex + 1][newIndex], lcs[oldIndex][newIndex + 1])
                }
            }
        }

        val result = mutableListOf<DiffLine>()
        var oldIndex = 0
        var newIndex = 0
        while (oldIndex < before.size || newIndex < after.size) {
            when {
                oldIndex < before.size && newIndex < after.size && before[oldIndex] == after[newIndex] -> {
                    result += DiffLine(DiffLineType.CONTEXT, before[oldIndex], oldIndex + 1, newIndex + 1)
                    oldIndex++
                    newIndex++
                }
                newIndex < after.size && (oldIndex == before.size || lcs[oldIndex][newIndex + 1] >= lcs[oldIndex + 1][newIndex]) -> {
                    result += DiffLine(DiffLineType.ADDITION, after[newIndex], null, newIndex + 1)
                    newIndex++
                }
                oldIndex < before.size -> {
                    result += DiffLine(DiffLineType.DELETION, before[oldIndex], oldIndex + 1, null)
                    oldIndex++
                }
            }
        }
        return collapseUnchangedLines(result)
    }

    private fun collapseUnchangedLines(lines: List<DiffLine>): List<DiffLine> {
        val changedIndexes = lines.indices.filter { lines[it].type != DiffLineType.CONTEXT }
        if (changedIndexes.isEmpty()) return lines
        val visible = BooleanArray(lines.size)
        changedIndexes.forEach { changed ->
            for (index in maxOf(0, changed - DIFF_CONTEXT_LINES)..minOf(lines.lastIndex, changed + DIFF_CONTEXT_LINES)) {
                visible[index] = true
            }
        }
        val result = mutableListOf<DiffLine>()
        var index = 0
        while (index < lines.size) {
            if (visible[index]) {
                result += lines[index++]
            } else {
                val start = index
                while (index < lines.size && !visible[index]) index++
                result += DiffLine(DiffLineType.INFO, "… ${index - start} unchanged lines …")
            }
        }
        return result
    }

    private fun lineChanges(beforeBytes: ByteArray, afterBytes: ByteArray): Pair<Int, Int> {
        if (beforeBytes.any { it == 0.toByte() } || afterBytes.any { it == 0.toByte() }) {
            return (if (afterBytes.isNotEmpty()) 1 else 0) to (if (beforeBytes.isNotEmpty()) 1 else 0)
        }
        val before = textLines(beforeBytes)
        val after = textLines(afterBytes)
        if (before.size > MAX_DIFF_LINES || after.size > MAX_DIFF_LINES) {
            return maxOf(0, after.size - before.size) to maxOf(0, before.size - after.size)
        }
        var previous = IntArray(after.size + 1)
        before.forEach { oldLine ->
            val current = IntArray(after.size + 1)
            after.forEachIndexed { index, newLine ->
                current[index + 1] = if (oldLine == newLine) {
                    previous[index] + 1
                } else {
                    maxOf(previous[index + 1], current[index])
                }
            }
            previous = current
        }
        val common = previous[after.size]
        return (after.size - common) to (before.size - common)
    }

    private fun textLines(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        val lines = bytes.decodeToString().split('\n')
        return if (lines.lastOrNull().isNullOrEmpty()) lines.dropLast(1) else lines
    }

    fun safeWorkspaceFile(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/')) { "Unsafe workspace path" }
        val file = File(root, relative)
        val rootPath = root.canonicalFile.toPath()
        val parentPath = (file.parentFile ?: root).canonicalFile.toPath()
        require(parentPath.startsWith(rootPath)) { "Workspace path escapes project" }
        return file
    }

    fun snapshot(root: File): Map<String, String> = root.walkTopDown()
        .onEnter { directory ->
            directory == root || !isInternalRuntimePath(directory.relativeTo(root).invariantSeparatorsPath)
        }
        .filter { it.isFile && !isInternalRuntimePath(it.relativeTo(root).invariantSeparatorsPath) }
        .associate { it.relativeTo(root).path to digest(it) }

    fun changedFiles(root: File, before: Map<String, String>): List<String> {
        val after = snapshot(root)
        return (before.keys + after.keys).distinct().filter { before[it] != after[it] }.sorted()
    }

    fun isInternalRuntimePath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized == ".claude.json" || IGNORED_DIRECTORY_NAMES.any { directory ->
            normalized == directory || normalized.startsWith("$directory/") || normalized.contains("/$directory/")
        }
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_DIFF_LINES = 2_000
        private const val MAX_RENDERED_DIFF_LINES = 600
        private const val MAX_DIFF_FILE_BYTES = 1_000_000L
        private const val DIFF_CONTEXT_LINES = 3
        private val IGNORED_DIRECTORY_NAMES = setOf(
            ".claude",
            ".git",
            ".gradle",
            ".idea",
            ".next",
            ".dart_tool",
            ".venv",
            "build",
            "dist",
            "node_modules",
            "target",
            "venv",
        )
    }
}
