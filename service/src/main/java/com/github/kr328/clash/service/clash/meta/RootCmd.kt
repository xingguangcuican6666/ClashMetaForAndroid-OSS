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
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(false)
            .start()

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
