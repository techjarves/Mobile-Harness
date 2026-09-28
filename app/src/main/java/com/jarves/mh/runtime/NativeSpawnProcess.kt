package com.jarves.mh.runtime

import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

internal class NativeSpawnProcess private constructor(
    private val delegateProcess: Process?,
    private val pid: Int,
    internal val outputFile: File,
    private val stdin: OutputStream,
    private val outputPump: Thread? = null,
) : Process() {
    @Volatile private var result: Int? = null

    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = delegateProcess?.inputStream ?: FileInputStream(outputFile)
    override fun getErrorStream(): InputStream = delegateProcess?.errorStream ?: ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int {
        result?.let { return it }
        if (delegateProcess != null) {
            return delegateProcess.waitFor().also {
                result = it
                outputPump?.join(1_000)
            }
        }
        return NativeSpawn.waitFor(pid, false).also {
            result = it
            outputPump?.join(1_000)
        }
    }

    override fun exitValue(): Int {
        result?.let { return it }
        if (delegateProcess != null) {
            return delegateProcess.exitValue().also { result = it }
        }
        val status = NativeSpawn.waitFor(pid, true)
        if (status == NativeSpawn.STILL_RUNNING) throw IllegalThreadStateException("Process is still running")
        return status.also { result = it }
    }

    override fun destroy() {
        if (delegateProcess != null) {
            delegateProcess.destroy()
        } else {
            NativeSpawn.kill(pid, 15)
        }
    }

    /** Send the same interrupt signal produced by Ctrl+C in a real terminal. */
    internal fun interrupt() {
        if (delegateProcess != null) {
            delegateProcess.destroy()
        } else {
            NativeSpawn.kill(pid, 2)
        }
    }

    override fun destroyForcibly(): Process {
        if (delegateProcess != null) {
            delegateProcess.destroyForcibly()
        } else {
            NativeSpawn.kill(pid, 9)
        }
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
            if (NativeSpawn.isAvailable) {
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
                val pump = spawned[2].takeIf { it >= 0 }?.let { outputFd ->
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
                return NativeSpawnProcess(null, spawned[0], outputFile, input, pump)
            } else {
                val pb = ProcessBuilder(argv)
                val cwdFile = File(cwd)
                if (cwdFile.isDirectory) {
                    pb.directory(cwdFile)
                }
                pb.environment().putAll(environment)
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val pump = Thread({
                    runCatching {
                        proc.inputStream.use { source ->
                            FileOutputStream(outputFile, true).use { destination ->
                                source.copyTo(destination)
                            }
                        }
                    }
                }, "process-output-pump").apply {
                    isDaemon = true
                    start()
                }
                return NativeSpawnProcess(proc, 0, outputFile, proc.outputStream, pump)
            }
        }
    }
}

private object NativeSpawn {
    const val STILL_RUNNING = -2

    val isAvailable: Boolean = runCatching {
        System.loadLibrary("pocketspawn")
        true
    }.getOrDefault(false)

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
}
