package org.lean4android.lsp

sealed interface JsonRpcId {
    data class NumberId(val source: String) : JsonRpcId
    data class StringId(val value: String) : JsonRpcId

    fun render(): String = when (this) {
        is NumberId -> source
        is StringId -> value.jsonString()
    }
}

sealed interface JsonRpcEnvelope {
    data class Request(val id: JsonRpcId, val method: String, val params: JsonValue?) : JsonRpcEnvelope
    data class Notification(val method: String, val params: JsonValue?) : JsonRpcEnvelope
    data class Response(val id: JsonRpcId, val result: JsonValue?, val error: JsonValue?) : JsonRpcEnvelope
}

object JsonRpcEnvelopeParser {
    fun parse(payload: String): JsonRpcEnvelope {
        val root = JsonValueParser.parse(payload) as? JsonValue.ObjectValue
            ?: throw JsonParseException("JSON-RPC payload must be an object")
        val fields = root.fields
        require((fields["jsonrpc"] as? JsonValue.StringValue)?.value == "2.0") { "Unsupported JSON-RPC version" }
        val id = fields["id"]?.let(::id)
        val method = (fields["method"] as? JsonValue.StringValue)?.value
        return when {
            method != null && id != null -> JsonRpcEnvelope.Request(id, method, fields["params"])
            method != null -> JsonRpcEnvelope.Notification(method, fields["params"])
            id != null -> {
                val hasResult = fields.containsKey("result")
                val hasError = fields.containsKey("error")
                require(hasResult.xor(hasError)) { "JSON-RPC response requires exactly one of result or error" }
                JsonRpcEnvelope.Response(id, fields["result"], fields["error"])
            }
            else -> throw JsonParseException("Unrecognized JSON-RPC envelope")
        }
    }

    private fun id(value: JsonValue): JsonRpcId = when (value) {
        is JsonValue.NumberValue -> JsonRpcId.NumberId(value.source)
        is JsonValue.StringValue -> JsonRpcId.StringId(value.value)
        else -> throw JsonParseException("JSON-RPC id must be a number or string")
    }
}

fun interface JsonRpcResponder {
    fun reply(id: JsonRpcId, result: JsonValue)
}

interface LeanLspEventSink {
    fun onResponse(response: JsonRpcEnvelope.Response) = Unit
    fun onServerRequest(request: JsonRpcEnvelope.Request) = Unit
    fun onNotification(notification: JsonRpcEnvelope.Notification) = Unit
    fun onDiagnostics(batch: DiagnosticBatch<JsonValue.ObjectValue>) = Unit
}

class LeanLspDispatcher(
    private val responder: JsonRpcResponder,
    private val acceptsDiagnostics: (DiagnosticBatch<JsonValue.ObjectValue>) -> Boolean,
    private val sink: LeanLspEventSink,
) {
    fun dispatch(payload: String) {
        when (val envelope = JsonRpcEnvelopeParser.parse(payload)) {
            is JsonRpcEnvelope.Response -> sink.onResponse(envelope)
            is JsonRpcEnvelope.Request -> {
                if (envelope.method == "client/registerCapability") {
                    responder.reply(envelope.id, JsonValue.NullValue)
                } else {
                    sink.onServerRequest(envelope)
                }
            }
            is JsonRpcEnvelope.Notification -> {
                val diagnostics = envelope.toDiagnostics()
                if (diagnostics != null && acceptsDiagnostics(diagnostics)) sink.onDiagnostics(diagnostics)
                sink.onNotification(envelope)
            }
        }
    }

    private fun JsonRpcEnvelope.Notification.toDiagnostics(): DiagnosticBatch<JsonValue.ObjectValue>? {
        if (method != "textDocument/publishDiagnostics") return null
        val fields = (params as? JsonValue.ObjectValue)?.fields ?: return null
        val uri = (fields["uri"] as? JsonValue.StringValue)?.value ?: return null
        val versionValue = fields["version"]
        val version = when (versionValue) {
            null, JsonValue.NullValue -> null
            is JsonValue.NumberValue -> versionValue.source.toIntOrNull() ?: return null
            else -> return null
        }
        val values = (fields["diagnostics"] as? JsonValue.ArrayValue)?.values ?: return null
        val diagnostics = values.map { it as? JsonValue.ObjectValue ?: return null }
        return DiagnosticBatch(uri, version, diagnostics)
    }
}
