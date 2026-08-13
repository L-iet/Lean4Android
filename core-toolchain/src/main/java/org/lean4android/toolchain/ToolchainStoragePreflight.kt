package org.lean4android.toolchain

object ToolchainStoragePreflight {
    const val RESERVE_BYTES = 64L * 1024L * 1024L

    fun problem(availableBytes: Long, payloadBytes: Long): String? {
        require(availableBytes >= 0) { "Available bytes cannot be negative" }
        require(payloadBytes >= 0) { "Payload bytes cannot be negative" }
        val required = payloadBytes + RESERVE_BYTES
        return if (availableBytes >= required) null else
            "Not enough storage for Lean toolchain: need ${format(required)}, available ${format(availableBytes)}"
    }

    private fun format(bytes: Long): String = "%.2f GiB".format(bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
}
