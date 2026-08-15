package org.lean4android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.lean4android.project.LeanProjectRepository

class EditorSessionStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `dirty buffers and active tab survive store recreation`() {
        val repository = LeanProjectRepository(temporary.newFolder("projects"), "toolchain")
        repository.create("sample")
        val snapshot = temporary.root.resolve("recovery/editor.bin")
        val store = EditorSessionStore(snapshot)
        val initial = store.loadOrCreate(repository, "sample")
        val edited = initial.edit("Main.lean", "unsaved main").select("Sample/Basic.lean")
        store.save(edited)

        val restored = EditorSessionStore(snapshot).loadOrCreate(repository, "sample")
        assertEquals("Sample/Basic.lean", restored.activePath)
        assertEquals("unsaved main", restored.tabs.single { it.path == "Main.lean" }.contents)
        assertTrue(restored.tabs.single { it.path == "Main.lean" }.dirty)
        assertFalse(restored.tabs.single { it.path == "Sample/Basic.lean" }.dirty)
    }

    @Test fun `successful save baseline clears dirty state`() {
        val state = EditorSessionState("sample", listOf(EditorTab("Main.lean", "old", "new")), "Main.lean")
        assertTrue(state.tabs.single().dirty)
        assertFalse(state.markSaved().tabs.single().dirty)
    }

    @Test fun `corrupt snapshot falls back to project files`() {
        val repository = LeanProjectRepository(temporary.newFolder("projects"), "toolchain")
        repository.create("sample")
        val snapshot = temporary.root.resolve("editor.bin").apply { writeText("broken") }

        val restored = EditorSessionStore(snapshot).loadOrCreate(repository, "sample")
        assertEquals(listOf("Main.lean", "Sample/Basic.lean"), restored.tabs.map(EditorTab::path))
        assertEquals("Main.lean", restored.activePath)
        assertTrue(restored.tabs.none(EditorTab::dirty))
    }

    @Test fun `large project initially opens Main and one companion while preserving drawer discovery`() {
        val repository = LeanProjectRepository(temporary.newFolder("large-projects"), "toolchain")
        repository.create("large")
        repeat(30) { index -> repository.createSource("large", "Sources/File$index.lean", "def value$index := $index\n") }

        val restored = EditorSessionStore(temporary.root.resolve("missing.bin")).loadOrCreate(repository, "large")

        assertEquals(listOf("Main.lean", "Large/Basic.lean"), restored.tabs.map(EditorTab::path))
        assertEquals(32, repository.open("large").sourceFiles.size)
    }

    @Test fun `tab operations retain dirty content and choose a surviving active tab`() {
        val initial = EditorSessionState(
            "sample",
            listOf(EditorTab("Main.lean", "saved", "dirty"), EditorTab("Basic.lean", "basic", "basic")),
            "Main.lean",
        )
        val renamed = initial.rename("Main.lean", "Renamed.lean")
        assertEquals("dirty", renamed.tabs.single { it.path == "Renamed.lean" }.contents)
        val added = renamed.add("Third.lean")
        assertEquals("Third.lean", added.activePath)
        val removed = added.remove("Third.lean")
        assertEquals("Basic.lean", removed.activePath)
    }

    @Test fun `save as retains original and close permits empty editor`() {
        val initial = EditorSessionState(
            "sample", listOf(EditorTab("Main.lean", "saved", "edited")), "Main.lean",
        )
        val copied = initial.saveAs("Main.lean", "Copy.lean")
        assertEquals(listOf("Main.lean", "Copy.lean"), copied.tabs.map(EditorTab::path))
        assertTrue(copied.tabs.first().dirty)
        assertFalse(copied.tabs.last().dirty)
        val empty = copied.remove("Copy.lean").remove("Main.lean")
        assertTrue(empty.tabs.isEmpty())
        assertEquals(null, empty.activePath)
    }

    @Test fun `folder rename and delete remap every affected open tab`() {
        val initial = EditorSessionState(
            "sample",
            listOf(
                EditorTab("Main.lean", "main", "main"),
                EditorTab("Old/One.lean", "one", "dirty one"),
                EditorTab("Old/Deep/Two.lean", "two", "two"),
            ),
            "Old/Deep/Two.lean",
        )

        val renamed = initial.renamePrefix("Old", "Sources")
        assertEquals(listOf("Main.lean", "Sources/One.lean", "Sources/Deep/Two.lean"), renamed.tabs.map(EditorTab::path))
        assertEquals("Sources/Deep/Two.lean", renamed.activePath)
        assertTrue(renamed.tabs.single { it.path == "Sources/One.lean" }.dirty)

        val deleted = renamed.removePrefix("Sources")
        assertEquals(listOf("Main.lean"), deleted.tabs.map(EditorTab::path))
        assertEquals("Main.lean", deleted.activePath)
    }
}
