package org.lean4android.toolchain

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object ToolchainInstallationState {
    const val SCHEMA_VERSION = 1
    private val requiredRuntimeFiles = listOf(
        "lib/lean/Init.olean",
        "lib/lean/Init.olean.private",
        "lib/lean/Init.olean.server",
        "lib/lean/Init.ilean",
        "lib/lean/Init.ir",
    )

    fun problems(root: File, toolchainId: String, requireMarker: Boolean = true): List<String> = buildList {
        requiredRuntimeFiles.forEach { relative ->
            val file = root.resolve(relative)
            if (!file.isFile || file.length() == 0L) add("Required toolchain facet is missing or empty: $relative")
        }
        if (requireMarker) {
            val marker = root.resolve(".installed")
            val fields = marker.takeIf(File::isFile)?.readLines()
                ?.mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
                }
                ?.toMap()
                .orEmpty()
            if (fields["schema"] != SCHEMA_VERSION.toString()) add("Toolchain installation schema is missing or unsupported")
            if (fields["toolchain"] != toolchainId) add("Toolchain installation ID does not match $toolchainId")
        }
    }

    fun isLegacyMarker(root: File, toolchainId: String): Boolean =
        root.resolve(".installed").takeIf(File::isFile)?.readText()?.trim() == toolchainId &&
            problems(root, toolchainId, requireMarker = false).isEmpty()

    fun writeMarker(root: File, toolchainId: String) {
        val marker = root.resolve(".installed")
        marker.parentFile?.mkdirs()
        val temporary = marker.resolveSibling("${marker.name}.writing")
        temporary.writeText("schema=$SCHEMA_VERSION\ntoolchain=$toolchainId\n")
        Files.move(
            temporary.toPath(),
            marker.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
