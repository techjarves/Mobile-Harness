package com.jarves.mh.runtime

import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

internal class NativeSpawnProcess private constructor(
    private val pid: Int,
    internal val outputFile: File,
    private val stdin: OutputStream,
    private val outputPump: Thread? = null,
) : Process() {
    @Volatile private var result: Int? = null

    override fun getOutputStream(): OutputStream = stdin

    /**
     * Streams the child's captured output file. Unlike a pipe, this stream has
     * *moving-EOF* semantics: `read()` returns -1 whenever the child has not
     * flushed more output yet — not only after the process exits. Never treat a
     * single EOF as "process finished"; combine it with [isAlive] / [waitFor],
     * or re-read with a growing offset as the bridges do.
     */
    override fun getInputStream(): InputStream = FileInputStream(outputFile)
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int {
        result?.let { return it }
        return NativeSpawn.waitFor(pid, false).also {
            result = it
            outputPump?.join(1_000)
        }
    }

    override fun exitValue(): Int {
        result?.let { return it }
        val status = NativeSpawn.waitFor(pid, true)
        if (status == NativeSpawn.STILL_RUNNING) throw IllegalThreadStateException("Process is still running")
        return status.also { result = it }
    }

    override fun destroy() {
        NativeSpawn.kill(pid, 15)
    }

    /** Send the same interrupt signal produced by Ctrl+C in a real terminal. */
    internal fun interrupt() {
        NativeSpawn.kill(pid, 2)
    }

    override fun destroyForcibly(): Process {
        NativeSpawn.kill(pid, 9)
        return this
    }

    override fun isAlive(): Boolean = runCatching { exitValue(); false }.getOrDefault(true)

    companion object {
        fun start(
            argv: List<String>,
            environment: Map<String, String>,
            cwd: String,
            outputFile: File,
            pseudoTerminal: Boolean = false,
            ptyRows: Int = 40,
            ptyColumns: Int = 120,
        ): NativeSpawnProcess {
            outputFile.parentFile?.mkdirs()
            if (pseudoTerminal) outputFile.delete()
            val spawned = SpawnResultValidator.validate(
                NativeSpawn.spawn(
                    argv.toTypedArray(),
                    environment.map { "${it.key}=${it.value}" }.toTypedArray(),
                    cwd,
                    outputFile.absolutePath,
                    pseudoTerminal,
                    ptyRows,
                    ptyColumns,
                ),
            ) ?: throw IllegalStateException(
                "The Linux runtime could not launch — the device may be low on memory. " +
                    "Close other apps and try again.",
            )
            val input = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(spawned.inputFd))
            val pump = spawned.outputFd.takeIf { it >= 0 }?.let { outputFd ->
                Thread({
                    runCatching {
                        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(outputFd)).use { source ->
                            FileOutputStream(outputFile, false).use { destination -> source.copyTo(destination) }
                        }
                    }
                }, "pocket-pty-output").apply {
                    isDaemon = true
                    start()
                }
            }
            return NativeSpawnProcess(spawned.pid, outputFile, input, pump)
        }
    }
}

private object NativeSpawn {
    const val STILL_RUNNING = -2

    init {
        System.loadLibrary("pocketspawn")
    }

    // Nullable on purpose (ISSUE-029): the native side returns NULL when
    // calloc/posix_openpt/fork fails under memory pressure.
    external fun spawn(
        argv: Array<String>,
        environment: Array<String>,
        cwd: String,
        outputFile: String,
        pseudoTerminal: Boolean,
        ptyRows: Int,
        ptyColumns: Int,
    ): IntArray?
    external fun waitFor(pid: Int, noHang: Boolean): Int
    external fun kill(pid: Int, signal: Int): Int
}

/**
 * Pure validation of JNI spawn results (ISSUE-029). Returned as a separate
 * object so the rules are unit-testable on the JVM without the native lib:
 * null (allocation or fork failure), a short array, or a non-positive pid are
 * all launch failures — the caller turns them into a friendly message instead
 * of an NPE crash.
 */
internal object SpawnResultValidator {
    data class Valid(val pid: Int, val inputFd: Int, val outputFd: Int)

    fun validate(spawned: IntArray?): Valid? = when {
        spawned == null -> null
        spawned.size != 3 -> null
        spawned[0] <= 0 -> null
        else -> Valid(pid = spawned[0], inputFd = spawned[1], outputFd = spawned[2])
    }
}
