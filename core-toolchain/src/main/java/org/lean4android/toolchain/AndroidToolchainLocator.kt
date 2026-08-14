package org.lean4android.toolchain

import android.content.Context
import org.lean4android.model.ToolchainHealth
import org.lean4android.model.ToolchainId
import org.lean4android.model.ToolchainLayout
import java.io.File

class AndroidToolchainLocator(private val context: Context) {
    private val activation = ToolchainActivation()

    fun installSysroot(): File {
        val destination = context.noBackupFilesDir.resolve("toolchains/${BuildConfig.TOOLCHAIN_ID}")
        val staging = destination.resolveSibling("${destination.name}.installing")
        val previous = destination.resolveSibling("${destination.name}.previous")
        staging.deleteTreeWithoutFollowingLinks()
        val manifest = readRuntimeManifest()

        if (ToolchainInstallationState.isLegacyMarker(destination, BuildConfig.TOOLCHAIN_ID)) {
            verifyAndMarkIfHealthy(destination, manifest)
        }
        if (ToolchainInstallationState.hasSchemaOneMarker(destination, BuildConfig.TOOLCHAIN_ID)) {
            verifyAndMarkIfHealthy(destination, manifest)
        }
        if (installationProblems(destination).isEmpty()) {
            previous.deleteTreeWithoutFollowingLinks()
            ToolchainLayoutAdapter.refresh(layout(destination))
            return destination
        }

        activation.restorePreviousIfHealthy(destination, previous, ::installationProblems)
        if (installationProblems(destination).isNotEmpty()) {
            ToolchainStoragePreflight.problem(
                availableBytes = context.noBackupFilesDir.usableSpace,
                payloadBytes = BuildConfig.PACKAGED_SYSROOT_BYTES,
            )?.let(::error)
            ApkAssetRuntimePayloadSource(context.assets).use { source ->
                RuntimePayloadInstaller().install(source, staging, manifest)
            }
            val stagingProblems = ToolchainInstallationState.problems(
                staging,
                BuildConfig.TOOLCHAIN_ID,
                requireMarker = false,
            )
            require(stagingProblems.isEmpty()) { stagingProblems.joinToString("\n") }
            val runtimeProblems = ToolchainRuntimeVerifier().problems(staging, manifest)
            require(runtimeProblems.isEmpty()) { runtimeProblems.joinToString("\n") }
            ToolchainInstallationState.writeMarker(
                staging,
                BuildConfig.TOOLCHAIN_ID,
                BuildConfig.RUNTIME_MANIFEST_SHA256,
            )
            activation.activate(staging, destination, previous)
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
            addAll(installationProblems(layout.sysroot))
            addAll(ToolchainLayoutAdapter.problems(layout))
        }
        return if (problems.isEmpty()) ToolchainHealth.Ready(layout) else ToolchainHealth.Missing(problems)
    }

    /** Explicit slow-path audit. Normal startup and editor checks intentionally use fast health checks. */
    fun verifyInstalledRuntime(): List<String> {
        val sysroot = context.noBackupFilesDir.resolve("toolchains/${BuildConfig.TOOLCHAIN_ID}")
        val fastProblems = installationProblems(sysroot)
        if (fastProblems.isNotEmpty()) return fastProblems
        return ToolchainRuntimeVerifier().problems(sysroot, readRuntimeManifest())
    }

    private fun installationProblems(root: File) = ToolchainInstallationState.problems(
        root,
        BuildConfig.TOOLCHAIN_ID,
        BuildConfig.RUNTIME_MANIFEST_SHA256,
    )

    private fun readRuntimeManifest(): ToolchainRuntimeManifest {
        val contents = context.assets.open("toolchain-manifest.tsv").bufferedReader().use { it.readText() }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(contents.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        require(digest == BuildConfig.RUNTIME_MANIFEST_SHA256) { "Packaged runtime manifest digest is invalid" }
        return ToolchainRuntimeManifest.parse(contents).also {
            require(it.schema == 1) { "Unsupported packaged runtime manifest schema" }
            require(it.toolchainId == BuildConfig.TOOLCHAIN_ID) { "Packaged runtime manifest toolchain mismatch" }
        }
    }

    private fun verifyAndMarkIfHealthy(root: File, manifest: ToolchainRuntimeManifest): Boolean {
        val problems = ToolchainRuntimeVerifier().problems(root, manifest)
        if (problems.isNotEmpty()) return false
        ToolchainInstallationState.writeMarker(root, BuildConfig.TOOLCHAIN_ID, BuildConfig.RUNTIME_MANIFEST_SHA256)
        return true
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

}
