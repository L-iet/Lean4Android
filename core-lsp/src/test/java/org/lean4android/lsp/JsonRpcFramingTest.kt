package org.lean4android.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.StandardCharsets

class JsonRpcFramingTest {
    @Test
    fun `write uses UTF-8 byte length rather than character count`() {
        val output = ByteArrayOutputStream()
        JsonRpcMessageIO(ByteArrayInputStream(byteArrayOf()), output).write("{\"text\":\"λ🙂\"}")

        val wire = output.toByteArray()
        val separator = wire.indexOfSequence("\r\n\r\n".toByteArray())
        val header = String(wire, 0, separator, StandardCharsets.US_ASCII)
        val payloadSize = wire.size - separator - 4

        assertEquals("Content-Length: $payloadSize", header)
    }

    @Test
    fun `read handles fragmented input and coalesced messages`() {
        val first = frame("{\"value\":\"héllo λ\"}")
        val second = frame("{\"value\":2}")
        val input = ChunkedInputStream(first + second, chunkSize = 2)
        val io = JsonRpcMessageIO(input, ByteArrayOutputStream())

        assertEquals("{\"value\":\"héllo λ\"}", io.read())
        assertEquals("{\"value\":2}", io.read())
        assertNull(io.read())
    }

    @Test
    fun `read rejects missing duplicate invalid and truncated lengths`() {
        assertThrows(JsonRpcFramingException::class.java) { io("X-Test: 1\r\n\r\n").read() }
        assertThrows(JsonRpcFramingException::class.java) {
            io("Content-Length: 1\r\ncontent-length: 1\r\n\r\nxx").read()
        }
        assertThrows(JsonRpcFramingException::class.java) { io("Content-Length: nope\r\n\r\n").read() }
        assertThrows(EOFException::class.java) { io("Content-Length: 5\r\n\r\nabc").read() }
    }

    private fun io(wire: String) = JsonRpcMessageIO(
        ByteArrayInputStream(wire.toByteArray(StandardCharsets.UTF_8)),
        ByteArrayOutputStream(),
    )

    private fun frame(payload: String): ByteArray {
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        return "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII) + bytes
    }

    private fun ByteArray.indexOfSequence(sequence: ByteArray): Int = indices.first { start ->
        start + sequence.size <= size && sequence.indices.all { this[start + it] == sequence[it] }
    }

    private class ChunkedInputStream(
        private val bytes: ByteArray,
        private val chunkSize: Int,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int = if (position < bytes.size) bytes[position++].toInt() and 0xff else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, chunkSize, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }
}
