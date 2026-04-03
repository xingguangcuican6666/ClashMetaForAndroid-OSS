package com.github.kr328.clash.service.clash.meta

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

internal data class CmdResult(
    val code: Int,
    val stdout: String,
    val stderr: String,
)

internal object RootCmd {
    fun run(command: String, timeoutSeconds: Long = 10): CmdResult {
        require(!command.contains('\u0000')) { "Invalid command" }
        require(!command.contains("${'$'}{var@P}")) { "Unsafe shell expansion is not allowed" }

        // Use `su` without `-c` and pipe the command via stdin.
        // Passing large scripts (e.g. heredoc with config YAML content) as a -c argument
        // hits the kernel ARG_MAX limit and causes E2BIG / "Argument list too long".
        // Writing via stdin has no such size restriction.
        val process = ProcessBuilder("su")
            .redirectErrorStream(false)
            .start()

        // Write in a daemon thread so the main thread can concurrently drain stdout/stderr,
        // avoiding a pipe-buffer deadlock when the command produces output.
        Thread {
            runCatching {
                process.outputStream.bufferedWriter().use { it.write(command) }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val outReader = BufferedReader(InputStreamReader(process.inputStream))
        val errReader = BufferedReader(InputStreamReader(process.errorStream))

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return CmdResult(
                code = -1,
                stdout = outReader.readTextSafe(),
                stderr = "timeout",
            )
        }

        return CmdResult(
            code = process.exitValue(),
            stdout = outReader.readTextSafe(),
            stderr = errReader.readTextSafe(),
        )
    }

    private fun BufferedReader.readTextSafe(): String {
        return runCatching { readText().trim() }.getOrDefault("")
    }
}
