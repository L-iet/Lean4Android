package org.lean4android.model

import java.io.File

@JvmInline
value class ToolchainId(val value: String) {
    init {
        require(value.matches(Regex("[a-zA-Z0-9._-]+"))) { "Invalid toolchain ID" }
    }
}

data class ToolchainLayout(
    val id: ToolchainId,
    val leanExecutable: File,
    val lakeExecutable: File,
    val sysroot: File,
) {
    val leanLibraryDirectory: File get() = sysroot.resolve("lib/lean")
    val timezoneFile: File get() = sysroot.resolve("share/lean4android/UTC")
}

sealed interface ToolchainHealth {
    data class Ready(val layout: ToolchainLayout) : ToolchainHealth
    data class Missing(val problems: List<String>) : ToolchainHealth
}
