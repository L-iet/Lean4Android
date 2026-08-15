package org.lean4android.lsp

object LeanLspDocuments {
    fun didOpen(uri: String, version: Int, text: String): String {
        require(version >= 0) { "Document version cannot be negative" }
        return """{"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":${uri.jsonString()},"languageId":"lean","version":$version,"text":${text.jsonString()}}}}"""
    }

    fun didChange(uri: String, version: Int, text: String): String {
        require(version >= 0) { "Document version cannot be negative" }
        return """{"jsonrpc":"2.0","method":"textDocument/didChange","params":{"textDocument":{"uri":${uri.jsonString()},"version":$version},"contentChanges":[{"text":${text.jsonString()}}]}}"""
    }

    fun didClose(uri: String): String =
        """{"jsonrpc":"2.0","method":"textDocument/didClose","params":{"textDocument":{"uri":${uri.jsonString()}}}}"""

    fun didSave(uri: String, text: String? = null): String {
        val includedText = text?.let { ",\"text\":${it.jsonString()}" }.orEmpty()
        return """{"jsonrpc":"2.0","method":"textDocument/didSave","params":{"textDocument":{"uri":${uri.jsonString()}}$includedText}}"""
    }
}

data class DiagnosticBatch<T>(
    val uri: String,
    val version: Int?,
    val diagnostics: List<T>,
)

/** Tracks open document versions and rejects diagnostics for older editor contents. */
class DocumentVersionGate {
    private val versions = mutableMapOf<String, Int>()

    @Synchronized
    fun opened(uri: String, version: Int) = update(uri, version)

    @Synchronized
    fun changed(uri: String, version: Int) = update(uri, version)

    @Synchronized
    fun closed(uri: String) {
        versions.remove(uri)
    }

    @Synchronized
    fun <T> accepts(batch: DiagnosticBatch<T>): Boolean {
        val current = versions[batch.uri] ?: return false
        return batch.version == null || batch.version == current
    }

    private fun update(uri: String, version: Int) {
        require(version >= 0) { "Document version cannot be negative" }
        val previous = versions[uri]
        require(previous == null || version > previous) {
            "Document version must increase: current=$previous, next=$version"
        }
        versions[uri] = version
    }
}
