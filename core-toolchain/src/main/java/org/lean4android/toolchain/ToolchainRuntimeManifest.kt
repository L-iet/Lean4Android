package org.lean4android.toolchain

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class ToolchainRuntimeManifest(
    val schema: Int,
    val toolchainId: String,
    val files: List<Entry>,
) {
    data class Entry(val path: String, val size: Long, val sha256: String)

    companion object {
        fun parse(contents: String): ToolchainRuntimeManifest {
            var schema: Int? = null
            var toolchain: String? = null
            val files = mutableListOf<Entry>()
            contents.lineSequence().filter(String::isNotBlank).forEach { line ->
                val fields = line.split('\t')
                when (fields.firstOrNull()) {
                    "schema" -> {
                        require(fields.size == 2 && schema == null) { "Invalid manifest schema line" }
                        schema = fields[1].toIntOrNull() ?: error("Invalid manifest schema")
                    }
                    "toolchain" -> {
                        require(fields.size == 2 && toolchain == null) { "Invalid manifest toolchain line" }
                        toolchain = fields[1]
                    }
                    "file" -> {
                        require(fields.size == 4) { "Invalid manifest file line" }
                        val path = fields[1]
                        require(path.isNotEmpty() && !path.startsWith('/') && path.split('/').none { it == ".." }) {
                            "Unsafe manifest path: $path"
                        }
                        val size = fields[2].toLongOrNull()?.takeIf { it >= 0 } ?: error("Invalid size for $path")
                        val hash = fields[3].lowercase()
                        require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 for $path" }
                        files += Entry(path, size, hash)
                    }
                    else -> error("Unknown manifest record")
                }
            }
            require(schema != null && toolchain != null && files.isNotEmpty()) { "Incomplete runtime manifest" }
            require(files.map(Entry::path).toSet().size == files.size) { "Duplicate runtime manifest path" }
            return ToolchainRuntimeManifest(schema, toolchain, files)
        }
    }
}

class ToolchainRuntimeVerifier {
    fun problems(root: File, manifest: ToolchainRuntimeManifest): List<String> = buildList {
        manifest.files.forEach { entry ->
            val file = root.resolve(entry.path)
            when {
                !file.isFile -> add("Runtime file is missing: ${entry.path}")
                file.length() != entry.size -> add("Runtime file has wrong size: ${entry.path}")
                sha256(file) != entry.sha256 -> add("Runtime file hash mismatch: ${entry.path}")
            }
        }
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
