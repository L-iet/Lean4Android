package org.lean4android.lsp

/** Typed constructors for standard LSP and pinned Lean extension requests. */
object LeanLspRequests {
    fun request(id: Long, method: String, params: JsonValue): String {
        require(id >= 0) { "Request ID cannot be negative" }
        require(method.isNotBlank()) { "Request method cannot be blank" }
        return """{"jsonrpc":"2.0","id":$id,"method":${method.jsonString()},"params":${params.render()}}"""
    }

    fun textDocumentPosition(uri: String, line: Int, character: Int): JsonValue.ObjectValue {
        require(line >= 0 && character >= 0) { "Document position cannot be negative" }
        return JsonValue.ObjectValue(linkedMapOf(
            "textDocument" to JsonValue.ObjectValue(mapOf("uri" to JsonValue.StringValue(uri))),
            "position" to JsonValue.ObjectValue(linkedMapOf(
                "line" to JsonValue.NumberValue(line.toString()),
                "character" to JsonValue.NumberValue(character.toString()),
            )),
        ))
    }

    fun rpcConnect(id: Long, uri: String): String = request(
        id,
        "$/lean/rpc/connect",
        JsonValue.ObjectValue(mapOf("uri" to JsonValue.StringValue(uri))),
    )

    fun plainGoal(id: Long, uri: String, line: Int, character: Int): String =
        request(id, "$/lean/plainGoal", textDocumentPosition(uri, line, character))

    fun plainTermGoal(id: Long, uri: String, line: Int, character: Int): String =
        request(id, "$/lean/plainTermGoal", textDocumentPosition(uri, line, character))

    fun hover(id: Long, uri: String, line: Int, character: Int): String =
        request(id, "textDocument/hover", textDocumentPosition(uri, line, character))

    fun completion(id: Long, uri: String, line: Int, character: Int): String =
        request(id, "textDocument/completion", textDocumentPosition(uri, line, character))

    fun definition(id: Long, uri: String, line: Int, character: Int): String =
        request(id, "textDocument/definition", textDocumentPosition(uri, line, character))

    fun references(id: Long, uri: String, line: Int, character: Int): String {
        val fields = textDocumentPosition(uri, line, character).fields
        return request(id, "textDocument/references", JsonValue.ObjectValue(linkedMapOf(
            "textDocument" to requireNotNull(fields["textDocument"]),
            "position" to requireNotNull(fields["position"]),
            "context" to JsonValue.ObjectValue(mapOf("includeDeclaration" to JsonValue.BooleanValue(true))),
        )))
    }

    fun interactiveGoals(
        id: Long,
        uri: String,
        line: Int,
        character: Int,
        sessionId: Long,
    ): String {
        require(sessionId >= 0) { "RPC session ID cannot be negative" }
        val position = textDocumentPosition(uri, line, character).fields
        return request(
            id,
            "$/lean/rpc/call",
            JsonValue.ObjectValue(linkedMapOf(
                "textDocument" to requireNotNull(position["textDocument"]),
                "position" to requireNotNull(position["position"]),
                "sessionId" to JsonValue.NumberValue(sessionId.toString()),
                "method" to JsonValue.StringValue("Lean.Widget.getInteractiveGoals"),
                "params" to JsonValue.ObjectValue(linkedMapOf(
                    "textDocument" to requireNotNull(position["textDocument"]),
                    "position" to requireNotNull(position["position"]),
                )),
            )),
        )
    }
}
