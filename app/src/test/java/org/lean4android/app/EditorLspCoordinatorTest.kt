package org.lean4android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorLspCoordinatorTest {
    @Test fun generationReopensDocumentsAndEditsIncreaseVersions() {
        val coordinator = EditorLspCoordinator()
        val uri = "file:///project/Main.lean"

        assertEquals(1, coordinator.activate(7, mapOf(uri to "#check Nat\n")).single().version)
        assertTrue(coordinator.activate(7, mapOf(uri to "ignored")).isEmpty())
        assertNull(coordinator.edit(uri, "#check Nat\n"))
        assertEquals(2, coordinator.edit(uri, "#check String\n")?.version)
        assertEquals(3, coordinator.edit(uri, "#check Bool\n")?.version)
        assertEquals(3, coordinator.currentVersion(uri))

        assertEquals(1, coordinator.activate(8, mapOf(uri to "#check Bool\n")).single().version)
        assertEquals(1, coordinator.currentVersion(uri))
    }

    @Test fun saveAndCloseAreBoundToOpenCurrentGenerationDocuments() {
        val coordinator = EditorLspCoordinator()
        val uri = "file:///project/Main.lean"
        coordinator.activate(1, mapOf(uri to "#check Nat\n"))

        assertEquals("#check Nat\n", coordinator.save(uri)?.text)
        assertEquals(uri, coordinator.close(uri)?.uri)
        assertNull(coordinator.currentVersion(uri))
        assertNull(coordinator.save(uri))
    }
}
