package org.lean4android.process

import java.io.File
import java.io.InputStream
import java.io.OutputStream
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
    val stdoutCapture: CapturedOutput = CapturedOutput.fromUtf8(stdout),
    val stderrCapture: CapturedOutput = CapturedOutput.fromUtf8(stderr),
)

/** Immutable, byte-faithful retained output suitable for a stable export revision. */
class CapturedOutput private constructor(bytes: ByteArray, val omittedBytes: Long) {
    private val retained = bytes.copyOf()
    val retainedBytes: Int get() = retained.size
    fun bytes(): ByteArray = retained.copyOf()
    fun text(): String = retained.toString(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean = other is CapturedOutput &&
        omittedBytes == other.omittedBytes && retained.contentEquals(other.retained)
    override fun hashCode(): Int = 31 * retained.contentHashCode() + omittedBytes.hashCode()

    companion object {
        fun fromBytes(bytes: ByteArray, omittedBytes: Long = 0): CapturedOutput {
            require(omittedBytes >= 0) { "Omitted byte count must be non-negative" }
            return CapturedOutput(bytes, omittedBytes)
        }
        fun fromUtf8(text: String): CapturedOutput = fromBytes(text.toByteArray(Charsets.UTF_8))
        fun concatenate(vararg outputs: CapturedOutput): CapturedOutput {
            val total = outputs.sumOf { it.retainedBytes }
            val bytes = ByteArray(total)
            var offset = 0
            outputs.forEach { output ->
                val next = output.retained
                next.copyInto(bytes, offset)
                offset += next.size
            }
            return CapturedOutput(bytes, outputs.sumOf(CapturedOutput::omittedBytes))
        }
    }
}

fun interface CommandRunner {
    suspend fun run(command: ProcessCommand): ProcessResult
}

interface RunningProcess : AutoCloseable {
    val standardInput: OutputStream
    val standardOutput: InputStream
    val standardError: InputStream
    val isAlive: Boolean
    fun awaitExit(timeout: Duration): Int?
    fun terminate(gracePeriod: Duration = 2.seconds): Int
}

fun interface ProcessLauncher {
    fun start(command: ProcessCommand): RunningProcess
}
