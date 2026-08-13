package org.lean4android.toolchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.lean4android.model.ToolchainId
import org.lean4android.model.ToolchainLayout

class ToolchainCommandFactoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `Lean command uses deterministic Android-safe environment`() {
        val fixture = fixture()
        val command = fixture.factory.lean(listOf("Main.lean"), fixture.project)

        assertEquals(fixture.layout.leanExecutable, command.executable)
        assertEquals(fixture.cache.path, command.environment["TMPDIR"])
        assertEquals(fixture.layout.sysroot.path, command.environment["LEAN_SYSROOT"])
        assertEquals(":${fixture.layout.timezoneFile.path}", command.environment["TZ"])
        assertEquals(fixture.layout.leanLibraryDirectory.path, command.environment["LEAN_PATH"])
        assertEquals(setOf("HOME", "TMPDIR", "LEAN_SYSROOT", "TZ", "LEAN_PATH", "LD_LIBRARY_PATH", "PATH"), command.environment.keys)
    }

    @Test
    fun `Lake command selects writable compatibility layout without forcing Lean path`() {
        val fixture = fixture()
        val command = fixture.factory.lake(listOf("build"), fixture.project)

        assertEquals(fixture.layout.lakeExecutable, command.executable)
        assertEquals(fixture.layout.sysroot.path, command.environment["LAKE_HOME"])
        assertEquals("true", command.environment["LAKE_OVERRIDE_LEAN"])
        assertFalse(command.environment.containsKey("LEAN_PATH"))
    }

    @Test
    fun `language server uses workspace-aware Lake serve command`() {
        val fixture = fixture()
        val command = fixture.factory.lakeServer(fixture.project)

        assertEquals(fixture.layout.lakeExecutable, command.executable)
        assertEquals(listOf("serve"), command.arguments)
        assertEquals(kotlin.time.Duration.INFINITE, command.timeout)
    }

    private fun fixture(): Fixture {
        val root = temporaryFolder.root
        val layout = ToolchainLayout(
            ToolchainId("test"),
            root.resolve("native/liblean_exe.so"),
            root.resolve("native/liblake_exe.so"),
            root.resolve("sysroot"),
        )
        val home = root.resolve("home")
        val cache = root.resolve("cache")
        val project = root.resolve("project")
        return Fixture(layout, cache, project, ToolchainCommandFactory(layout, home, cache))
    }

    private data class Fixture(
        val layout: ToolchainLayout,
        val cache: java.io.File,
        val project: java.io.File,
        val factory: ToolchainCommandFactory,
    )
}
