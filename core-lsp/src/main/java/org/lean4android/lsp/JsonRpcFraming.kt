package org.lean4android.lsp

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

private const val HEADER_TERMINATOR = "\r\n\r\n"
private const val MAX_HEADER_BYTES = 16 * 1024

class JsonRpcFramingException(message: String) : Exception(message)

/** Byte-accurate LSP stdio framing. Each instance must have a single reader and writer. */
class JsonRpcMessageIO(
    private val input: InputStream,
    private val output: OutputStream,
) {
    fun read(): String? {
        val header = readHeader() ?: return null
        val contentLength = parseContentLength(header)
        val payload = ByteArray(contentLength)
        var offset = 0
        while (offset < payload.size) {
            val count = input.read(payload, offset, payload.size - offset)
            if (count < 0) throw EOFException("JSON-RPC payload ended after $offset of $contentLength bytes")
            if (count == 0) continue
            offset += count
        }
        return String(payload, StandardCharsets.UTF_8)
    }

    @Synchronized
    fun write(payload: String) {
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        output.write("Content-Length: ${bytes.size}$HEADER_TERMINATOR".toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun readHeader(): String? {
        val bytes = ArrayList<Byte>()
        var matched = 0
        while (bytes.size < MAX_HEADER_BYTES) {
            val next = input.read()
            if (next < 0) {
                if (bytes.isEmpty()) return null
                throw EOFException("JSON-RPC header ended before CRLF terminator")
            }
            bytes.add(next.toByte())
            matched = if (next.toChar() == HEADER_TERMINATOR[matched]) matched + 1 else if (next == '\r'.code) 1 else 0
            if (matched == HEADER_TERMINATOR.length) {
                return String(bytes.toByteArray(), StandardCharsets.US_ASCII)
            }
        }
        throw JsonRpcFramingException("JSON-RPC header exceeds $MAX_HEADER_BYTES bytes")
    }

    private fun parseContentLength(header: String): Int {
        val values = header.removeSuffix(HEADER_TERMINATOR)
            .split("\r\n")
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) throw JsonRpcFramingException("Malformed JSON-RPC header: $line")
                val name = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                if (name.equals("Content-Length", ignoreCase = true)) value else null
            }
        if (values.size != 1) throw JsonRpcFramingException("JSON-RPC message requires exactly one Content-Length header")
        return values.single().toIntOrNull()
            ?.takeIf { it >= 0 }
            ?: throw JsonRpcFramingException("Invalid Content-Length: ${values.single()}")
    }
}
