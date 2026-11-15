package com.jarves.mh.runtime

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
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
    private val ptyMaster: ParcelFileDescriptor? = null,
    @Volatile private var ptyRows: Int = 0,
    @Volatile private var ptyColumns: Int = 0,
    private val outputPump: Thread? = null,
) : Process() {
    @Volatile private var result: Int? = null

    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = FileInputStream(outputFile)
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int {
        result?.let { return it }
        return NativeSpawn.waitFor(pid, false).also {
            result = it
            awaitOutputPump(1_000)
        }
    }

    /** Waits for the PTY pump to finish writing. No-op for non-PTY processes. */
    internal fun awaitOutputPump(timeoutMillis: Long = 5_000) {
        outputPump?.join(timeoutMillis)
    }

    /**
     * Resizes the PTY and signals the child with SIGWINCH. Returns false when this is a
     * non-PTY process, the request is invalid, or the fd was already closed.
     */
    internal fun resizePty(rows: Int, columns: Int): Boolean {
        val master = ptyMaster ?: return false
        if (rows <= 0 || columns <= 0) return false
        if (rows == ptyRows && columns == ptyColumns) return true
        return runCatching {
            val fd = master.fd
            if (fd < 0) return@runCatching false
            val descriptor = master.fileDescriptor ?: return@runCatching false
            Os.fstat(descriptor)
            if (!NativeSpawn.resizePty(fd, rows, columns)) return@runCatching false
            ptyRows = rows
            ptyColumns = columns
            NativeSpawn.kill(pid, OsConstants.SIGWINCH)
            true
        }.getOrDefault(false)
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
            val spawned = NativeSpawn.spawn(
                argv.toTypedArray(),
                environment.map { "${it.key}=${it.value}" }.toTypedArray(),
                cwd,
                outputFile.absolutePath,
                pseudoTerminal,
                ptyRows,
                ptyColumns,
            )
            check(spawned.size == 3 && spawned[0] > 0) { "Native runtime launch failed" }
            val input = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(spawned[1]))
            val master = spawned[2].takeIf { it >= 0 }?.let { ParcelFileDescriptor.adoptFd(it) }
            val pump = master?.let { ptyMaster ->
                Thread({
                    runCatching {
                        ParcelFileDescriptor.AutoCloseInputStream(ptyMaster).use { source ->
                            FileOutputStream(outputFile, false).use { destination -> source.copyTo(destination) }
                        }
                    }
                }, "pocket-pty-output").apply {
                    isDaemon = true
                    start()
                }
            }
            return NativeSpawnProcess(
                pid = spawned[0],
                outputFile = outputFile,
                stdin = input,
                ptyMaster = master,
                ptyRows = if (pseudoTerminal) ptyRows else 0,
                ptyColumns = if (pseudoTerminal) ptyColumns else 0,
                outputPump = pump,
            )
        }
    }
}

private object NativeSpawn {
    const val STILL_RUNNING = -2

    init {
        System.loadLibrary("pocketspawn")
    }

    external fun spawn(
        argv: Array<String>,
        environment: Array<String>,
        cwd: String,
        outputFile: String,
        pseudoTerminal: Boolean,
        ptyRows: Int,
        ptyColumns: Int,
    ): IntArray
    external fun waitFor(pid: Int, noHang: Boolean): Int
    external fun kill(pid: Int, signal: Int): Int
    external fun resizePty(fd: Int, rows: Int, columns: Int): Boolean
}
