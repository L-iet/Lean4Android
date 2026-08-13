package org.lean4android.toolchain

import org.lean4android.model.ToolchainLayout
import org.lean4android.process.ProcessCommand
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ToolchainCommandFactory(
    private val layout: ToolchainLayout,
    private val appHome: File,
    private val temporaryDirectory: File,
) {
    fun lean(
        arguments: List<String>,
        workingDirectory: File,
        timeout: Duration = 30.seconds,
    ) = ProcessCommand(
        executable = layout.leanExecutable,
        arguments = arguments,
        workingDirectory = workingDirectory,
        environment = commonEnvironment() + ("LEAN_PATH" to layout.leanLibraryDirectory.path),
        timeout = timeout,
    )

    fun lake(
        arguments: List<String>,
        workingDirectory: File,
        timeout: Duration = 30.seconds,
    ) = ProcessCommand(
        executable = layout.lakeExecutable,
        arguments = arguments,
        workingDirectory = workingDirectory,
        environment = commonEnvironment() + mapOf(
            "LAKE_HOME" to layout.sysroot.path,
            "LAKE_OVERRIDE_LEAN" to "true",
        ),
        timeout = timeout,
    )

    private fun commonEnvironment() = mapOf(
        "HOME" to appHome.path,
        "TMPDIR" to temporaryDirectory.path,
        "LEAN_SYSROOT" to layout.sysroot.path,
        "LD_LIBRARY_PATH" to layout.leanExecutable.parentFile!!.path,
        "PATH" to "/system/bin",
    )
}
