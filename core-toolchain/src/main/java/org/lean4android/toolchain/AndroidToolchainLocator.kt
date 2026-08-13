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
        val staging = destination.resolveSibling("${destination.name}.installing")
        val previous = destination.resolveSibling("${destination.name}.previous")
        staging.deleteRecursively()

        if (ToolchainInstallationState.isLegacyMarker(destination, BuildConfig.TOOLCHAIN_ID)) {
            ToolchainInstallationState.writeMarker(destination, BuildConfig.TOOLCHAIN_ID)
        }
        if (ToolchainInstallationState.problems(destination, BuildConfig.TOOLCHAIN_ID).isEmpty()) {
            previous.deleteRecursively()
            ToolchainLayoutAdapter.refresh(layout(destination))
            return destination
        }

        restorePreviousIfHealthy(destination, previous)
        if (ToolchainInstallationState.problems(destination, BuildConfig.TOOLCHAIN_ID).isNotEmpty()) {
            ToolchainStoragePreflight.problem(
                availableBytes = context.noBackupFilesDir.usableSpace,
                payloadBytes = BuildConfig.PACKAGED_SYSROOT_BYTES,
            )?.let(::error)
            copyAssetDirectory("toolchain", staging)
            val stagingProblems = ToolchainInstallationState.problems(
                staging,
                BuildConfig.TOOLCHAIN_ID,
                requireMarker = false,
            )
            require(stagingProblems.isEmpty()) { stagingProblems.joinToString("\n") }
            ToolchainInstallationState.writeMarker(staging, BuildConfig.TOOLCHAIN_ID)
            activate(staging, destination, previous)
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
            addAll(ToolchainInstallationState.problems(layout.sysroot, BuildConfig.TOOLCHAIN_ID))
            addAll(ToolchainLayoutAdapter.problems(layout))
        }
        return if (problems.isEmpty()) ToolchainHealth.Ready(layout) else ToolchainHealth.Missing(problems)
    }

    private fun restorePreviousIfHealthy(destination: File, previous: File) {
        if (ToolchainInstallationState.problems(previous, BuildConfig.TOOLCHAIN_ID).isNotEmpty()) return
        destination.deleteRecursively()
        check(previous.renameTo(destination)) { "Could not restore previous Lean sysroot" }
    }

    private fun activate(staging: File, destination: File, previous: File) {
        previous.deleteRecursively()
        if (destination.exists()) {
            check(destination.renameTo(previous)) { "Could not preserve previous Lean sysroot" }
        }
        if (!staging.renameTo(destination)) {
            if (previous.exists()) previous.renameTo(destination)
            error("Could not activate Lean sysroot")
        }
        previous.deleteRecursively()
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
