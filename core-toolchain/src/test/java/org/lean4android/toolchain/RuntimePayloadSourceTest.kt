package org.lean4android.toolchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RuntimePayloadSourceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun signedZipStreamsAndVerifiesPayload() {
        val bytes = "hello runtime".toByteArray()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val manifestText = "schema\t1\ntoolchain\ttest\nfile\tlib/Init.olean\t${bytes.size}\t$hash\n"
        val archive = temporary.root.resolve("runtime.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            listOf("toolchain-manifest.tsv" to manifestText.toByteArray(), "lib/Init.olean" to bytes).forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val signer = Signature.getInstance("SHA256withRSA").apply { initSign(keys.private) }
        signer.update(archive.readBytes())

        SignedZipRuntimePayloadSource(archive, signer.sign(), keys.public).use { source ->
            val manifest = ToolchainRuntimeManifest.parse(source.manifestText())
            val output = temporary.newFolder("installed")
            RuntimePayloadInstaller().install(source, output, manifest)
            assertEquals("hello runtime", output.resolve("lib/Init.olean").readText())
        }
    }

    @Test fun rejectsModifiedSignedArchive() {
        val archive = temporary.newFile("runtime.zip").apply { writeText("pack") }
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val signer = Signature.getInstance("SHA256withRSA").apply { initSign(keys.private) }
        signer.update(archive.readBytes())
        val signature = signer.sign()
        archive.appendText("modified")
        val failure = runCatching { SignedZipRuntimePayloadSource(archive, signature, keys.public) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("signature is invalid"))
    }

    @Test fun fullExternalPackStreamsWhenRequested() {
        val archivePath = System.getenv("LEAN4ANDROID_FULL_PACK") ?: return
        val signaturePath = System.getenv("LEAN4ANDROID_FULL_PACK_SIGNATURE") ?: return
        val publicKeyPath = System.getenv("LEAN4ANDROID_FULL_PACK_PUBLIC_KEY_DER") ?: return
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(java.io.File(publicKeyPath).readBytes()),
        )
        SignedZipRuntimePayloadSource(
            java.io.File(archivePath),
            java.io.File(signaturePath).readBytes(),
            publicKey,
        ).use { source ->
            val manifest = ToolchainRuntimeManifest.parse(source.manifestText())
            val output = temporary.newFolder("full-installed")
            RuntimePayloadInstaller().install(source, output, manifest)
            assertEquals(manifest.files.size, output.walkTopDown().count { it.isFile })
            assertTrue(ToolchainRuntimeVerifier().problems(output, manifest).isEmpty())
        }
    }
}
