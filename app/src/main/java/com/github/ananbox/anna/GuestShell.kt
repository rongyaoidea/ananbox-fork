package com.github.ananbox.anna

import android.os.Build
import java.io.File
import java.io.InputStream

/**
 * Runs one-shot commands inside the guest by starting a second proot instance
 * with the same binds as the booted container, so the guest's own `sh`, `am`,
 * `pm`, `cmd` and `dumpsys` are available to the agent.
 *
 * The command runs as the app's uid with proot's fake-root view of the guest.
 */
object GuestShell {

    private const val MAX_OUTPUT = 1 shl 20
    private const val POLL_MS = 50L

    const val DEFAULT_TIMEOUT_MS = 30_000
    const val MAX_TIMEOUT_MS = 120_000

    data class Result(
        val exitCode: Int,
        val output: String,
        val truncated: Boolean,
        val timedOut: Boolean,
    )

    fun exec(command: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Result {
        val rootfs = AnnaCore.rootfs
        val filesDir = AnnaCore.filesDir
        val proot = File(AnnaCore.appContext.applicationInfo.nativeLibraryDir, "libproot.so")
        val tmpDir = File(filesDir, "tmp")
        prepare(rootfs, tmpDir)

        val spec = GuestCommand.build(
            rootfs = rootfs,
            prootBinary = proot,
            tmpDir = tmpDir,
            command = command,
            kernelRelease = System.getProperty("os.version") ?: "",
        )

        val process = ProcessBuilder(spec.args).apply {
            directory(filesDir)
            environment().putAll(spec.env)
            redirectErrorStream(true)
        }.start()

        val collector = Collector()
        val reader = Thread({ collector.drain(process.inputStream) }, "anna-exec-reader").apply {
            isDaemon = true
            start()
        }

        val timeout = timeoutMs.coerceIn(1_000, MAX_TIMEOUT_MS)
        val finished = waitFor(process, timeout.toLong())
        if (!finished) kill(process)
        reader.join(2_000)

        return Result(
            exitCode = if (finished) process.exitValue() else -1,
            output = collector.text(),
            truncated = collector.truncated,
            timedOut = !finished,
        )
    }

    private fun waitFor(process: Process, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (hasExited(process)) return true
            Thread.sleep(POLL_MS)
        }
        return hasExited(process)
    }

    private fun hasExited(process: Process): Boolean = try {
        process.exitValue()
        true
    } catch (_: IllegalThreadStateException) {
        false
    }

    private fun kill(process: Process) {
        try {
            process.destroy()
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                process.destroyForcibly()
            } catch (_: Exception) {
            }
        }
    }

    private fun prepare(rootfs: File, tmpDir: File) {
        tmpDir.mkdirs()
        for (dir in listOf("dev/socket", "dev/__properties__", "dev/input", "system/vendor", "mnt/user/0")) {
            File(rootfs, dir).mkdirs()
        }
        for (file in listOf("dev/kmsg", "dev/pmsg0")) {
            val target = File(rootfs, file)
            if (!target.exists()) {
                target.parentFile?.mkdirs()
                runCatching { target.createNewFile() }
            }
        }
    }

    private class Collector {
        private val lock = Any()
        private val builder = StringBuilder()
        private var overflowed = false

        val truncated: Boolean
            get() = synchronized(lock) { overflowed }

        fun drain(input: InputStream) {
            try {
                input.bufferedReader().use { reader ->
                    val buffer = CharArray(8192)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        synchronized(lock) {
                            val room = (MAX_OUTPUT - builder.length).coerceAtLeast(0)
                            if (room == 0) {
                                overflowed = true
                            } else {
                                builder.append(buffer, 0, minOf(read, room))
                                if (read > room) overflowed = true
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // stream closed by process teardown
            }
        }

        fun text(): String = synchronized(lock) { builder.toString() }
    }
}
