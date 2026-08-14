package org.lean4android.toolchain

import java.io.File

/** Atomic-directory activation and recovery, isolated so interruption paths are host-testable. */
internal class ToolchainActivation(
    private val rename: (File, File) -> Boolean = { source, target -> source.renameTo(target) },
) {
    fun restorePreviousIfHealthy(
        destination: File,
        previous: File,
        problems: (File) -> List<String>,
    ): Boolean {
        if (problems(previous).isNotEmpty()) return false
        destination.deleteTreeWithoutFollowingLinks()
        check(rename(previous, destination)) { "Could not restore previous Lean sysroot" }
        return true
    }

    fun activate(staging: File, destination: File, previous: File) {
        previous.deleteTreeWithoutFollowingLinks()
        if (destination.exists()) {
            check(rename(destination, previous)) { "Could not preserve previous Lean sysroot" }
        }
        if (rename(staging, destination)) {
            previous.deleteTreeWithoutFollowingLinks()
            return
        }

        val restored = !previous.exists() || rename(previous, destination)
        check(restored) { "Could not restore previous Lean sysroot after activation failure" }
        error("Could not activate Lean sysroot")
    }
}
