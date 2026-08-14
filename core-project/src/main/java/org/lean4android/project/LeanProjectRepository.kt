package org.lean4android.project

import org.lean4android.process.ProcessCommand
import org.lean4android.toolchain.ToolchainCommandFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class LeanProject(
    val id: String,
    val directory: File,
    val sourceFiles: List<String>,
)

class UnsupportedProjectException(message: String) : IllegalArgumentException(message)

/** Owns app-private project data. All externally supplied names and archive paths are contained here. */
class LeanProjectRepository(
    private val root: File,
    private val toolchainId: String,
    private val leanToolchainSpec: String = "leanprover/lean4:v4.32.1",
) {
    companion object {
        private const val METADATA = ".lean4android-project"
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val UNSUPPORTED_LAKE = Regex(
            "(?im)\\b(lean_exe|extern_lib|require\\s+.+\\s+from\\s+(git|\"https?://)|git|curl|wget)\\b",
        )
    }

    fun create(id: String): LeanProject {
        validateId(id)
        val destination = root.resolve(id)
        require(!destination.exists()) { "Project already exists: $id" }
        val staging = root.resolve(".$id.creating")
        deleteTree(staging)
        try {
            staging.mkdirs()
            atomicWrite(staging.resolve(METADATA), "schema=1\ntoolchain=$toolchainId\n")
            atomicWrite(staging.resolve("lean-toolchain"), "$leanToolchainSpec\n")
            atomicWrite(
                staging.resolve("lakefile.toml"),
                "name = \"$id\"\nversion = \"0.1.0\"\ndefaultTargets = [\"${leanName(id)}\"]\n\n[[lean_lib]]\nname = \"${leanName(id)}\"\nroots = [\"Main\", \"${leanName(id)}.Basic\"]\n",
            )
            val module = leanName(id)
            atomicWrite(staging.resolve("$module/Basic.lean"), defaultLibrary(module))
            atomicWrite(staging.resolve("Main.lean"), defaultMain(module))
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: Throwable) {
            deleteTree(staging)
            throw failure
        }
        return open(id)
    }

    fun list(): List<LeanProject> = root.listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.startsWith('.') }
        .mapNotNull { runCatching { open(it.name) }.getOrNull() }
        .sortedBy(LeanProject::id)

    fun open(id: String): LeanProject {
        validateId(id)
        val directory = root.resolve(id)
        require(directory.isDirectory) { "Project does not exist: $id" }
        validateMetadata(directory)
        validateSupportedWorkflow(directory)
        val sources = directory.walkTopDown()
            .filter { it.isFile && it.extension == "lean" }
            .map { it.relativeTo(directory).invariantSeparatorsPath }
            .sorted()
            .toList()
        require(sources.isNotEmpty()) { "Project has no Lean source files" }
        return LeanProject(id, directory, sources)
    }

    fun save(projectId: String, relativePath: String, contents: String) {
        val project = open(projectId)
        val destination = resolveContained(project.directory, relativePath)
        require(destination.extension == "lean") { "Only Lean source files can be edited" }
        atomicWrite(destination, contents)
    }

    fun read(projectId: String, relativePath: String): String {
        val project = open(projectId)
        val source = resolveContained(project.directory, relativePath)
        require(source.isFile && source.extension == "lean") { "Lean source does not exist: $relativePath" }
        return source.readText()
    }

    fun createSource(projectId: String, relativePath: String, contents: String = "") : LeanProject {
        val project = open(projectId)
        val destination = resolveContained(project.directory, relativePath)
        require(destination.extension == "lean") { "Only Lean source files can be created" }
        require(!destination.exists()) { "Lean source already exists: $relativePath" }
        atomicWrite(destination, contents)
        return open(projectId)
    }

    fun renameSource(projectId: String, oldPath: String, newPath: String): LeanProject {
        val project = open(projectId)
        val source = resolveContained(project.directory, oldPath)
        val destination = resolveContained(project.directory, newPath)
        require(source.isFile && source.extension == "lean") { "Lean source does not exist: $oldPath" }
        require(destination.extension == "lean") { "Only Lean source files can be renamed" }
        require(!destination.exists()) { "Lean source already exists: $newPath" }
        destination.parentFile?.mkdirs()
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        removeEmptyParents(source.parentFile, project.directory)
        return open(projectId)
    }

    fun deleteSource(projectId: String, relativePath: String): LeanProject {
        val project = open(projectId)
        require(project.sourceFiles.size > 1) { "A project must keep at least one Lean source file" }
        val source = resolveContained(project.directory, relativePath)
        require(source.isFile && source.extension == "lean") { "Lean source does not exist: $relativePath" }
        Files.delete(source.toPath())
        removeEmptyParents(source.parentFile, project.directory)
        return open(projectId)
    }

    fun delete(id: String) {
        val project = open(id)
        deleteTree(project.directory)
        check(!project.directory.exists()) { "Could not delete project: $id" }
    }

    fun export(id: String, destination: File) {
        val project = open(id)
        destination.parentFile?.mkdirs()
        val temporary = destination.resolveSibling("${destination.name}.saving")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { zip ->
            project.directory.walkTopDown().filter(File::isFile).forEach { file ->
                val path = file.relativeTo(project.directory).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(path).apply { time = 0L })
                FileInputStream(file).buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun import(id: String, archive: File): LeanProject {
        validateId(id)
        require(archive.isFile) { "Project archive does not exist" }
        val destination = root.resolve(id)
        require(!destination.exists()) { "Project already exists: $id" }
        val staging = root.resolve(".$id.importing")
        deleteTree(staging)
        staging.mkdirs()
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
                var count = 0
                var total = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= 10_000) { "Project archive has too many entries" }
                    val output = resolveContained(staging, entry.name)
                    if (entry.isDirectory) output.mkdirs() else {
                        output.parentFile?.mkdirs()
                        FileOutputStream(output).buffered().use { sink ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                total += read
                                require(total <= 256L * 1024 * 1024) { "Project archive expands beyond 256 MiB" }
                                sink.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            validateMetadata(staging)
            validateSupportedWorkflow(staging)
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: Throwable) {
            deleteTree(staging)
            throw failure
        }
        return open(id)
    }

    fun lakeBuild(factory: ToolchainCommandFactory, id: String, timeout: Duration = 5.minutes): ProcessCommand {
        val project = open(id)
        return factory.lake(listOf("build"), project.directory, timeout)
    }

    fun lakeLean(factory: ToolchainCommandFactory, id: String, source: String, timeout: Duration = 2.minutes): ProcessCommand {
        val project = open(id)
        val file = resolveContained(project.directory, source)
        require(file.isFile && file.extension == "lean") { "Lean source does not exist: $source" }
        return factory.lake(listOf("lean", source), project.directory, timeout)
    }

    private fun validateMetadata(directory: File) {
        val values = directory.resolve(METADATA).takeIf(File::isFile)?.readLines()
            ?.associate { line -> line.substringBefore('=') to line.substringAfter('=', "") }
            ?: error("Project metadata is missing")
        require(values["schema"] == "1") { "Unsupported project schema" }
        require(values["toolchain"] == toolchainId) {
            "Project requires ${values["toolchain"] ?: "an unknown toolchain"}; installed toolchain is $toolchainId"
        }
    }

    private fun validateSupportedWorkflow(directory: File) {
        val lakefile = directory.resolve("lakefile.toml")
        require(lakefile.isFile) { "lakefile.toml is missing" }
        if (UNSUPPORTED_LAKE.containsMatchIn(lakefile.readText())) {
            throw UnsupportedProjectException("Git, network dependencies, executables, and native targets are not supported offline")
        }
    }

    private fun validateId(id: String) {
        require(SAFE_ID.matches(id)) { "Project ID must be 1-64 safe filename characters" }
    }

    private fun resolveContained(base: File, relativePath: String): File {
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) { "Project path must be relative" }
        val result = base.resolve(relativePath).canonicalFile
        val canonicalBase = base.canonicalFile
        require(result.toPath().startsWith(canonicalBase.toPath()) && result != canonicalBase) { "Project path escapes its root" }
        return result
    }

    private fun removeEmptyParents(start: File?, projectRoot: File) {
        val root = projectRoot.canonicalFile
        var directory = start?.canonicalFile
        while (directory != null && directory != root && directory.toPath().startsWith(root.toPath())) {
            if (directory.list().orEmpty().isNotEmpty()) return
            Files.deleteIfExists(directory.toPath())
            directory = directory.parentFile
        }
    }

    private fun atomicWrite(destination: File, contents: String) {
        destination.parentFile?.mkdirs()
        val previousModified = destination.takeIf(File::exists)?.lastModified() ?: 0L
        val temporary = destination.resolveSibling("${destination.name}.saving")
        temporary.writeText(contents)
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        // FAT-like/coarse Android timestamps can otherwise make a rapid error/fix cycle invisible to Lake.
        destination.setLastModified(maxOf(System.currentTimeMillis(), previousModified + 1_000L))
    }

    private fun deleteTree(root: File) {
        if (!Files.exists(root.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: java.nio.file.Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    private fun leanName(id: String) = id.split(Regex("[^A-Za-z0-9]+"))
        .filter(String::isNotEmpty).joinToString("") { it.replaceFirstChar(Char::uppercase) }
        .ifEmpty { "Project" }

    private fun defaultMain(module: String) = """import $module.Basic

#check $module.answer
example : $module.answer = 42 := by rfl
"""

    private fun defaultLibrary(module: String) = """namespace $module

def answer : Nat := 42

theorem answer_is_positive : 0 < answer := by decide

end $module
"""
}
