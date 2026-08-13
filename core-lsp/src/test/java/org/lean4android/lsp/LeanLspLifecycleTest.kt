package org.lean4android.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LeanLspLifecycleTest {
    @Test
    fun `lifecycle emits ordered JSON-RPC messages through framing boundary`() {
        val output = ByteArrayOutputStream()
        val writer = JsonRpcMessageIO(ByteArrayInputStream(byteArrayOf()), output)
        val messages = listOf(
            LeanLspLifecycle.initialize(1, "file:///data/user/0/app/projects/space and λ"),
            LeanLspLifecycle.initialized(),
            LeanLspLifecycle.shutdown(2),
            LeanLspLifecycle.exit(),
        )
        messages.forEach(writer::write)

        val reader = JsonRpcMessageIO(ByteArrayInputStream(output.toByteArray()), ByteArrayOutputStream())
        val decoded = generateSequence(reader::read).toList()

        assertEquals(messages, decoded)
        assertTrue(decoded[0].contains("\"method\":\"initialize\""))
        assertTrue(decoded[0].contains("space and λ"))
        assertTrue(decoded[2].contains("\"method\":\"shutdown\""))
        assertTrue(decoded[3].contains("\"method\":\"exit\""))
    }

    @Test
    fun `initialize safely escapes URI control characters`() {
        val message = LeanLspLifecycle.initialize(7, "file:///project/\"quoted\"\nline")

        assertTrue(message.contains("\\\"quoted\\\""))
        assertTrue(message.contains("\\nline"))
        assertTrue(message.none { it == '\n' })
    }
}
