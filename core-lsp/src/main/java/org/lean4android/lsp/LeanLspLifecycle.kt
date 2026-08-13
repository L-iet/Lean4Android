package org.lean4android.lsp

/** Minimal lifecycle messages for the pinned Lean language server. */
object LeanLspLifecycle {
    fun initialize(requestId: Long, rootUri: String): String =
        """{"jsonrpc":"2.0","id":$requestId,"method":"initialize","params":{"processId":null,"rootUri":${rootUri.jsonString()},"capabilities":{}}}"""

    fun initialized(): String =
        """{"jsonrpc":"2.0","method":"initialized","params":{}}"""

    fun shutdown(requestId: Long): String =
        """{"jsonrpc":"2.0","id":$requestId,"method":"shutdown","params":null}"""

    fun exit(): String =
        """{"jsonrpc":"2.0","method":"exit","params":null}"""

    private fun String.jsonString(): String = buildString {
        append('"')
        for (character in this@jsonString) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}
