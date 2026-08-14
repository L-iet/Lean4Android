package org.lean4android.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lean4android.project.LeanProjectRepository
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class M31WorkspaceImportTest {
    @Test fun archiveFolderAndLeanFileCopiesStayInternalAndRejectHostileWorkflow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.currentTimeMillis().toString()
        val repository = LeanProjectRepository(context.filesDir.resolve("projects"), "lean-4.32.1-android1")
        val fixture = context.cacheDir.resolve("m31-fixture-$suffix").apply { mkdirs() }
        val valid = fixture.resolve("valid").apply { mkdirs() }
        writeProject(valid, hostile = false)
        val hostile = fixture.resolve("hostile").apply { mkdirs() }
        writeProject(hostile, hostile = true)
        val archive = fixture.resolve("valid.zip").also { zip(valid, it) }
        val hostileArchive = fixture.resolve("hostile.zip").also { zip(hostile, it) }
        val lean = fixture.resolve("Picked.lean").apply { writeText("#eval 91\n") }
        val archiveId = "m31_archive_$suffix"
        val folderId = "m31_folder_$suffix"
        val scratchId = "m31_scratch_$suffix"
        val destination = "Imported" + suffix.takeLast(6) + ".lean"
        try {
            assertTrue(repository.import(archiveId, archive).sourceFiles.contains("Main.lean"))
            assertTrue(repository.importFolder(folderId, valid).sourceFiles.contains("Main.lean"))
            assertTrue(repository.importStandalone(scratchId, "Picked.lean", lean).sourceFiles.contains("Picked.lean"))
            repository.importSource(archiveId, destination, lean)
            assertTrue(repository.read(archiveId, destination).contains("91"))
            val rejected = runCatching { repository.import("m31_hostile_$suffix", hostileArchive) }.isFailure
            assertTrue(rejected)
            assertFalse(context.filesDir.resolve("projects/m31_hostile_$suffix").exists())
        } finally {
            listOf(archiveId, folderId, scratchId).forEach { id -> runCatching { repository.delete(id) } }
            fixture.deleteRecursively()
        }
    }

    private fun writeProject(root: File, hostile: Boolean) {
        root.resolve(".lean4android-project").writeText("schema=1\ntoolchain=lean-4.32.1-android1\n")
        root.resolve("lean-toolchain").writeText("leanprover/lean4:v4.32.1\n")
        root.resolve("lakefile.toml").writeText(
            if (hostile) "name = \"Hostile\"\nrequire bad from git \"https://example.invalid/bad\"\n"
            else "name = \"Imported\"\n[[lean_lib]]\nname = \"Imported\"\nroots = [\"Main\"]\n",
        )
        root.resolve("Main.lean").writeText("#eval 73\n")
    }

    private fun zip(source: File, destination: File) {
        ZipOutputStream(destination.outputStream()).use { output ->
            source.walkTopDown().filter(File::isFile).forEach { file ->
                output.putNextEntry(ZipEntry(file.relativeTo(source).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
        }
    }
}
