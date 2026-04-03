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

        // Write command to stdin in a separate thread so that stdout/stderr can be
        // drained concurrently and we never deadlock on the pipe buffer.
        Thread {
            runCatching {
                process.outputStream.bufferedWriter().use { it.write(command) }
            }.onFailure {
                // Log but do not crash: the process may have already exited, which is
                // a normal race on short commands that produce no stdin.
                android.util.Log.w("RootCmd", "stdin write failed: ${it.message}")
            }
        }.apply { isDaemon = true; start() }

        // Drain stdout and stderr concurrently. Without concurrent draining, a command
        // that produces large output (e.g. `cat config.base.yaml`) will fill the pipe
        // buffer and block, causing waitFor() to hang until the timeout fires.
        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()

        val stdoutThread = Thread {
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream)).forEachLine {
                    stdoutBuf.append(it).append('\n')
                }
            }
        }.apply { isDaemon = true; start() }

        val stderrThread = Thread {
            runCatching {
                BufferedReader(InputStreamReader(process.errorStream)).forEachLine {
                    stderrBuf.append(it).append('\n')
                }
            }
        }.apply { isDaemon = true; start() }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

        if (!finished) {
            process.destroyForcibly()
        }

        // Give the reader threads a moment to flush any remaining bytes.
        stdoutThread.join(2000)
        stderrThread.join(2000)

        val stdout = stdoutBuf.toString().trim()
        val stderr = stderrBuf.toString().trim()

        return if (finished) {
            CmdResult(code = process.exitValue(), stdout = stdout, stderr = stderr)
        } else {
            CmdResult(code = -1, stdout = stdout, stderr = "timeout")
        }
    }
}
