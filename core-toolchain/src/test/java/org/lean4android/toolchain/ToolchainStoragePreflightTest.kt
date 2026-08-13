package org.lean4android.toolchain

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolchainStoragePreflightTest {
    @Test
    fun `accepts payload plus reserve and rejects one byte less`() {
        val payload = 2_000L
        val required = payload + ToolchainStoragePreflight.RESERVE_BYTES

        assertNull(ToolchainStoragePreflight.problem(required, payload))
        assertTrue(ToolchainStoragePreflight.problem(required - 1, payload)!!.contains("Not enough storage"))
    }
}
