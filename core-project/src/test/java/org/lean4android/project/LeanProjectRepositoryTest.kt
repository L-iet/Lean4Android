package org.lean4android.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LeanProjectRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun createSaveExportDeleteAndImportRoundTrip() {
        val root = temporary.newFolder("projects")
        val repository = LeanProjectRepository(root, "lean-4.32.1-android1")
        val created = repository.create("sample")
        assertEquals(listOf("Main.lean", "Sample/Basic.lean"), created.sourceFiles)
        assertTrue(created.directory.resolve("lakefile.toml").readText().contains("roots = [\"Main\", \"Sample.Basic\"]"))

        repository.save("sample", "Sample/Basic.lean", "namespace Sample\ndef answer : Nat := 41\nend Sample\n")
        val archive = temporary.root.resolve("sample.zip")
        repository.export("sample", archive)
        repository.delete("sample")
        assertFalse(root.resolve("sample").exists())

        val imported = repository.import("sample", archive)
        assertEquals(2, imported.sourceFiles.size)
        assertTrue(imported.directory.resolve("Sample/Basic.lean").readText().contains("41"))
    }

    @Test fun rejectsWrongToolchainAndUnsupportedWorkflow() {
        val root = temporary.newFolder("projects")
        val repository = LeanProjectRepository(root, "expected")
        repository.create("sample")
        root.resolve("sample/.lean4android-project").writeText("schema=1\ntoolchain=other\n")
        expectFailure("requires other") { repository.open("sample") }

        root.resolve("sample/.lean4android-project").writeText("schema=1\ntoolchain=expected\n")
        root.resolve("sample/lakefile.toml").appendText("\nrequire foo from git \"https://example.invalid/foo\"\n")
        expectFailure("not supported offline") { repository.open("sample") }
    }

    @Test fun rejectsZipTraversalAndCleansStaging() {
        val root = temporary.newFolder("projects")
        val archive = temporary.root.resolve("hostile.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../escaped"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        val repository = LeanProjectRepository(root, "toolchain")
        expectFailure("escapes") { repository.import("hostile", archive) }
        assertFalse(temporary.root.resolve("escaped").exists())
        assertFalse(root.resolve(".hostile.importing").exists())
    }

    @Test fun rejectsUnsafeNamesAndNonLeanSave() {
        val repository = LeanProjectRepository(temporary.newFolder("projects"), "toolchain")
        expectFailure("safe filename") { repository.create("../bad") }
        repository.create("good")
        expectFailure("Only Lean") { repository.save("good", "notes.txt", "no") }
    }

    @Test fun rapidSaveAdvancesTimestampForLakeIncrementality() {
        val repository = LeanProjectRepository(temporary.newFolder("projects"), "toolchain")
        val project = repository.create("sample")
        val source = project.directory.resolve("Sample/Basic.lean")
        val before = source.lastModified()
        repository.save("sample", "Sample/Basic.lean", "def fixed := true\n")
        assertTrue(source.lastModified() >= before + 1_000L)
    }

    private fun expectFailure(message: String, block: () -> Unit) {
        try {
            block()
            fail("Expected failure containing $message")
        } catch (failure: IllegalArgumentException) {
            assertTrue(failure.message.orEmpty().contains(message))
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains(message))
        }
    }
}
