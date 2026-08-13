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

        assertTrue(open.contains("\"languageId\":\"lean\""))
        assertTrue(open.contains("#check \\\"λ\\\"\\n"))
        assertTrue(change.contains("\"version\":2"))
        assertTrue(change.contains("\"contentChanges\":[{"))
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
