package org.lean4android.toolchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ToolchainActivationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `successful activation replaces destination and removes rollback tree`() {
        val parent = temporaryFolder.newFolder()
        val destination = parent.resolve("runtime").withPayload("old")
        val staging = parent.resolve("runtime.installing").withPayload("new")
        val previous = parent.resolve("runtime.previous")

        ToolchainActivation().activate(staging, destination, previous)

        assertEquals("new", destination.resolve("payload").readText())
        assertFalse(staging.exists())
        assertFalse(previous.exists())
    }

    @Test
    fun `rollback cleanup never follows old absolute compatibility links into new tree`() {
        val parent = temporaryFolder.newFolder()
        val destination = parent.resolve("runtime").apply {
            resolve("lib/lean").mkdirs()
            resolve("lib/lean/old").writeText("old")
            resolve(".lake/build/lib").mkdirs()
            Files.createSymbolicLink(resolve(".lake/build/lib/lean").toPath(), resolve("lib/lean").toPath())
        }
        val staging = parent.resolve("runtime.installing").apply {
            resolve("lib/lean").mkdirs()
            resolve("lib/lean/new").writeText("new")
        }
        val previous = parent.resolve("runtime.previous")

        ToolchainActivation().activate(staging, destination, previous)

        assertEquals("new", destination.resolve("lib/lean/new").readText())
        assertFalse(previous.exists())
    }

    @Test
    fun `failed staging rename restores preserved destination`() {
        val parent = temporaryFolder.newFolder()
        val destination = parent.resolve("runtime").withPayload("old")
        val staging = parent.resolve("runtime.installing").withPayload("new")
        val previous = parent.resolve("runtime.previous")
        val activation = ToolchainActivation { source, target ->
            if (source == staging) false else source.renameTo(target)
        }

        val failure = runCatching { activation.activate(staging, destination, previous) }.exceptionOrNull()

        assertTrue(failure?.message?.contains("activate") == true)
        assertEquals("old", destination.resolve("payload").readText())
        assertTrue(staging.exists())
        assertFalse(previous.exists())
    }

    @Test
    fun `failed rollback rename is reported explicitly`() {
        val parent = temporaryFolder.newFolder()
        val destination = parent.resolve("runtime").withPayload("old")
        val staging = parent.resolve("runtime.installing").withPayload("new")
        val previous = parent.resolve("runtime.previous")
        val activation = ToolchainActivation { source, target ->
            when (source) {
                destination -> source.renameTo(target)
                else -> false
            }
        }

        val failure = runCatching { activation.activate(staging, destination, previous) }.exceptionOrNull()

        assertTrue(failure?.message?.contains("restore previous") == true)
        assertFalse(destination.exists())
        assertEquals("old", previous.resolve("payload").readText())
        assertTrue(staging.exists())
    }

    @Test
    fun `startup recovery restores only a healthy rollback tree`() {
        val parent = temporaryFolder.newFolder()
        val destination = parent.resolve("runtime").withPayload("partial")
        val previous = parent.resolve("runtime.previous").withPayload("verified")
        val activation = ToolchainActivation()

        assertFalse(activation.restorePreviousIfHealthy(destination, previous) { listOf("corrupt") })
        assertEquals("partial", destination.resolve("payload").readText())

        assertTrue(activation.restorePreviousIfHealthy(destination, previous) { emptyList() })
        assertEquals("verified", destination.resolve("payload").readText())
        assertFalse(previous.exists())
    }

    private fun File.withPayload(value: String) = apply {
        mkdirs()
        resolve("payload").writeText(value)
    }
}
