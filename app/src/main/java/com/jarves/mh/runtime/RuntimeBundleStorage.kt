package com.jarves.mh.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption

internal fun runtimeBundleDownloadFile(
    cacheDownloads: File,
    noBackupDir: File,
    bundleLabel: String,
    fileName: String,
): File {
    // A cache trim may run between the last write, verification, and unpacking.
    val directory = File(noBackupDir, "runtime-downloads").resolve(bundleLabel)
    Files.createDirectories(directory.toPath())
    val archive = directory.resolve(fileName)
    val partial = directory.resolve("$fileName.part")
    directory.listFiles()?.forEach { file ->
        if (file.isFile && file != archive && file != partial) file.delete()
    }

    // Resume downloads started before bundle staging moved out of the cache.
    for (target in listOf(archive, partial)) {
        val legacy = cacheDownloads.resolve(target.name)
        if (legacy.isFile && !target.exists()) {
            try {
                Files.move(legacy.toPath(), target.toPath())
            } catch (_: NoSuchFileException) {
                // Android reclaimed the cached file. Start this download afresh.
            }
        }
    }
    return archive
}

internal fun reuseVerifiedDownload(
    archive: File,
    partial: File,
    expectedBytes: Long?,
    matchesChecksum: (File) -> Boolean,
): Boolean {
    if (archive.isFile && matchesChecksum(archive)) {
        partial.delete()
        return true
    }
    if (expectedBytes == null || !partial.isFile) return false
    val length = partial.length()
    if (length < expectedBytes) return false

    // After a restart a complete partial needs verification, not another Range request.
    if (length == expectedBytes && matchesChecksum(partial)) {
        Files.move(partial.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return true
    }
    Files.delete(partial.toPath())
    return false
}
