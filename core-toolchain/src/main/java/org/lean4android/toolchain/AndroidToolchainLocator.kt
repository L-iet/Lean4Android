package org.lean4android.toolchain

import android.content.Context
import org.lean4android.model.ToolchainHealth
import org.lean4android.model.ToolchainId
import org.lean4android.model.ToolchainLayout
import java.io.File
import java.io.FileOutputStream

class AndroidToolchainLocator(private val context: Context) {
    fun installSysroot(): File {
        val destination = context.noBackupFilesDir.resolve("toolchains/${BuildConfig.TOOLCHAIN_ID}")
        val marker = destination.resolve(".installed")
        if (!marker.isFile) {
            val staging = destination.resolveSibling("${destination.name}.installing")
            staging.deleteRecursively()
            copyAssetDirectory("toolchain", staging)
            require(staging.resolve("lib/lean/Init.olean").isFile) {
                "Packaged Lean sysroot is missing Init.olean"
            }
            marker.parentFile?.mkdirs()
            if (destination.exists()) destination.deleteRecursively()
            check(staging.renameTo(destination)) { "Could not activate Lean sysroot" }
            marker.writeText(BuildConfig.TOOLCHAIN_ID)
        }
        ToolchainLayoutAdapter.refresh(layout(destination))
        return destination
    }

    fun locate(): ToolchainHealth {
        val sysroot = context.noBackupFilesDir.resolve("toolchains/${BuildConfig.TOOLCHAIN_ID}")
        val layout = runCatching { layout(sysroot) }.getOrElse {
            return ToolchainHealth.Missing(listOf(it.message ?: "Android did not provide nativeLibraryDir"))
        }
        val problems = buildList {
            if (!layout.leanExecutable.isFile) add("Packaged Lean executable is absent")
            if (!layout.lakeExecutable.isFile) add("Packaged Lake executable is absent")
            if (!layout.leanLibraryDirectory.isDirectory) add("Lean sysroot data is not installed")
            addAll(ToolchainLayoutAdapter.problems(layout))
        }
        return if (problems.isEmpty()) ToolchainHealth.Ready(layout) else ToolchainHealth.Missing(problems)
    }

    private fun layout(sysroot: File): ToolchainLayout {
        val nativeDirectory = context.applicationInfo.nativeLibraryDir
            ?.let(::File)
            ?: error("Android did not provide nativeLibraryDir")
        return ToolchainLayout(
            id = ToolchainId(BuildConfig.TOOLCHAIN_ID),
            leanExecutable = nativeDirectory.resolve("liblean_exe.so"),
            lakeExecutable = nativeDirectory.resolve("liblake_exe.so"),
            sysroot = sysroot,
        )
    }

    fun probe(): String = when (val health = locate()) {
        is ToolchainHealth.Ready -> "Ready: ${health.layout.id.value}"
        is ToolchainHealth.Missing -> health.problems.joinToString(separator = "\n", prefix = "Not ready:\n• ")
    }

    private fun copyAssetDirectory(assetPath: String, destination: File) {
        val children = context.assets.list(assetPath)
            ?: error("Could not list packaged asset $assetPath")
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destination).use(input::copyTo)
            }
            return
        }
        destination.mkdirs()
        for (child in children) {
            copyAssetDirectory("$assetPath/$child", destination.resolve(child))
        }
    }
}
