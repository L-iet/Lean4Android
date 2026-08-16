package org.lean4android.toolchain

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale

data class DependencyPackManifest(
    val schema: Int,
    val packId: String,
    val requiredToolchainId: String,
    val mathlibRevision: String,
    val capabilities: Set<String>,
    val licenses: List<License>,
    val files: List<Entry>,
    val expandedBytes: Long,
) {
    data class License(val path: String, val spdxId: String)
    data class Entry(val path: String, val size: Long, val sha256: String, val kind: Kind)
    enum class Kind { SOURCE, LEAN_ARTIFACT, METADATA, LICENSE }

    companion object {
        private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val hashPattern = Regex("[0-9a-f]{64}")

        fun parse(contents: String): DependencyPackManifest {
            var schema: Int? = null
            var packId: String? = null
            var toolchainId: String? = null
            var mathlibRevision: String? = null
            var declaredCount: Int? = null
            var declaredBytes: Long? = null
            val capabilities = linkedSetOf<String>()
            val licenses = mutableListOf<License>()
            val files = mutableListOf<Entry>()
            contents.lineSequence().filter(String::isNotBlank).forEach { line ->
                val fields = line.split('\t')
                when (fields.firstOrNull()) {
                    "schema" -> {
                        require(fields.size == 2 && schema == null) { "Invalid dependency-pack schema line" }
                        schema = fields[1].toIntOrNull()
                    }
                    "pack" -> {
                        require(fields.size == 2 && packId == null && fields[1].matches(idPattern)) { "Invalid pack ID" }
                        packId = fields[1]
                    }
                    "toolchain" -> {
                        require(fields.size == 2 && toolchainId == null && fields[1].matches(idPattern)) {
                            "Invalid required toolchain ID"
                        }
                        toolchainId = fields[1]
                    }
                    "mathlib" -> {
                        require(fields.size == 2 && mathlibRevision == null && fields[1].matches(hashPattern)) {
                            "Invalid Mathlib revision"
                        }
                        mathlibRevision = fields[1]
                    }
                    "capability" -> {
                        require(fields.size == 2 && fields[1].matches(idPattern) && capabilities.add(fields[1])) {
                            "Invalid or duplicate capability"
                        }
                    }
                    "license" -> {
                        require(fields.size == 3 && fields[2].matches(idPattern)) { "Invalid license record" }
                        licenses += License(safePath(fields[1]), fields[2])
                    }
                    "total" -> {
                        require(fields.size == 3 && declaredCount == null && declaredBytes == null) {
                            "Invalid dependency-pack total"
                        }
                        declaredCount = fields[1].toIntOrNull()?.takeIf { it > 0 }
                        declaredBytes = fields[2].toLongOrNull()?.takeIf { it >= 0 }
                    }
                    "file" -> {
                        require(fields.size == 5) { "Invalid dependency-pack file line" }
                        val path = safePath(fields[1])
                        val size = fields[2].toLongOrNull()?.takeIf { it >= 0 } ?: error("Invalid size for $path")
                        val hash = fields[3].lowercase(Locale.ROOT)
                        require(hash.matches(hashPattern)) { "Invalid SHA-256 for $path" }
                        val kind = runCatching { Kind.valueOf(fields[4]) }.getOrElse { error("Invalid kind for $path") }
                        files += Entry(path, size, hash, kind)
                    }
                    else -> error("Unknown dependency-pack manifest record")
                }
            }
            require(schema == 1 && packId != null && toolchainId != null && mathlibRevision != null) {
                "Incomplete or unsupported dependency-pack manifest"
            }
            require(files.isNotEmpty() && capabilities.isNotEmpty() && licenses.isNotEmpty()) {
                "Dependency-pack manifest has no files, capabilities, or licenses"
            }
            val foldedPaths = files.map { it.path.lowercase(Locale.ROOT) }
            require(foldedPaths.toSet().size == files.size) { "Duplicate or case-folded dependency-pack path" }
            val licensePaths = licenses.map(License::path)
            require(licensePaths.toSet().size == licenses.size && licensePaths.all { it in files.map(Entry::path) }) {
                "License records must name distinct declared files"
            }
            require(licensePaths.all { path -> files.single { it.path == path }.kind == Kind.LICENSE }) {
                "License records must reference LICENSE entries"
            }
            val actualBytes = files.sumOf(Entry::size)
            require(declaredCount == files.size && declaredBytes == actualBytes) { "Dependency-pack totals do not match files" }
            return DependencyPackManifest(
                schema = schema,
                packId = packId,
                requiredToolchainId = toolchainId,
                mathlibRevision = mathlibRevision,
                capabilities = capabilities,
                licenses = licenses,
                files = files,
                expandedBytes = actualBytes,
            )
        }

        private fun safePath(value: String): String {
            require(
                value.isNotEmpty() && !value.startsWith('/') && '\\' !in value && '\u0000' !in value &&
                    value.split('/').none { it.isEmpty() || it == "." || it == ".." },
            ) { "Unsafe dependency-pack path: $value" }
            return value
        }
    }
}

class DependencyPackVerifier {
    fun problems(root: File, manifest: DependencyPackManifest): List<String> = buildList {
        val declared = manifest.files.associateBy { it.path }
        val observed = linkedSetOf<String>()
        if (!root.isDirectory) {
            add("Dependency-pack root is missing")
            return@buildList
        }
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = root.toPath().relativize(file).toString().replace(File.separatorChar, '/')
                if (attrs.isSymbolicLink || !attrs.isRegularFile) {
                    add("Dependency-pack path is not a regular file: $relative")
                } else if (relative !in declared) {
                    add("Dependency-pack file is undeclared: $relative")
                }
                observed += relative
                return FileVisitResult.CONTINUE
            }
        })
        manifest.files.forEach { entry ->
            val file = root.resolve(entry.path)
            when {
                entry.path !in observed || !file.isFile -> add("Dependency-pack file is missing: ${entry.path}")
                file.length() != entry.size -> add("Dependency-pack file has wrong size: ${entry.path}")
                hasExecutableMagic(file) -> add("Dependency-pack file contains executable content: ${entry.path}")
                sha256(file) != entry.sha256 -> add("Dependency-pack file hash mismatch: ${entry.path}")
            }
        }
    }

    private fun hasExecutableMagic(file: File): Boolean {
        val header = ByteArray(4)
        val count = file.inputStream().use { it.read(header) }
        if (count < 2) return false
        val elf = count == 4 && header.contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        val pe = header[0] == 'M'.code.toByte() && header[1] == 'Z'.code.toByte()
        val magic = if (count == 4) header.fold(0u) { value, byte -> (value shl 8) or byte.toUByte().toUInt() } else 0u
        val machO = magic in setOf(0xfeedfaceu, 0xfeedfacfu, 0xcefaedfeu, 0xcffaedfeu, 0xcafebabeu, 0xbebafecau)
        return elf || pe || machO
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input -> update(digest, input) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun update(digest: MessageDigest, input: InputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
}

/** Delivery-neutral source for immutable dependency-pack data. */
interface DependencyPackPayloadSource : AutoCloseable {
    fun open(relativePath: String): InputStream
    override fun close() = Unit
}

/**
 * Streams a verified manifest into a new staging directory. Activation and rollback are deliberately
 * owned by the higher-level lifecycle; a failed staging directory is never treated as installed.
 */
class DependencyPackPayloadInstaller {
    fun install(
        source: DependencyPackPayloadSource,
        destination: File,
        manifest: DependencyPackManifest,
        expectedToolchainId: String,
    ) {
        require(manifest.requiredToolchainId == expectedToolchainId) { "Dependency pack requires a different toolchain" }
        require(!destination.exists()) { "Dependency-pack staging destination already exists" }
        require(destination.mkdirs()) { "Cannot create dependency-pack staging destination" }

        manifest.files.forEach { entry ->
            val output = destination.resolve(entry.path)
            val parent = output.parentFile ?: error("Dependency-pack entry has no parent")
            createSafeDirectories(destination, parent)
            require(!output.exists()) { "Dependency-pack staging path already exists: ${entry.path}" }
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            source.open(entry.path).buffered().use { input ->
                FileOutputStream(output).buffered().use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= entry.size) { "Dependency-pack entry is larger than declared: ${entry.path}" }
                        digest.update(buffer, 0, count)
                        sink.write(buffer, 0, count)
                    }
                }
            }
            require(size == entry.size) { "Dependency-pack entry has wrong size: ${entry.path}" }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash == entry.sha256) { "Dependency-pack entry hash mismatch: ${entry.path}" }
        }

        val problems = DependencyPackVerifier().problems(destination, manifest)
        require(problems.isEmpty()) { problems.joinToString("; ") }
    }

    private fun createSafeDirectories(root: File, directory: File) {
        val relative = root.toPath().relativize(directory.toPath())
        var current = root.toPath()
        relative.forEach { segment ->
            current = current.resolve(segment)
            if (Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                require(Files.isDirectory(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    "Dependency-pack parent is not a directory"
                }
            } else {
                Files.createDirectory(current)
            }
        }
    }
}
