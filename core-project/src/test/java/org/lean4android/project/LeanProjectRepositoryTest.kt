package org.lean4android.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
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

    @Test fun `project names normalize spaces and creation rejects invalid and case folded collisions`() {
        assertEquals("My-Lean-Project", LeanProjectRepository.normalizeProjectId("  My  Lean Project  "))
        val repository = LeanProjectRepository(temporary.newFolder("named-projects"), "toolchain")
        val created = repository.create("My-Lean-Project")
        val lakefile = created.directory.resolve("lakefile.toml").readText()
        assertTrue(lakefile.contains("name = \"MyLeanProject\""))
        assertTrue(lakefile.contains("roots = [\"Main\", \"MyLeanProject.Basic\"]"))
        assertTrue(created.directory.resolve("Main.lean").readText().contains("namespace MyLeanProject.Main"))
        expectFailure("case-insensitive") { repository.create("my-lean-project") }
        expectFailure("1-64 safe filename") { repository.create("bad/name") }
    }

    @Test fun `common user project name styles generate one consistent valid Lean identity`() {
        val repository = LeanProjectRepository(temporary.newFolder("name-styles"), "toolchain")
        data class NameCase(val input: String, val id: String, val leanName: String)
        val cases = listOf(
            NameCase("Capitalized", "Capitalized", "Capitalized"),
            NameCase("snake_case", "snake_case", "SnakeCase"),
            NameCase("camelCase", "camelCase", "CamelCase"),
            NameCase("TitleCase", "TitleCase", "TitleCase"),
            NameCase("With spaces", "With-spaces", "WithSpaces"),
            NameCase("with-hyphens", "with-hyphens", "WithHyphens"),
        )
        cases.forEach { (input, id, leanName) ->
            assertEquals(id, LeanProjectRepository.normalizeProjectId(input))
            val project = repository.create(id)
            val lakefile = project.directory.resolve("lakefile.toml").readText()
            assertTrue("package identity for $id", lakefile.contains("name = \"$leanName\""))
            assertTrue("library identity for $id", lakefile.contains("name = \"$leanName\"\nroots = [\"Main\", \"$leanName.Basic\"]"))
            assertTrue(project.directory.resolve("Main.lean").readText().contains("namespace $leanName.Main"))
            assertTrue(project.directory.resolve("$leanName/Basic.lean").isFile)
        }
    }

    @Test fun portableExportIsDeterministicAndExcludesGeneratedAndPrivateFiles() {
        val repository = LeanProjectRepository(temporary.newFolder("projects"), "toolchain")
        val project = repository.create("sample")
        project.directory.resolve(".lake/build/output.olean").apply { parentFile?.mkdirs(); writeText("generated") }
        project.directory.resolve("editor-recovery.bin").writeText("private")
        project.directory.resolve("secret.token").writeText("secret")

        val first = java.io.ByteArrayOutputStream().also { repository.export("sample", it) }.toByteArray()
        val second = java.io.ByteArrayOutputStream().also { repository.export("sample", it) }.toByteArray()
        assertTrue(first.contentEquals(second))

        val entries = mutableListOf<String>()
        ZipInputStream(first.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += entry.name
                zip.closeEntry()
            }
        }
        assertEquals(entries.sorted(), entries)
        assertEquals(listOf(".lean4android-project", "Main.lean", "Sample/Basic.lean", "lakefile.toml", "lean-toolchain"), entries)
    }

    @Test fun portableExportRejectsSymbolicLinks() {
        val repository = LeanProjectRepository(temporary.newFolder("projects"), "toolchain")
        val project = repository.create("sample")
        val target = temporary.root.resolve("outside.lean").apply { writeText("def outside := true\n") }
        runCatching { Files.createSymbolicLink(project.directory.resolve("Linked.lean").toPath(), target.toPath()) }
            .getOrElse { return }
        expectFailure("symbolic links") { repository.export("sample", java.io.ByteArrayOutputStream()) }
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

    @Test fun projectAndFolderLifecycleOperationsAreContainedAndCollisionSafe() {
        val root = temporary.newFolder("projects")
        val repository = LeanProjectRepository(root, "toolchain")
        repository.create("sample")
        repository.createSource("sample", "Nested/Deep/One.lean", "def one := 1\n")
        repository.createSource("sample", "Nested/Two.lean", "def two := 2\n")

        val renamedFolder = repository.renameEntry("sample", "Nested", "Sources")
        assertTrue("Sources/Deep/One.lean" in renamedFolder.sourceFiles)
        assertTrue("Sources/Two.lean" in renamedFolder.sourceFiles)
        expectFailure("inside itself") { repository.renameEntry("sample", "Sources", "Sources/Child") }
        expectFailure("case-insensitive") { repository.renameEntry("sample", "Sources/Two.lean", "sources/deep/one.lean") }

        val afterFolderDelete = repository.deleteEntry("sample", "Sources")
        assertFalse(afterFolderDelete.sourceFiles.any { it.startsWith("Sources/") })
        expectFailure("escapes") { repository.deleteEntry("sample", "../outside") }

        val renamedProject = repository.renameProject("sample", "renamed-project")
        assertEquals("renamed-project", renamedProject.id)
        assertFalse(root.resolve("sample").exists())
        repository.create("occupied")
        expectFailure("case-insensitive") { repository.renameProject("renamed-project", "OCCUPIED") }
        repository.delete("renamed-project")
        assertFalse(root.resolve("renamed-project").exists())
    }

    @Test fun saveAsRetainsOriginalAndRejectsCaseFoldedCollision() {
        val repository = LeanProjectRepository(temporary.newFolder("projects"), "toolchain")
        repository.create("sample")
        repository.copySource("sample", "Main.lean", "Copies/MainCopy.lean", "def copied := 7\n")
        assertTrue(repository.read("sample", "Main.lean").contains("namespace Sample.Main"))
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
        assertTrue(repository.read(scratch.id, "Main.lean").contains("namespace Scratch.Main"))

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
