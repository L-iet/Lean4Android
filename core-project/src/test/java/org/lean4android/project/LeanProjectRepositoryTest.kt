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

    @Test fun sourceFileOperationsStayContainedAndPreserveAtLeastOneSource() {
        val repository = LeanProjectRepository(temporary.newFolder("projects"), "toolchain")
        repository.create("sample")

        repository.createSource("sample", "Extra/Nested.lean", "def nested := true\n")
        assertEquals("def nested := true\n", repository.read("sample", "Extra/Nested.lean"))
        val renamed = repository.renameSource("sample", "Extra/Nested.lean", "Extra/Renamed.lean")
        assertTrue("Extra/Renamed.lean" in renamed.sourceFiles)
        assertFalse("Extra/Nested.lean" in renamed.sourceFiles)
        val afterDelete = repository.deleteSource("sample", "Extra/Renamed.lean")
        assertFalse("Extra/Renamed.lean" in afterDelete.sourceFiles)

        expectFailure("escapes") { repository.createSource("sample", "../Outside.lean") }
        repository.deleteSource("sample", "Main.lean")
        expectFailure("at least one") { repository.deleteSource("sample", "Sample/Basic.lean") }
    }

    @Test fun saveAsRetainsOriginalAndRejectsCaseFoldedCollision() {
        val repository = LeanProjectRepository(temporary.newFolder("projects"), "toolchain")
        repository.create("sample")
        repository.copySource("sample", "Main.lean", "Copies/MainCopy.lean", "def copied := 7\n")
        assertTrue(repository.read("sample", "Main.lean").contains("import"))
        assertEquals("def copied := 7\n", repository.read("sample", "Copies/MainCopy.lean"))
        expectFailure("case-insensitive") {
            repository.createSource("sample", "copies/maincopy.lean")
        }
    }

    @Test fun folderAndStandaloneImportsActivateOnlyValidatedCopies() {
        val root = temporary.newFolder("projects")
        val repository = LeanProjectRepository(root, "toolchain")
        val sourceRoot = temporary.newFolder("folder")
        LeanProjectRepository(temporary.newFolder("builder"), "toolchain").create("source").directory
            .copyRecursively(sourceRoot, overwrite = true)
        val imported = repository.importFolder("folder_copy", sourceRoot)
        assertEquals(2, imported.sourceFiles.size)

        val lean = temporary.root.resolve("Chosen.lean").apply { writeText("def chosen := 9\n") }
        val scratch = repository.importStandalone("scratch", "Chosen.lean", lean)
        assertEquals("def chosen := 9\n", repository.read(scratch.id, "Chosen.lean"))
        assertTrue(repository.read(scratch.id, "Main.lean").contains("import"))

        val hostile = temporary.newFolder("hostile").apply { resolve("lakefile.toml").writeText("require x from git \"https://bad\"") }
        expectFailure("metadata is missing") { repository.importFolder("bad", hostile) }
        assertFalse(root.resolve("bad").exists())
        assertFalse(root.resolve(".bad.importing").exists())
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
