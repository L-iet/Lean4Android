package org.lean4android.app

/** UI-independent generation/version state for translating editor buffers to ordered LSP operations. */
internal class EditorLspCoordinator {
    sealed interface Operation {
        val uri: String

        data class Open(override val uri: String, val version: Int, val text: String) : Operation
        data class Change(override val uri: String, val version: Int, val text: String) : Operation
        data class Save(override val uri: String, val text: String) : Operation
        data class Close(override val uri: String) : Operation
    }

    private data class Document(var version: Int, var text: String)

    var generation: Long? = null
        private set
    private val documents = linkedMapOf<String, Document>()

    fun activate(generation: Long, buffers: Map<String, String>): List<Operation.Open> {
        if (this.generation == generation) return emptyList()
        this.generation = generation
        documents.clear()
        return buffers.map { (uri, text) ->
            documents[uri] = Document(version = 1, text = text)
            Operation.Open(uri, version = 1, text = text)
        }
    }

    fun edit(uri: String, text: String): Operation.Change? {
        val document = documents[uri] ?: return null
        if (document.text == text) return null
        document.version += 1
        document.text = text
        return Operation.Change(uri, document.version, text)
    }

    fun add(uri: String, text: String): Operation.Open {
        require(uri !in documents) { "Document is already open: $uri" }
        documents[uri] = Document(version = 1, text = text)
        return Operation.Open(uri, version = 1, text = text)
    }

    fun save(uri: String): Operation.Save? = documents[uri]?.let { Operation.Save(uri, it.text) }

    fun close(uri: String): Operation.Close? = documents.remove(uri)?.let { Operation.Close(uri) }

    fun currentVersion(uri: String): Int? = documents[uri]?.version

    fun currentText(uri: String): String? = documents[uri]?.text

    fun openUris(): Set<String> = documents.keys.toSet()

    fun clear() {
        generation = null
        documents.clear()
    }
}
