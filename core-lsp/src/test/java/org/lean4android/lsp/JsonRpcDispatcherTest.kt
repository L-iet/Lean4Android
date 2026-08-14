package org.lean4android.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRpcDispatcherTest {
    @Test
    fun `parser handles nested unicode envelopes and round trips JSON values`() {
        val payload = """{"jsonrpc":"2.0","id":"α","result":{"ok":true,"items":[null,-1.5e2,"line\n"]}}"""

        val response = JsonRpcEnvelopeParser.parse(payload) as JsonRpcEnvelope.Response

        assertEquals(JsonRpcId.StringId("α"), response.id)
        assertEquals("""{"ok":true,"items":[null,-1.5e2,"line\n"]}""", response.result!!.render())
    }

    @Test
    fun `dynamic registration is acknowledged and not forwarded as unknown request`() {
        val replies = mutableListOf<Pair<JsonRpcId, JsonValue>>()
        val requests = mutableListOf<JsonRpcEnvelope.Request>()
        val dispatcher = LeanLspDispatcher(
            responder = JsonRpcResponder { id, result -> replies += id to result },
            acceptsDiagnostics = { true },
            sink = object : LeanLspEventSink {
                override fun onServerRequest(request: JsonRpcEnvelope.Request) { requests += request }
            },
        )

        dispatcher.dispatch("""{"jsonrpc":"2.0","id":7,"method":"client/registerCapability","params":{"registrations":[]}}""")

        assertEquals(listOf(JsonRpcId.NumberId("7") to JsonValue.NullValue), replies)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `only current version diagnostics reach diagnostic sink`() {
        val accepted = mutableListOf<DiagnosticBatch<JsonValue.ObjectValue>>()
        val notifications = mutableListOf<JsonRpcEnvelope.Notification>()
        val dispatcher = LeanLspDispatcher(
            responder = JsonRpcResponder { _, _ -> },
            acceptsDiagnostics = { it.version == 2 },
            sink = object : LeanLspEventSink {
                override fun onNotification(notification: JsonRpcEnvelope.Notification) { notifications += notification }
                override fun onDiagnostics(batch: DiagnosticBatch<JsonValue.ObjectValue>) { accepted += batch }
            },
        )
        val diagnostic = """{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"message":"bad"}"""

        dispatcher.dispatch("""{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"file:///Main.lean","version":1,"diagnostics":[$diagnostic]}}""")
        dispatcher.dispatch("""{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"file:///Main.lean","version":2,"diagnostics":[$diagnostic]}}""")

        assertEquals(1, accepted.size)
        assertEquals(2, accepted.single().version)
        assertEquals(2, notifications.size)
    }

    @Test
    fun `malformed duplicate and ambiguous envelopes are rejected`() {
        val invalid = listOf(
            """{"jsonrpc":"2.0","id":1,"id":2,"result":null}""",
            """{"jsonrpc":"2.0","id":1,"result":null,"error":null}""",
            """{"jsonrpc":"1.0","method":"x"}""",
            """{"jsonrpc":"2.0","id":null,"result":null}""",
            """{"jsonrpc":"2.0","method":"x","params":[1,]}""",
        )

        invalid.forEach { payload ->
            assertTrue("Expected rejection: $payload", runCatching { JsonRpcEnvelopeParser.parse(payload) }.isFailure)
        }
    }
}
