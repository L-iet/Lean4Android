package org.lean4android.toolchain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolchainInstallationStateTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `complete facets and matching schema marker are healthy`() {
        val root = completeRuntime()
        ToolchainInstallationState.writeMarker(root, "lean-test")

        assertTrue(ToolchainInstallationState.problems(root, "lean-test").isEmpty())
        assertTrue(root.resolve(".installed").readText().contains("schema=1"))
    }

    @Test
    fun `legacy marker can migrate only when required facets are complete`() {
        val complete = completeRuntime()
        complete.resolve(".installed").writeText("lean-test")
        assertTrue(ToolchainInstallationState.isLegacyMarker(complete, "lean-test"))

        complete.resolve("lib/lean/Init.olean.private").delete()
        assertFalse(ToolchainInstallationState.isLegacyMarker(complete, "lean-test"))
    }

    @Test
    fun `health reports corrupt marker wrong ID and every missing facet`() {
        val root = completeRuntime()
        root.resolve(".installed").writeText("schema=999\ntoolchain=other\n")
        root.resolve("lib/lean/Init.ir").writeBytes(byteArrayOf())

        val problems = ToolchainInstallationState.problems(root, "lean-test")

        assertTrue(problems.any { "Init.ir" in it })
        assertTrue(problems.any { "schema" in it })
        assertTrue(problems.any { "ID" in it })
    }

    private fun completeRuntime() = temporaryFolder.newFolder().apply {
        listOf("olean", "olean.private", "olean.server", "ilean", "ir").forEach { extension ->
            resolve("lib/lean/Init.$extension").apply {
                parentFile!!.mkdirs()
                writeText(extension)
            }
        }
    }
}
