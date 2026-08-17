package org.lean4android.project

import org.lean4android.process.ProcessCommand
import org.lean4android.toolchain.ToolchainCommandFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
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
        private const val MAX_PROJECT_ENTRIES = 10_000
        private const val MAX_PROJECT_FILE_BYTES = 64L * 1024 * 1024
        private const val MAX_PROJECT_BYTES = 256L * 1024 * 1024
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val LEAN_MODULE_COMPONENT = Regex("[A-Za-z][A-Za-z0-9_]*")
        private val GENERATED_LEAN_LIB = Regex("(?ms)^\\[\\[lean_lib]]\\s*\\n(.*?)(?=^\\[|\\z)")
        private val GENERATED_LEAN_LIB_LINE = Regex("(name|roots|globs)\\s*=.*")
        private val UNSUPPORTED_LAKE = Regex(
            "(?im)\\b(lean_exe|extern_lib|require\\s+.+\\s+from\\s+(git|\"https?://)|git|curl|wget)\\b",
        )

        fun normalizeProjectId(name: String): String = name.trim().replace(Regex("\\s+"), "-")

        fun leanName(id: String): String = id.split(Regex("[^A-Za-z0-9]+"))
            .filter(String::isNotEmpty).joinToString("") { it.replaceFirstChar(Char::uppercase) }
            .ifEmpty { "Project" }

        fun defaultNewSourcePath(projectId: String): String = "${leanName(projectId)}/New.lean"
    }

    fun create(id: String): LeanProject {
        validateId(id)
        val destination = root.resolve(id)
        require(!destination.exists()) { "Project already exists: $id" }
        require(root.listFiles().orEmpty().none { it.isDirectory && it.name.equals(id, ignoreCase = true) }) {
            "Project already exists (case-insensitive): $id"
        }
        val staging = root.resolve(".$id.creating")
        deleteTree(staging)
        try {
            staging.mkdirs()
            atomicWrite(staging.resolve(METADATA), "schema=1\ntoolchain=$toolchainId\n")
            atomicWrite(staging.resolve("lean-toolchain"), "$leanToolchainSpec\n")
            atomicWrite(staging.resolve("lakefile.toml"), defaultLakefile(id))
            val module = leanName(id)
            atomicWrite(staging.resolve("$module/Basic.lean"), defaultLibrary(module))
            atomicWrite(staging.resolve("Main.lean"), defaultMain(module))
            reconcileLakeConfiguration(staging, id)
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

    fun renameProject(oldId: String, newId: String): LeanProject {
        validateId(newId)
        val project = open(oldId)
        require(oldId != newId) { "Enter a different project name" }
        require(root.listFiles().orEmpty().none { it != project.directory && it.name.equals(newId, ignoreCase = true) }) {
            "Project already exists (case-insensitive): $newId"
        }
        Files.move(project.directory.toPath(), root.resolve(newId).toPath(), StandardCopyOption.ATOMIC_MOVE)
        return try {
            reconcileLakeConfiguration(newId)
        } catch (failure: Throwable) {
            Files.move(root.resolve(newId).toPath(), project.directory.toPath(), StandardCopyOption.ATOMIC_MOVE)
            throw failure
        }
    }

    fun save(projectId: String, relativePath: String, contents: String) {
        val project = open(projectId)
        val destination = resolveContained(project.directory, relativePath)
        require(destination.extension == "lean") { "Only Lean source files can be edited" }
        validateLeanSourcePath(relativePath)
        requireReconciliableLakefile(project.directory)
        atomicWrite(destination, contents)
        reconcileLakeConfiguration(project.directory, projectId)
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
        validateLeanSourcePath(relativePath)
        requireReconciliableLakefile(project.directory)
        requireNoCaseFoldedCollision(project, relativePath)
        atomicWrite(destination, contents)
        return reconcileLakeConfiguration(projectId)
    }

    /** Save As: creates a new source with the supplied bytes and leaves the original untouched. */
    fun copySource(projectId: String, sourcePath: String, destinationPath: String, contents: String): LeanProject {
        val project = open(projectId)
        val source = resolveContained(project.directory, sourcePath)
        require(source.isFile && source.extension == "lean") { "Lean source does not exist: $sourcePath" }
        val destination = resolveContained(project.directory, destinationPath)
        require(destination.extension == "lean") { "Only Lean source files can be created" }
        validateLeanSourcePath(destinationPath)
        requireReconciliableLakefile(project.directory)
        requireNoCaseFoldedCollision(project, destinationPath)
        atomicWrite(destination, contents)
        return reconcileLakeConfiguration(projectId)
    }

    fun renameSource(projectId: String, oldPath: String, newPath: String): LeanProject {
        val project = open(projectId)
        require(resolveContained(project.directory, oldPath).isFile) { "Lean source does not exist: $oldPath" }
        require(resolveContained(project.directory, newPath).extension == "lean") { "Only Lean source files can be renamed" }
        return renameEntry(projectId, oldPath, newPath)
    }

    fun renameEntry(projectId: String, oldPath: String, newPath: String): LeanProject {
        val project = open(projectId)
        val source = resolveContained(project.directory, oldPath)
        val destination = resolveContained(project.directory, newPath)
        require(source.exists()) { "Project entry does not exist: $oldPath" }
        require(!source.isFile || (source.extension == "lean" && destination.extension == "lean")) {
            "Lean source files must keep the .lean extension"
        }
        movedLeanPaths(project, oldPath, newPath).forEach(::validateLeanSourcePath)
        requireReconciliableLakefile(project.directory)
        require(oldPath != newPath) { "Enter a different project-relative path" }
        require(!source.isDirectory || !destination.toPath().startsWith(source.toPath())) {
            "A folder cannot be moved inside itself"
        }
        val sourcePrefix = oldPath.trimEnd('/') + "/"
        val destinationPrefix = newPath.trimEnd('/') + "/"
        val movedSources = project.sourceFiles.filter { it == oldPath || it.startsWith(sourcePrefix) }
            .map { if (it == oldPath) newPath else destinationPrefix + it.removePrefix(sourcePrefix) }
        val retainedFolded = project.sourceFiles.filterNot { it == oldPath || it.startsWith(sourcePrefix) }
            .map(String::lowercase).toSet()
        require(movedSources.none { it.lowercase() in retainedFolded }) {
            "Project entry already exists (case-insensitive): $newPath"
        }
        val collision = destination.parentFile?.listFiles().orEmpty().firstOrNull {
            it != source && it.name.equals(destination.name, ignoreCase = true)
        }
        require(collision == null && (!destination.exists() || destination == source)) {
            "Project entry already exists (case-insensitive): $newPath"
        }
        destination.parentFile?.mkdirs()
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        removeEmptyParents(source.parentFile, project.directory)
        return reconcileLakeConfiguration(projectId)
    }

    fun deleteSource(projectId: String, relativePath: String): LeanProject {
        val project = open(projectId)
        require(resolveContained(project.directory, relativePath).isFile) { "Lean source does not exist: $relativePath" }
        return deleteEntry(projectId, relativePath)
    }

    fun deleteEntry(projectId: String, relativePath: String): LeanProject {
        val project = open(projectId)
        val entry = resolveContained(project.directory, relativePath)
        require(entry.exists()) { "Project entry does not exist: $relativePath" }
        val prefix = relativePath.trimEnd('/') + "/"
        val removedSources = project.sourceFiles.filter { it == relativePath || it.startsWith(prefix) }
        require(removedSources.isNotEmpty()) { "Only folders containing Lean sources can be deleted here" }
        require(project.sourceFiles.size > removedSources.size) { "A project must keep at least one Lean source file" }
        requireReconciliableLakefile(project.directory)
        if (entry.isDirectory) deleteTree(entry) else Files.delete(entry.toPath())
        removeEmptyParents(entry.parentFile, project.directory)
        return reconcileLakeConfiguration(projectId)
    }

    fun delete(id: String) {
        val project = open(id)
        deleteTree(project.directory)
        check(!project.directory.exists()) { "Could not delete project: $id" }
    }

    fun export(id: String, destination: File) {
        destination.parentFile?.mkdirs()
        val temporary = destination.resolveSibling("${destination.name}.saving")
        try {
            FileOutputStream(temporary).use { export(id, it) }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Throwable) {
            Files.deleteIfExists(temporary.toPath())
            throw failure
        }
    }

    /** Streams the supported portable project inputs without closing over provider or device identity. */
    fun export(id: String, destination: OutputStream) {
        val project = open(id)
        val entries = portableEntries(project)
        ZipOutputStream(BufferedOutputStream(destination)).use { zip ->
            entries.forEach { (path, file) ->
                zip.putNextEntry(ZipEntry(path).apply { time = 0L })
                FileInputStream(file).buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
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
            reconcileLakeConfiguration(staging, id)
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: Throwable) {
            deleteTree(staging)
            throw failure
        }
        return open(id)
    }

    /** Imports a provider-staged directory as data into a new app-managed project. */
    fun importFolder(id: String, source: File): LeanProject {
        validateId(id)
        require(source.isDirectory) { "Project folder does not exist" }
        val destination = root.resolve(id)
        require(!destination.exists()) { "Project already exists: $id" }
        val staging = root.resolve(".$id.importing")
        deleteTree(staging)
        staging.mkdirs()
        try {
            var count = 0
            var total = 0L
            val folded = hashSetOf<String>()
            Files.walkFileTree(source.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun preVisitDirectory(dir: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    require(!attrs.isSymbolicLink) { "Project folders cannot contain symbolic links" }
                    if (dir != source.toPath()) resolveContained(staging, source.toPath().relativize(dir).toString()).mkdirs()
                    return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    require(attrs.isRegularFile && !attrs.isSymbolicLink) { "Project folders may contain only regular files" }
                    require(++count <= 10_000) { "Project folder has too many entries" }
                    val relative = source.toPath().relativize(file).toString().replace(File.separatorChar, '/')
                    require(folded.add(relative.lowercase())) { "Duplicate case-folded project path: $relative" }
                    val size = attrs.size()
                    require(size <= 64L * 1024 * 1024) { "Project file exceeds 64 MiB: $relative" }
                    total += size
                    require(total <= 256L * 1024 * 1024) { "Project folder expands beyond 256 MiB" }
                    val output = resolveContained(staging, relative)
                    output.parentFile?.mkdirs()
                    Files.copy(file, output.toPath())
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            })
            validateMetadata(staging)
            validateSupportedWorkflow(staging)
            reconcileLakeConfiguration(staging, id)
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: Throwable) {
            deleteTree(staging)
            throw failure
        }
        return open(id)
    }

    fun importStandalone(id: String, filename: String, source: File): LeanProject {
        require(source.isFile) { "Lean source does not exist" }
        require(source.length() <= 8L * 1024 * 1024) { "Lean source exceeds 8 MiB" }
        val project = create(id)
        return try {
            val path = filename.takeIf { it.endsWith(".lean") } ?: "Main.lean"
            if (path == "Main.lean") save(id, path, source.readText())
            else createSource(id, path, source.readText())
            open(id)
        } catch (failure: Throwable) {
            deleteTree(project.directory)
            throw failure
        }
    }

    fun importSource(projectId: String, destinationPath: String, source: File): LeanProject {
        require(source.isFile) { "Lean source does not exist" }
        require(source.length() <= 8L * 1024 * 1024) { "Lean source exceeds 8 MiB" }
        return createSource(projectId, destinationPath, source.readText())
    }

    fun lakeBuild(factory: ToolchainCommandFactory, id: String, timeout: Duration = 5.minutes): ProcessCommand {
        val project = reconcileLakeConfiguration(id)
        return factory.lake(listOf("build"), project.directory, timeout)
    }

    fun lakeLean(factory: ToolchainCommandFactory, id: String, source: String, timeout: Duration = 2.minutes): ProcessCommand {
        val project = reconcileLakeConfiguration(id)
        val file = resolveContained(project.directory, source)
        require(file.isFile && file.extension == "lean") { "Lean source does not exist: $source" }
        return factory.lake(listOf("lean", source), project.directory, timeout)
    }

    /** Runs an already-built source directly so the supervised stdin pipe reaches Lean unchanged. */
    fun leanProgram(factory: ToolchainCommandFactory, id: String, source: String, timeout: Duration = 2.minutes): ProcessCommand {
        val project = reconcileLakeConfiguration(id)
        val file = resolveContained(project.directory, source)
        require(file.isFile && file.extension == "lean") { "Lean source does not exist: $source" }
        val projectLibrary = project.directory.resolve(".lake/build/lib/lean")
        return factory.lean(listOf("--run", source), project.directory, timeout, listOf(projectLibrary))
    }

    fun reconcileLakeConfiguration(projectId: String): LeanProject {
        val project = open(projectId)
        reconcileLakeConfiguration(project.directory, projectId)
        return open(projectId)
    }

    private fun reconcileLakeConfiguration(directory: File, projectId: String) {
        val lakefile = directory.resolve("lakefile.toml")
        val current = lakefile.readText()
        val match = requireReconciliableLakefile(directory)
        val sourceModules = mutableListOf<String>()
        Files.walk(directory.toPath()).use { paths ->
            paths.filter { path ->
                Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) && path.fileName.toString().endsWith(".lean")
            }.map { directory.toPath().relativize(it).toString().replace(File.separatorChar, '/') }
                .forEach { relative ->
                    validateLeanSourcePath(relative)
                    sourceModules += relative.removeSuffix(".lean").replace('/', '.')
                }
        }
        val roots = sourceModules.distinct().sorted()
        require(roots.isNotEmpty()) { "Project has no buildable Lean modules" }
        val module = leanName(projectId)
        val block = buildString {
            append("[[lean_lib]]\n")
            append("name = \"").append(module).append("\"\n")
            append("roots = ").append(tomlArray(roots)).append('\n')
        }
        var canonical = current.replaceRange(match.range, block)
        canonical = canonical.replaceFirst(Regex("(?m)^name\\s*=\\s*\"[A-Za-z][A-Za-z0-9_]*\"\\s*$"), "name = \"$module\"")
        canonical = canonical.replaceFirst(Regex("(?m)^defaultTargets\\s*=\\s*\\[[^]\\r\\n]*]\\s*$"), "defaultTargets = [\"$module\"]")
        if (canonical != current) atomicWrite(lakefile, canonical)
    }

    private fun requireReconciliableLakefile(directory: File): MatchResult {
        val lakefile = directory.resolve("lakefile.toml")
        require(lakefile.isFile) { "lakefile.toml is missing" }
        val contents = lakefile.readText()
        val matches = GENERATED_LEAN_LIB.findAll(contents).toList()
        if (matches.size != 1) throw UnsupportedProjectException(
            "This Lake configuration cannot be updated safely; expected one generated lean_lib block",
        )
        val lines = matches.single().groupValues[1].lineSequence()
            .map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
        if (lines.any { !GENERATED_LEAN_LIB_LINE.matches(it) } || lines.count { it.startsWith("name") } != 1) {
            throw UnsupportedProjectException("This Lake library configuration requires manual repair")
        }
        return matches.single()
    }

    private fun validateLeanSourcePath(relativePath: String) {
        require(relativePath.endsWith(".lean")) { "Lean module paths must end in .lean" }
        val modulePath = relativePath.removeSuffix(".lean").replace('\\', '/')
        require(modulePath.split('/').all(LEAN_MODULE_COMPONENT::matches)) {
            "Lean module path components must start with a letter and contain only letters, numbers, or underscores: $relativePath"
        }
    }

    private fun movedLeanPaths(project: LeanProject, oldPath: String, newPath: String): List<String> {
        val sourcePrefix = oldPath.trimEnd('/') + "/"
        val destinationPrefix = newPath.trimEnd('/') + "/"
        return project.sourceFiles.filter { it == oldPath || it.startsWith(sourcePrefix) }
            .map { if (it == oldPath) newPath else destinationPrefix + it.removePrefix(sourcePrefix) }
    }

    private fun tomlArray(values: List<String>): String = values.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

    private fun defaultLakefile(id: String): String {
        val module = leanName(id)
        return "name = \"$module\"\nversion = \"0.1.0\"\ndefaultTargets = [\"$module\"]\n\n" +
            "[[lean_lib]]\nname = \"$module\"\nroots = [\"Main\"]\n"
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

    private fun portableEntries(project: LeanProject): List<Pair<String, File>> {
        val entries = mutableListOf<Pair<String, File>>()
        var count = 0
        var total = 0L
        Files.walkFileTree(project.directory.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun preVisitDirectory(dir: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                require(!attrs.isSymbolicLink) { "Project export cannot contain symbolic links" }
                val relative = project.directory.toPath().relativize(dir).toString().replace(File.separatorChar, '/')
                return if (relative == ".lake" || relative.startsWith(".lake/")) {
                    java.nio.file.FileVisitResult.SKIP_SUBTREE
                } else java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                require(!attrs.isSymbolicLink) { "Project export cannot contain symbolic links" }
                require(attrs.isRegularFile) { "Project export may contain only regular files" }
                val relative = project.directory.toPath().relativize(file).toString().replace(File.separatorChar, '/')
                if (relative.endsWith(".lean") || relative == "lakefile.toml" || relative == "lean-toolchain" || relative == METADATA) {
                    require(++count <= MAX_PROJECT_ENTRIES) { "Project has too many portable entries" }
                    require(attrs.size() <= MAX_PROJECT_FILE_BYTES) { "Project file exceeds 64 MiB: $relative" }
                    total += attrs.size()
                    require(total <= MAX_PROJECT_BYTES) { "Portable project exceeds 256 MiB" }
                    // Re-resolve every entry so a concurrently replaced path cannot escape containment.
                    entries += relative to resolveContained(project.directory, relative)
                }
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
        val sorted = entries.sortedBy { it.first }
        require(sorted.any { it.first == METADATA } && sorted.any { it.first == "lakefile.toml" } &&
            sorted.any { it.first == "lean-toolchain" } && sorted.any { it.first.endsWith(".lean") }) {
            "Project is missing required portable inputs"
        }
        return sorted
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

    private fun requireNoCaseFoldedCollision(project: LeanProject, path: String, excluding: String? = null) {
        val folded = path.lowercase()
        require(project.sourceFiles.none { it != excluding && it.lowercase() == folded }) {
            "Lean source already exists (case-insensitive): $path"
        }
        require(!resolveContained(project.directory, path).exists() || path == excluding) {
            "Lean source already exists: $path"
        }
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

    private fun defaultMain(module: String) = """namespace $module.Main

#check Nat
example : 21 + 21 = 42 := by decide

end $module.Main
"""

    private fun defaultLibrary(module: String) = """namespace $module

def answer : Nat := 42

theorem answer_is_positive : 0 < answer := by decide

end $module
"""
}
