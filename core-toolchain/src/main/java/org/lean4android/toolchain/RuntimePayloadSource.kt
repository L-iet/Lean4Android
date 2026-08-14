package org.lean4android.toolchain

import android.content.res.AssetManager
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.util.zip.ZipFile

/** Delivery-neutral source for the immutable, data-only sysroot payload. */
interface RuntimePayloadSource : AutoCloseable {
    fun manifestText(): String
    fun open(relativePath: String): InputStream
    override fun close() = Unit
}

class ApkAssetRuntimePayloadSource(private val assets: AssetManager) : RuntimePayloadSource {
    override fun manifestText(): String = assets.open("toolchain-manifest.tsv").bufferedReader().use { it.readText() }
    override fun open(relativePath: String): InputStream = assets.open("toolchain/$relativePath")
}

/** Independent-distribution prototype: a detached RSA signature authenticates the complete ZIP bytes. */
class SignedZipRuntimePayloadSource(
    archive: File,
    signatureBytes: ByteArray,
    publicKey: PublicKey,
) : RuntimePayloadSource {
    private val zip: ZipFile

    init {
        require(archive.isFile) { "Runtime data pack is missing" }
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        archive.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                verifier.update(buffer, 0, count)
            }
        }
        require(verifier.verify(signatureBytes)) { "Runtime data-pack signature is invalid" }
        zip = ZipFile(archive)
    }

    override fun manifestText(): String = open("toolchain-manifest.tsv").bufferedReader().use { it.readText() }

    override fun open(relativePath: String): InputStream {
        require(relativePath.isNotBlank() && !relativePath.startsWith('/') && relativePath.split('/').none { it == ".." }) {
            "Unsafe runtime payload path"
        }
        val entry = zip.getEntry(relativePath)
            ?: zip.getEntry("toolchain/$relativePath")
            ?: error("Runtime payload entry is missing: $relativePath")
        require(!entry.isDirectory) { "Runtime payload entry is not a file: $relativePath" }
        return zip.getInputStream(entry)
    }

    override fun close() = zip.close()
}

class RuntimePayloadInstaller {
    fun install(source: RuntimePayloadSource, destination: File, manifest: ToolchainRuntimeManifest) {
        manifest.files.forEach { entry ->
            val output = destination.resolve(entry.path)
            output.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            source.open(entry.path).buffered().use { input ->
                FileOutputStream(output).buffered().use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= entry.size) { "Runtime payload entry is larger than declared: ${entry.path}" }
                        digest.update(buffer, 0, count)
                        sink.write(buffer, 0, count)
                    }
                }
            }
            require(size == entry.size) { "Runtime payload entry has wrong size: ${entry.path}" }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash == entry.sha256) { "Runtime payload entry hash mismatch: ${entry.path}" }
        }
    }
}
