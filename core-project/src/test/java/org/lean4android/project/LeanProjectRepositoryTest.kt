package org.lean4android.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
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
        assertFalse(created.directory.resolve("lakefile.toml").readText().contains("globs ="))

        repository.save("sample", "Sample/Basic.lean", "namespace Sample\ndef answer : Nat := 41\nend Sample\n")
        repository.createSource("sample", "notes/readme.txt", "portable notes\n")
        val archive = temporary.root.resolve("sample.zip")
        repository.export("sample", archive)
        repository.delete("sample")
        assertFalse(root.resolve("sample").exists())

        val imported = repository.import("sample", archive)
        assertEquals(2, imported.sourceFiles.size)
        assertTrue(imported.directory.resolve("Sample/Basic.lean").readText().contains("41"))
        assertEquals("portable notes\n", repository.read("sample", "notes/readme.txt"))
    }

    @Test fun `project names normalize spaces and creation rejects invalid and case folded collisions`() {
        assertEquals("My-Lean-Project", LeanProjectRepository.normalizeProjectId("  My  Lean Project  "))
        val repository = LeanProjectRepository(temporary.newFolder("named-projects"), "toolchain")
        val created = repository.create("My-Lean-Project")
        val lakefile = created.directory.resolve("lakefile.toml").readText()
        assertTrue(lakefile.contains("name = \"MyLeanProject\""))
        assertTrue(lakefile.contains("roots = [\"Main\", \"MyLeanProject.Basic\"]"))
        assertFalse(lakefile.contains("globs ="))
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
            val expectedRoots = listOf("Main", "$leanName.Basic").sorted().joinToString(prefix = "[\"", separator = "\", \"", postfix = "\"]")
            assertTrue("library identity for $id", lakefile.contains("name = \"$leanName\"\nroots = $expectedRoots"))
            assertFalse("roots provide the default exact module globs for $id", lakefile.contains("globs ="))
            assertTrue(project.directory.resolve("Main.lean").readText().contains("namespace $leanName.Main"))
            assertTrue(project.directory.resolve("$leanName/Basic.lean").isFile)
        }
    }

    @Test fun portableExportIsDeterministicAndExcludesGeneratedAndPrivateFiles() {
        val repository = LeanProjectRepository(temporary.newFolder("projects"), "toolchain")
        val project = repository.create("sample")
        project.directory.resolve(".lake/build/output.olean").apply { parentFile?.mkdirs(); writeText("generated") }
        project.directory.resolve("notes.txt").writeText("portable")

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
        assertEquals(listOf(".lean4android-project", "Main.lean", "Sample/Basic.lean", "lakefile.toml", "lean-toolchain", "notes.txt"), entries)
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

    @Test fun rejectsUnsafeNamesAndMissingFileSave() {
        val repository = LeanProjectRepository(temporary.newFolder("projects"), "toolchain")
        expectFailure("safe filename") { repository.create("../bad") }
        repository.create("good")
        expectFailure("does not exist") { repository.save("good", "notes.txt", "no") }
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

    @Test fun `lake configuration enumerates exact source modules including overlapping modules`() {
        val repository = LeanProjectRepository(temporary.newFolder("projects"), "toolchain")
        val project = repository.create("sample-project")

        assertEquals("SampleProject/New.lean", LeanProjectRepository.defaultNewSourcePath("sample-project"))
        repository.createSource("sample-project", "Extra.lean", "def extra := true\n")
        repository.createSource("sample-project", "Extra/Nested.lean", "def nested := true\n")
        repository.createSource("sample-project", "Other/Deep/Value.lean", "def value := 1\n")

        val lakefile = project.directory.resolve("lakefile.toml").readText()
        assertTrue(lakefile.contains("roots = [\"Extra\", \"Extra.Nested\", \"Main\", \"Other.Deep.Value\", \"SampleProject.Basic\"]"))
        assertFalse(lakefile.contains("globs ="))

        repository.deleteEntry("sample-project", "Other")
        val afterDelete = project.directory.resolve("lakefile.toml").readText()
        assertFalse(afterDelete.contains("Other.Deep.Value"))
    }

    @Test fun `legacy generated lake block migrates deterministically and rejects ambiguous modules`() {
        val repository = LeanProjectRepository(temporary.newFolder("projects"), "toolchain")
        val project = repository.create("sample")
        project.directory.resolve("lakefile.toml").writeText(
            "name = \"Sample\"\nversion = \"0.1.0\"\ndefaultTargets = [\"Sample\"]\n\n" +
                "[[lean_lib]]\nname = \"Sample\"\nroots = [\"Main\", \"Sample.Basic\"]\n",
        )

        repository.reconcileLakeConfiguration("sample")
        val migrated = project.directory.resolve("lakefile.toml").readText()
        assertTrue(migrated.contains("roots = [\"Main\", \"Sample.Basic\"]"))
        assertFalse(migrated.contains("globs ="))
        val timestamp = project.directory.resolve("lakefile.toml").lastModified()
        repository.reconcileLakeConfiguration("sample")
        assertEquals(timestamp, project.directory.resolve("lakefile.toml").lastModified())

        expectFailure("Lean module path components") { repository.createSource("sample", "bad-name/File.lean") }
    }

    @Test fun `project rename updates generated package identity and unsupported block fails before mutation`() {
        val repository = LeanProjectRepository(temporary.newFolder("projects"), "toolchain")
        repository.create("old-name")
        val renamed = repository.renameProject("old-name", "new-name")
        val lakefile = renamed.directory.resolve("lakefile.toml").readText()
        assertTrue(lakefile.startsWith("name = \"NewName\""))
        assertTrue(lakefile.contains("defaultTargets = [\"NewName\"]"))
        assertTrue(lakefile.contains("[[lean_lib]]\nname = \"NewName\""))
        assertTrue(lakefile.contains("roots = [\"Main\", \"OldName.Basic\"]"))
        assertFalse(lakefile.contains("globs ="))

        renamed.directory.resolve("lakefile.toml").appendText("\n[[lean_lib]]\nname = \"Second\"\nroots = [\"Main\"]\n")
        expectFailure("cannot be updated safely") {
            repository.createSource("new-name", "NewName/Blocked.lean", "def blocked := true\n")
        }
        assertFalse(renamed.directory.resolve("NewName/Blocked.lean").exists())
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

    @Test fun projectInputRevisionIsBoundedHashedAndOpenedExactlyOnce() {
        val repository = LeanProjectRepository(temporary.newFolder("input-projects"), "toolchain")
        val project = repository.create("sample")
        project.directory.resolve("input.txt").writeBytes("α\n".toByteArray())

        val revision = repository.resolveInputRevision("sample", "input.txt")

        assertEquals("sample", revision.projectId)
        assertEquals("input.txt", revision.relativePath)
        assertEquals(3L, revision.expectedBytes)
        assertEquals("91bb11dcbf2e523dc5df43a5ec31662deab18be39b4b8897a106acf86a928214", revision.sha256)
        val source = revision.openSource()
        assertEquals("α\n", source.openStream().use { it.readBytes().toString(Charsets.UTF_8) })
        expectFailure("already opened") { source.openStream() }
    }

    @Test fun projectInputRevisionRejectsReplacementTraversalDirectoriesLinksAndOversize() {
        val repository = LeanProjectRepository(temporary.newFolder("input-safety"), "toolchain")
        val project = repository.create("sample")
        val input = project.directory.resolve("input.txt").apply { writeText("first") }
        val revision = repository.resolveInputRevision("sample", "input.txt")
        input.writeText("other")
        expectFailure("changed after it was selected") { revision.openSource() }

        expectFailure("escapes") { repository.resolveInputRevision("sample", "../outside") }
        expectFailure("regular file") { repository.resolveInputRevision("sample", "Sample") }

        val link = project.directory.resolve("linked.txt")
        runCatching { Files.createSymbolicLink(link.toPath(), input.toPath()) }.onSuccess {
            expectFailure("symbolic links") { repository.resolveInputRevision("sample", "linked.txt") }
        }

        val oversized = project.directory.resolve("oversized.bin")
        RandomAccessFile(oversized, "rw").use { it.setLength(64L * 1024 * 1024 + 1) }
        expectFailure("exceeds 64 MiB") { repository.resolveInputRevision("sample", "oversized.bin") }
    }

    @Test fun selectableProjectInputsAreDeterministicRegularBoundedAndExcludePrivateState() {
        val repository = LeanProjectRepository(temporary.newFolder("input-list"), "toolchain")
        val project = repository.create("sample")
        project.directory.resolve("z-input.txt").writeText("z")
        project.directory.resolve("Data/a-input.txt").apply { parentFile?.mkdirs(); writeText("a") }
        project.directory.resolve(".lake/build/private.bin").apply { parentFile?.mkdirs(); writeText("private") }
        project.directory.resolve(".hidden").writeText("hidden")
        RandomAccessFile(project.directory.resolve("oversized.bin"), "rw").use { it.setLength(64L * 1024 * 1024 + 1) }
        runCatching {
            Files.createSymbolicLink(project.directory.resolve("linked.txt").toPath(), project.directory.resolve("z-input.txt").toPath())
        }

        val inputs = repository.listInputFiles("sample")

        assertEquals(inputs.sorted(), inputs)
        assertTrue("Data/a-input.txt" in inputs)
        assertTrue("z-input.txt" in inputs)
        assertTrue("Main.lean" in inputs)
        assertFalse(inputs.any { it.startsWith('.') || it == "oversized.bin" || it == "linked.txt" })
    }

    @Test fun generalTextFilesAreVisibleStrictBoundedAtomicAndLifecycleSafe() {
        val repository = LeanProjectRepository(temporary.newFolder("general-text"), "toolchain")
        val project = repository.create("sample")

        repository.createSource("sample", "notes/readme.txt", "hello λ\n")
        assertTrue("notes/readme.txt" in repository.open("sample").files)
        assertFalse("notes/readme.txt" in repository.open("sample").sourceFiles)
        assertEquals("hello λ\n", repository.read("sample", "notes/readme.txt"))

        repository.save("sample", "notes/readme.txt", "updated\n")
        repository.renameEntry("sample", "notes/readme.txt", "notes/renamed.md")
        assertEquals("updated\n", repository.read("sample", "notes/renamed.md"))
        repository.deleteEntry("sample", "notes/renamed.md")
        assertFalse("notes/renamed.md" in repository.open("sample").files)

        project.directory.resolve("binary.dat").writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        assertTrue("binary.dat" in repository.open("sample").files)
        expectFailure("not valid UTF-8") { repository.read("sample", "binary.dat") }

        RandomAccessFile(project.directory.resolve("large.txt"), "rw").use { it.setLength(8L * 1024 * 1024 + 1) }
        expectFailure("exceeds 8 MiB") { repository.read("sample", "large.txt") }
        expectFailure("metadata cannot be edited") { repository.save("sample", "lakefile.toml", "bad") }
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
