package org.lean4android.toolchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.lean4android.model.ToolchainId
import org.lean4android.model.ToolchainLayout
import java.nio.file.Files

class ToolchainLayoutAdapterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `refresh creates and repairs all compatibility links`() {
        val root = temporaryFolder.root
        val native = root.resolve("installed/native-v1").apply { mkdirs() }
        val sysroot = root.resolve("toolchain").apply { mkdirs() }
        val lean = native.resolve("liblean_exe.so").apply { writeText("lean") }
        val lake = native.resolve("liblake_exe.so").apply { writeText("lake") }
        sysroot.resolve("lib/lean").mkdirs()
        val layout = ToolchainLayout(ToolchainId("test"), lean, lake, sysroot)

        ToolchainLayoutAdapter.refresh(layout)

        assertTrue(ToolchainLayoutAdapter.problems(layout).isEmpty())
        assertEquals(lean.toPath(), Files.readSymbolicLink(sysroot.resolve("bin/lean").toPath()))

        val replacement = root.resolve("installed/native-v2/liblean_exe.so")
        replacement.parentFile!!.mkdirs()
        replacement.writeText("lean v2")
        val updated = layout.copy(leanExecutable = replacement)
        ToolchainLayoutAdapter.refresh(updated)

        assertTrue(ToolchainLayoutAdapter.problems(updated).isEmpty())
        assertEquals(replacement.toPath(), Files.readSymbolicLink(sysroot.resolve("bin/lean").toPath()))
    }
}
