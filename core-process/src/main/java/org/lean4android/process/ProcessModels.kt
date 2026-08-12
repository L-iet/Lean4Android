package org.lean4android.process

import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class ProcessCommand(
    val executable: File,
    val arguments: List<String> = emptyList(),
    val workingDirectory: File,
    val environment: Map<String, String> = emptyMap(),
    val timeout: Duration = 30.seconds,
) {
    init {
        require(executable.isAbsolute) { "Executable path must be absolute" }
        require(workingDirectory.isAbsolute) { "Working directory must be absolute" }
        require(arguments.none { '\u0000' in it }) { "Arguments cannot contain NUL" }
    }
}

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

fun interface CommandRunner {
    suspend fun run(command: ProcessCommand): ProcessResult
}

