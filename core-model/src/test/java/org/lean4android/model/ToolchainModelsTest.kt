package org.lean4android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolchainModelsTest {
    @Test
    fun `toolchain id accepts manifest-safe characters`() {
        assertEquals("lean-4.32.1-android1", ToolchainId("lean-4.32.1-android1").value)
    }

    @Test
    fun `toolchain id rejects paths`() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolchainId("../untrusted")
        }
    }
}

