package org.lean4android.toolchain

import org.lean4android.model.ToolchainLayout
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Maintains the writable conventional layout expected by Lean and Lake. */
object ToolchainLayoutAdapter {
    fun refresh(layout: ToolchainLayout) {
        replaceLink(layout.sysroot.resolve("bin/lean"), layout.leanExecutable)
        replaceLink(layout.sysroot.resolve(".lake/build/bin/lake"), layout.lakeExecutable)
        replaceLink(layout.sysroot.resolve(".lake/build/lib/lean"), layout.leanLibraryDirectory)
    }

    fun problems(layout: ToolchainLayout): List<String> = buildList {
        expectedLinks(layout).forEach { (link, target) ->
            if (!Files.isSymbolicLink(link.toPath())) {
                add("Toolchain compatibility link is absent: ${relative(layout, link)}")
            } else if (runCatching { Files.readSymbolicLink(link.toPath()) }.getOrNull() != target.toPath()) {
                add("Toolchain compatibility link is stale: ${relative(layout, link)}")
            }
        }
    }

    private fun expectedLinks(layout: ToolchainLayout) = listOf(
        layout.sysroot.resolve("bin/lean") to layout.leanExecutable,
        layout.sysroot.resolve(".lake/build/bin/lake") to layout.lakeExecutable,
        layout.sysroot.resolve(".lake/build/lib/lean") to layout.leanLibraryDirectory,
    )

    private fun replaceLink(link: File, target: File) {
        link.parentFile?.mkdirs()
        val temporary = link.resolveSibling("${link.name}.linking")
        Files.deleteIfExists(temporary.toPath())
        Files.createSymbolicLink(temporary.toPath(), target.toPath())
        try {
            Files.move(
                temporary.toPath(),
                link.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun relative(layout: ToolchainLayout, file: File): String =
        layout.sysroot.toPath().relativize(file.toPath()).toString()
}
