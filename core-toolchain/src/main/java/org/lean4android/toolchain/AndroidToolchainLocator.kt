package org.lean4android.toolchain

import android.content.Context
import org.lean4android.model.ToolchainHealth
import org.lean4android.model.ToolchainId
import org.lean4android.model.ToolchainLayout

class AndroidToolchainLocator(private val context: Context) {
    fun locate(): ToolchainHealth {
        val nativeDirectory = context.applicationInfo.nativeLibraryDir
            ?.let(::java.io.File)
            ?: return ToolchainHealth.Missing(listOf("Android did not provide nativeLibraryDir"))
        val sysroot = context.noBackupFilesDir.resolve("toolchains/${BuildConfig.TOOLCHAIN_ID}")
        val layout = ToolchainLayout(
            id = ToolchainId(BuildConfig.TOOLCHAIN_ID),
            leanExecutable = nativeDirectory.resolve("liblean_exe.so"),
            lakeExecutable = nativeDirectory.resolve("liblake_exe.so"),
            sysroot = sysroot,
        )
        val problems = buildList {
            if (!layout.leanExecutable.isFile) add("Packaged Lean executable is absent")
            if (!layout.lakeExecutable.isFile) add("Packaged Lake executable is absent")
            if (!layout.leanLibraryDirectory.isDirectory) add("Lean sysroot data is not installed")
        }
        return if (problems.isEmpty()) ToolchainHealth.Ready(layout) else ToolchainHealth.Missing(problems)
    }

    fun probe(): String = when (val health = locate()) {
        is ToolchainHealth.Ready -> "Ready: ${health.layout.id.value}"
        is ToolchainHealth.Missing -> health.problems.joinToString(separator = "\n", prefix = "Not ready:\n• ")
    }
}

