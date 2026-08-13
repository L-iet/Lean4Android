package org.lean4android.toolchain

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ToolchainRuntimeManifestTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `verifier detects missing size and same-size hash corruption`() {
        val root = temporaryFolder.newFolder()
        val good = root.resolve("lib/A.olean").apply { parentFile!!.mkdirs(); writeText("good") }
        val missingHash = "0".repeat(64)
        val manifest = ToolchainRuntimeManifest.parse(
            "schema\t1\ntoolchain\ttest\n" +
                "file\tlib/A.olean\t4\t${hash(good.readBytes())}\n" +
                "file\tlib/missing.olean\t1\t$missingHash\n",
        )
        assertTrue(ToolchainRuntimeVerifier().problems(root, manifest).any { "missing" in it })

        good.writeText("evil")
        assertTrue(ToolchainRuntimeVerifier().problems(root, manifest).any { "hash mismatch" in it })
        good.writeText("longer")
        assertTrue(ToolchainRuntimeVerifier().problems(root, manifest).any { "wrong size" in it })
    }

    @Test
    fun `parser rejects traversal duplicates and malformed hashes`() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolchainRuntimeManifest.parse("schema\t1\ntoolchain\tt\nfile\t../x\t1\t${"0".repeat(64)}\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolchainRuntimeManifest.parse("schema\t1\ntoolchain\tt\nfile\tx\t1\tbad\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolchainRuntimeManifest.parse(
                "schema\t1\ntoolchain\tt\nfile\tx\t1\t${"0".repeat(64)}\nfile\tx\t1\t${"0".repeat(64)}\n",
            )
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
