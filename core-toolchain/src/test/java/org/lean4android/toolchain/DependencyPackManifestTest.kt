package org.lean4android.toolchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.io.ByteArrayInputStream

class DependencyPackManifestTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `parser binds identity capabilities licenses files and totals`() {
        val manifest = parse(file("LICENSE", "license", "LICENSE"), file("lib/A.olean", "artifact"))
        assertEquals("mathlib-4.32.1-lean-4.32.1-android1", manifest.packId)
        assertEquals("lean-4.32.1-android1", manifest.requiredToolchainId)
        assertEquals(setOf("lake-lean", "lake-serve"), manifest.capabilities)
        assertEquals(15, manifest.expandedBytes)
    }

    @Test
    fun `parser rejects traversal case collisions bad totals and unbound licenses`() {
        assertThrows(IllegalArgumentException::class.java) { parse(file("../A.olean", "x")) }
        assertThrows(IllegalArgumentException::class.java) {
            parse(file("LICENSE", "license", "LICENSE"), file("Lib/A.olean", "a"), file("lib/a.olean", "b"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DependencyPackManifest.parse(base() + "license\tmissing\tApache-2.0\ntotal\t1\t1\n" + fileLine("x", "x"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DependencyPackManifest.parse(base() + "license\tLICENSE\tApache-2.0\ntotal\t2\t99\n" + fileLine("LICENSE", "x", "LICENSE"))
        }
    }

    @Test
    fun `verifier rejects undeclared corrupt symlink and executable content`() {
        val root = temporaryFolder.newFolder()
        root.resolve("LICENSE").writeText("license")
        root.resolve("lib/A.olean").apply { parentFile!!.mkdirs(); writeText("artifact") }
        val manifest = parse(file("LICENSE", "license", "LICENSE"), file("lib/A.olean", "artifact"))
        assertTrue(DependencyPackVerifier().problems(root, manifest).isEmpty())

        root.resolve("extra").writeText("x")
        assertTrue(DependencyPackVerifier().problems(root, manifest).any { "undeclared" in it })
        root.resolve("extra").delete()
        root.resolve("lib/A.olean").writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0, 0, 0, 0))
        assertTrue(DependencyPackVerifier().problems(root, manifest).any { "executable" in it })
        root.resolve("lib/A.olean").delete()
        runCatching { java.nio.file.Files.createSymbolicLink(root.resolve("lib/A.olean").toPath(), root.resolve("LICENSE").toPath()) }
        if (java.nio.file.Files.isSymbolicLink(root.resolve("lib/A.olean").toPath())) {
            assertTrue(DependencyPackVerifier().problems(root, manifest).any { "not a regular file" in it })
        }
    }

    @Test
    fun `installer streams declared bytes into a fresh staging directory`() {
        val manifest = parse(file("LICENSE", "license", "LICENSE"), file("lib/A.olean", "artifact"))
        val destination = temporaryFolder.root.resolve("staging")
        val source = source(mapOf("LICENSE" to "license", "lib/A.olean" to "artifact"))

        DependencyPackPayloadInstaller().install(source, destination, manifest, "lean-4.32.1-android1")

        assertEquals("artifact", destination.resolve("lib/A.olean").readText())
        assertTrue(DependencyPackVerifier().problems(destination, manifest).isEmpty())
    }

    @Test
    fun `installer rejects identity existing destination truncation corruption and executable content`() {
        val manifest = parse(file("LICENSE", "license", "LICENSE"), file("lib/A.olean", "artifact"))
        assertThrows(IllegalArgumentException::class.java) {
            DependencyPackPayloadInstaller().install(
                source(mapOf("LICENSE" to "license", "lib/A.olean" to "artifact")),
                temporaryFolder.root.resolve("wrong-toolchain"),
                manifest,
                "lean-4.32.1-android2",
            )
        }

        val existing = temporaryFolder.newFolder("existing")
        assertThrows(IllegalArgumentException::class.java) {
            DependencyPackPayloadInstaller().install(source(emptyMap()), existing, manifest, manifest.requiredToolchainId)
        }
        assertInstallFails(manifest, "short", "truncated")
        assertInstallFails(manifest, "corrupt!", "corrupt")

        val executable = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0, 0, 0, 0)
        val executableManifest = parse(file("LICENSE", "license", "LICENSE"), Triple("lib/A.olean", executable.decodeToString(), "LEAN_ARTIFACT"))
        val failure = runCatching {
            DependencyPackPayloadInstaller().install(
                object : DependencyPackPayloadSource {
                    override fun open(relativePath: String) = ByteArrayInputStream(
                        if (relativePath == "LICENSE") "license".toByteArray() else executable,
                    )
                },
                temporaryFolder.root.resolve("executable"),
                executableManifest,
                executableManifest.requiredToolchainId,
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException && "executable" in failure.message.orEmpty())
    }

    private fun assertInstallFails(manifest: DependencyPackManifest, artifact: String, name: String) {
        val failure = runCatching {
            DependencyPackPayloadInstaller().install(
                source(mapOf("LICENSE" to "license", "lib/A.olean" to artifact)),
                temporaryFolder.root.resolve(name),
                manifest,
                manifest.requiredToolchainId,
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    private fun source(files: Map<String, String>) = object : DependencyPackPayloadSource {
        override fun open(relativePath: String) = ByteArrayInputStream(files.getValue(relativePath).toByteArray())
    }

    private fun parse(vararg entries: Triple<String, String, String>): DependencyPackManifest {
        val lines = entries.joinToString("") { fileLine(it.first, it.second, it.third) }
        val total = entries.sumOf { it.second.toByteArray().size }
        val licenses = entries.filter { it.third == "LICENSE" }.joinToString("") { "license\t${it.first}\tApache-2.0\n" }
        return DependencyPackManifest.parse(base() + licenses + "total\t${entries.size}\t$total\n" + lines)
    }

    private fun file(path: String, contents: String, kind: String = "LEAN_ARTIFACT") = Triple(path, contents, kind)

    private fun fileLine(path: String, contents: String, kind: String = "LEAN_ARTIFACT") =
        "file\t$path\t${contents.toByteArray().size}\t${hash(contents.toByteArray())}\t$kind\n"

    private fun base() =
        "schema\t1\n" +
            "pack\tmathlib-4.32.1-lean-4.32.1-android1\n" +
            "toolchain\tlean-4.32.1-android1\n" +
            "mathlib\t${"a".repeat(64)}\n" +
            "capability\tlake-lean\n" +
            "capability\tlake-serve\n"

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
