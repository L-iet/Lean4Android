package org.lean4android.lsp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LeanLspDocumentsTest {
    @Test
    fun `document notifications preserve Lean language and escaped Unicode source`() {
        val uri = "file:///project space/Main.lean"
        val open = LeanLspDocuments.didOpen(uri, 1, "#check \"λ\"\n")
        val change = LeanLspDocuments.didChange(uri, 2, "#eval 1 + 1\n")
        val save = LeanLspDocuments.didSave(uri, "#check \"λ\"\n")

        assertTrue(open.contains("\"languageId\":\"lean\""))
        assertTrue(open.contains("#check \\\"λ\\\"\\n"))
        assertTrue(change.contains("\"version\":2"))
        assertTrue(change.contains("\"contentChanges\":[{"))
        assertTrue(save.contains("\"method\":\"textDocument/didSave\""))
        assertTrue(save.contains("#check \\\"λ\\\"\\n"))
    }

    @Test
    fun `standard and pinned Lean requests encode UTF-16 positions and RPC session`() {
        val uri = "file:///project space/Main.lean"
        val hover = LeanLspRequests.request(7, "textDocument/hover", LeanLspRequests.textDocumentPosition(uri, 3, 5))
        val termGoal = LeanLspRequests.plainTermGoal(11, uri, 3, 5)
        val connect = LeanLspRequests.rpcConnect(8, uri)
        val goals = LeanLspRequests.interactiveGoals(9, uri, 3, 5, 42)
        val references = LeanLspRequests.references(10, uri, 3, 5)

        assertTrue(hover.contains("\"id\":7"))
        assertTrue(hover.contains("\"line\":3,\"character\":5"))
        assertTrue(termGoal.contains("\"method\":\"$/lean/plainTermGoal\""))
        assertTrue(termGoal.contains("\"line\":3,\"character\":5"))
        assertTrue(connect.contains("$/lean/rpc/connect"))
        assertTrue(goals.contains("Lean.Widget.getInteractiveGoals"))
        assertTrue(goals.contains("\"sessionId\":42"))
        assertTrue(references.contains("textDocument/references"))
        assertTrue(references.contains("\"includeDeclaration\":true"))
    }

    @Test
    fun `version gate accepts current and rejects stale closed and unknown diagnostics`() {
        val gate = DocumentVersionGate()
        val uri = "file:///project/Main.lean"
        gate.opened(uri, 1)
        assertTrue(gate.accepts(DiagnosticBatch(uri, 1, listOf("first"))))

        gate.changed(uri, 2)
        assertFalse(gate.accepts(DiagnosticBatch(uri, 1, listOf("stale"))))
        assertTrue(gate.accepts(DiagnosticBatch(uri, 2, listOf("current"))))
        assertTrue(gate.accepts(DiagnosticBatch(uri, null, listOf("unversioned compatibility"))))

        gate.closed(uri)
        assertFalse(gate.accepts(DiagnosticBatch(uri, 2, emptyList<String>())))
        assertFalse(gate.accepts(DiagnosticBatch("file:///unknown", 1, emptyList<String>())))
    }

    @Test
    fun `document versions must increase`() {
        val gate = DocumentVersionGate()
        gate.opened("file:///Main.lean", 3)

        assertThrows(IllegalArgumentException::class.java) {
            gate.changed("file:///Main.lean", 3)
        }
    }
}
