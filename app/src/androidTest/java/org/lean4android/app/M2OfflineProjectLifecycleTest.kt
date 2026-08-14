package org.lean4android.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.lean4android.model.ToolchainHealth
import org.lean4android.process.JvmCommandRunner
import org.lean4android.project.LeanProjectRepository
import org.lean4android.toolchain.AndroidToolchainLocator
import org.lean4android.toolchain.ToolchainCommandFactory

@RunWith(AndroidJUnit4::class)
class M2OfflineProjectLifecycleTest {
    @Test fun twoModuleErrorFixBuildExportDeleteAndReimport() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val locator = AndroidToolchainLocator(context)
        locator.installSysroot()
        val layout = (locator.locate() as ToolchainHealth.Ready).layout
        val projectId = "m2-instrumentation"
        val projectRoot = context.filesDir.resolve("instrumentation-projects")
        val repository = LeanProjectRepository(projectRoot, layout.id.value)
        runCatching { repository.delete(projectId) }
        val archive = context.cacheDir.resolve("$projectId.zip").also { it.delete() }

        val project = repository.create(projectId)
        val library = project.sourceFiles.single { it.endsWith("/Basic.lean") }
        repository.save(projectId, library, "namespace M2Instrumentation\ndef answer : Nat := missing\nend M2Instrumentation\n")
        val commands = ToolchainCommandFactory(layout, context.filesDir, context.cacheDir)
        val runner = JvmCommandRunner()
        val broken = runBlocking { runner.run(repository.lakeBuild(commands, projectId)) }
        assertTrue("Expected broken project to fail: $broken", broken.exitCode != 0)

        repository.save(projectId, library, "namespace M2Instrumentation\ndef answer : Nat := 42\nend M2Instrumentation\n")
        val fixed = runBlocking { runner.run(repository.lakeBuild(commands, projectId)) }
        assertEquals("Fixed project did not build: $fixed", 0, fixed.exitCode)
        repository.export(projectId, archive)
        repository.delete(projectId)
        assertFalse(projectRoot.resolve(projectId).exists())
        repository.import(projectId, archive)
        val imported = runBlocking { runner.run(repository.lakeBuild(commands, projectId)) }
        assertEquals("Reimported project did not build: $imported", 0, imported.exitCode)
    }
}
