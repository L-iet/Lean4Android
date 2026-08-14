package org.lean4android.app

import org.lean4android.project.LeanProjectRepository
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class EditorTab(
    val path: String,
    val savedContents: String,
    val contents: String,
) {
    val dirty: Boolean get() = contents != savedContents
}

internal data class EditorSessionState(
    val projectId: String,
    val tabs: List<EditorTab>,
    val activePath: String?,
) {
    init {
        require(tabs.map(EditorTab::path).distinct().size == tabs.size) { "Editor tab paths must be unique" }
        require((tabs.isEmpty() && activePath == null) || tabs.any { it.path == activePath }) {
            "The active editor path must be open"
        }
    }

    fun edit(path: String, contents: String): EditorSessionState = copy(
        tabs = tabs.map { if (it.path == path) it.copy(contents = contents) else it },
    )

    fun select(path: String): EditorSessionState {
        require(tabs.any { it.path == path }) { "Editor tab is not open: $path" }
        return copy(activePath = path)
    }

    fun markSaved(path: String? = null): EditorSessionState = copy(
        tabs = tabs.map { if (path == null || it.path == path) it.copy(savedContents = it.contents) else it },
    )

    fun add(path: String, contents: String = ""): EditorSessionState {
        require(tabs.none { it.path == path }) { "Editor tab already exists: $path" }
        return copy(tabs = tabs + EditorTab(path, contents, contents), activePath = path)
    }

    fun rename(oldPath: String, newPath: String): EditorSessionState {
        require(tabs.any { it.path == oldPath }) { "Editor tab is not open: $oldPath" }
        require(tabs.none { it.path == newPath }) { "Editor tab already exists: $newPath" }
        return copy(
            tabs = tabs.map { if (it.path == oldPath) it.copy(path = newPath) else it },
            activePath = if (activePath == oldPath) newPath else activePath,
        )
    }

    fun saveAs(oldPath: String, newPath: String): EditorSessionState {
        val source = tabs.singleOrNull { it.path == oldPath } ?: error("Editor tab is not open: $oldPath")
        require(tabs.none { it.path == newPath }) { "Editor tab already exists: $newPath" }
        return copy(
            tabs = tabs + source.copy(path = newPath, savedContents = source.contents),
            activePath = newPath,
        )
    }

    fun remove(path: String): EditorSessionState {
        val index = tabs.indexOfFirst { it.path == path }
        require(index >= 0) { "Editor tab is not open: $path" }
        val remaining = tabs.filterNot { it.path == path }
        return copy(
            tabs = remaining,
            activePath = if (remaining.isEmpty()) null else if (activePath == path) {
                remaining[minOf(index, remaining.lastIndex)].path
            } else activePath,
        )
    }
}

/** Small, atomic recovery snapshot. Project source remains authoritative after a successful save. */
internal class EditorSessionStore(private val snapshot: File) {
    companion object {
        private const val MAGIC = 0x4c344145 // L4AE
        private const val SCHEMA = 2
        private const val MAX_TABS = 256
        private const val MAX_TEXT_BYTES = 8 * 1024 * 1024
    }

    fun loadOrCreate(repository: LeanProjectRepository, projectId: String): EditorSessionState {
        val project = repository.open(projectId)
        val recovered = runCatching { readSnapshot() }.getOrNull()
        if (recovered != null && recovered.projectId == projectId &&
            recovered.tabs.map(EditorTab::path).all { it in project.sourceFiles }
        ) return recovered

        return EditorSessionState(
            projectId = projectId,
            tabs = project.sourceFiles.map { path ->
                repository.read(projectId, path).let { EditorTab(path, it, it) }
            },
            activePath = project.sourceFiles.first(),
        )
    }

    fun save(state: EditorSessionState) {
        snapshot.parentFile?.mkdirs()
        val temporary = snapshot.resolveSibling("${snapshot.name}.saving")
        DataOutputStream(BufferedOutputStream(temporary.outputStream())).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(SCHEMA)
            output.writeUTF(state.projectId)
            output.writeBoolean(state.activePath != null)
            state.activePath?.let(output::writeUTF)
            output.writeInt(state.tabs.size)
            state.tabs.forEach { tab ->
                output.writeUTF(tab.path)
                writeText(output, tab.savedContents)
                writeText(output, tab.contents)
            }
        }
        Files.move(
            temporary.toPath(), snapshot.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
        )
    }

    fun clear() {
        Files.deleteIfExists(snapshot.toPath())
        Files.deleteIfExists(snapshot.resolveSibling("${snapshot.name}.saving").toPath())
    }

    private fun readSnapshot(): EditorSessionState? {
        if (!snapshot.isFile) return null
        return DataInputStream(BufferedInputStream(snapshot.inputStream())).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == SCHEMA) { "Unsupported editor recovery snapshot" }
            val projectId = input.readUTF()
            val activePath = if (input.readBoolean()) input.readUTF() else null
            val count = input.readInt()
            require(count in 0..MAX_TABS) { "Invalid editor tab count" }
            val tabs = List(count) {
                EditorTab(input.readUTF(), readText(input), readText(input))
            }
            require(input.read() == -1) { "Trailing editor recovery data" }
            EditorSessionState(projectId, tabs, activePath)
        }
    }

    private fun writeText(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "Editor buffer exceeds recovery limit" }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readText(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 0..MAX_TEXT_BYTES) { "Invalid editor buffer size" }
        return input.readNBytes(size).also { require(it.size == size) { "Truncated editor recovery buffer" } }
            .toString(Charsets.UTF_8)
    }
}
