package org.lean4android.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.ServiceConnection
import android.provider.DocumentsContract
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Switch
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.lean4android.model.ToolchainHealth
import org.lean4android.process.JvmCommandRunner
import org.lean4android.process.JvmProcessLauncher
import org.lean4android.process.ProcessResult
import org.lean4android.process.ProcessJobState
import org.lean4android.process.ProcessJobSupervisor
import org.lean4android.project.LeanProjectRepository
import org.lean4android.toolchain.AndroidToolchainLocator
import org.lean4android.toolchain.ToolchainCommandFactory
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.TimeSource

private const val LIBRARY_SOURCE = """namespace VisualProbe

def answer : Nat := 42

theorem answer_is_positive : 0 < answer := by decide

end VisualProbe
"""

private const val MAIN_SOURCE = """import VisualProbe.Basic

#check VisualProbe.answer_is_positive
#eval VisualProbe.answer
"""

// Underscore keeps the generated Lean module name VisualProbe while avoiding the legacy M1 directory.
private const val EDITOR_PROJECT_ID = "visual_probe"
private const val EDITOR_TOOLCHAIN_ID = "lean-4.32.1-android1"

class MainActivity : ComponentActivity() {
    private lateinit var editorStore: EditorSessionStore
    private lateinit var editorState: EditorSessionState
    private var lspService: LeanLspService? = null
    private var lspBound = false
    private var activeProjectId: String = EDITOR_PROJECT_ID
    @Volatile private var activeJob: ProcessJobSupervisor? = null
    private val archivePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importArchive(it) }
    }
    private val leanFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selected ->
            android.app.AlertDialog.Builder(this)
                .setTitle("Open Lean file")
                .setItems(arrayOf("Open as standalone", "Add to $activeProjectId")) { _, choice ->
                    if (choice == 0) importStandalone(selected) else importIntoActiveProject(selected)
                }.setNegativeButton("Cancel", null).show()
        }
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { importFolder(it) }
    }
    private val lspConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            lspService = (binder as LeanLspService.LocalBinder).service()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            lspService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeProjectId = getPreferences(MODE_PRIVATE).getString("activeProject", EDITOR_PROJECT_ID) ?: EDITOR_PROJECT_ID
        val repository = editorRepository()
        if (!filesDir.resolve("projects/$EDITOR_PROJECT_ID").exists()) {
            repository.create(EDITOR_PROJECT_ID)
            repository.save(EDITOR_PROJECT_ID, "Main.lean", MAIN_SOURCE)
            repository.save(EDITOR_PROJECT_ID, "VisualProbe/Basic.lean", LIBRARY_SOURCE)
        }
        if (runCatching { repository.open(activeProjectId) }.isFailure) {
            activeProjectId = EDITOR_PROJECT_ID
            getPreferences(MODE_PRIVATE).edit().putString("activeProject", activeProjectId).apply()
        }
        editorStore = EditorSessionStore(filesDir.resolve("editor-recovery/$activeProjectId.bin"))
        editorState = editorStore.loadOrCreate(repository, activeProjectId)
        setContent {
            var darkTheme by remember { mutableStateOf(getPreferences(MODE_PRIVATE).getBoolean("darkTheme", false)) }
            MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
                LeanEditorScreen(
                    initialState = editorState,
                    onStateChanged = {
                        editorState = it
                        editorStore.save(it)
                    },
                    onCreateSource = ::createSource,
                    onOpenSource = ::openSource,
                    onSave = ::saveSource,
                    onSaveAs = ::saveSourceAs,
                    onCheck = ::checkLeanSource,
                    onCancel = ::cancelRun,
                    onVerifyRuntime = ::verifyRuntime,
                    projects = repository.list().map { it.id },
                    projectFiles = repository.open(activeProjectId).sourceFiles,
                    recentProjects = recentProjects(),
                    onOpenProject = ::switchProject,
                    onImportArchive = { archivePicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onImportFolder = { folderPicker.launch(null) },
                    onOpenLeanFile = { leanFilePicker.launch(arrayOf("text/plain", "application/octet-stream")) },
                    onNewProject = { createAndSwitchProject() },
                    darkTheme = darkTheme,
                    onDarkThemeChanged = { enabled ->
                        darkTheme = enabled
                        getPreferences(MODE_PRIVATE).edit().putBoolean("darkTheme", enabled).apply()
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lspBound = bindService(Intent(this, LeanLspService::class.java), lspConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        editorStore.save(editorState)
        if (lspBound) {
            unbindService(lspConnection)
            lspBound = false
            lspService = null
        }
        super.onStop()
    }

    override fun onDestroy() {
        activeJob?.close()
        activeJob = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        editorStore.save(editorState)
        super.onSaveInstanceState(outState)
    }

    private fun editorRepository() = LeanProjectRepository(
        filesDir.resolve("projects"),
        EDITOR_TOOLCHAIN_ID,
    )

    private fun switchProject(id: String) {
        editorStore.save(editorState)
        val recent = (listOf(id) + recentProjects()).distinct().take(10)
        getPreferences(MODE_PRIVATE).edit().putString("activeProject", id).putString("recentProjects", recent.joinToString("\n")).apply()
        recreate()
    }

    private fun recentProjects(): List<String> = getPreferences(MODE_PRIVATE).getString("recentProjects", "")
        .orEmpty().lineSequence().filter(String::isNotBlank).filter { runCatching { editorRepository().open(it) }.isSuccess }.toList()

    private fun createAndSwitchProject() {
        val id = uniqueProjectId("project")
        runCatching { editorRepository().create(id) }.onSuccess { switchProject(id) }
    }

    private fun importArchive(uri: Uri) = importFromProvider("archive") { id, staged ->
        contentResolver.openInputStream(uri).use { input -> copyBounded(requireNotNull(input), staged, 256L * 1024 * 1024) }
        editorRepository().import(id, staged)
    }

    private fun importStandalone(uri: Uri) = importFromProvider("scratch") { id, staged ->
        contentResolver.openInputStream(uri).use { input -> copyBounded(requireNotNull(input), staged, 8L * 1024 * 1024) }
        val name = queryDisplayName(uri).takeIf { it.endsWith(".lean", ignoreCase = true) } ?: "Main.lean"
        editorRepository().importStandalone(id, name.replaceAfterLast('.', "lean"), staged)
    }

    private fun importIntoActiveProject(uri: Uri) = importFromProvider("source") { _, staged ->
        contentResolver.openInputStream(uri).use { input -> copyBounded(requireNotNull(input), staged, 8L * 1024 * 1024) }
        val raw = queryDisplayName(uri)
        val name = raw.substringBeforeLast('.') + ".lean"
        editorRepository().importSource(activeProjectId, name, staged)
    }

    private fun importFolder(uri: Uri) = importFromProvider("folder", directory = true) { id, staged ->
        copyDocumentTree(uri, staged)
        editorRepository().importFolder(id, staged)
    }

    private fun importFromProvider(prefix: String, directory: Boolean = false, operation: (String, java.io.File) -> Unit) {
        thread(name = "workspace-import") {
            val id = if (prefix == "source") activeProjectId else uniqueProjectId(prefix)
            val staged = cacheDir.resolve("workspace-import/${id}${if (directory) "" else ".bin"}")
            runCatching {
                staged.parentFile?.mkdirs()
                if (directory) staged.mkdirs()
                operation(id, staged)
            }.onSuccess { runOnUiThread { switchProject(id) } }
                .onFailure { failure -> runOnUiThread { android.widget.Toast.makeText(this, failure.message, android.widget.Toast.LENGTH_LONG).show() } }
            deleteWithoutFollowingLinks(staged)
        }
    }

    private fun uniqueProjectId(prefix: String): String {
        val base = "${prefix}_${System.currentTimeMillis()}"
        return base.take(64)
    }

    private fun copyBounded(input: java.io.InputStream, target: java.io.File, limit: Long): Long {
        var total = 0L
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= limit) { "Selected content exceeds ${limit / 1024 / 1024} MiB" }
                output.write(buffer, 0, read)
            }
        }
        return total
    }

    private fun deleteWithoutFollowingLinks(file: java.io.File) {
        if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(file.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(path: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.delete(path); return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(path: java.nio.file.Path, failure: java.io.IOException?): java.nio.file.FileVisitResult {
                if (failure != null) throw failure
                Files.delete(path); return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    private fun queryDisplayName(uri: Uri): String = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Main.lean"

    private fun copyDocumentTree(treeUri: Uri, destination: java.io.File) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        var count = 0
        var total = 0L
        fun copyChildren(documentId: String, output: java.io.File) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    require(++count <= 10_000) { "Project folder has too many entries" }
                    val childId = cursor.getString(0)
                    val name = cursor.getString(1)
                    require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name) { "Invalid provider filename" }
                    val target = output.resolve(name)
                    if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        target.mkdirs(); copyChildren(childId, target)
                    } else {
                        val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                        require(size <= 64L * 1024 * 1024) { "Project file exceeds 64 MiB" }
                        val child = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        val copied = contentResolver.openInputStream(child).use { input ->
                            copyBounded(requireNotNull(input), target, 64L * 1024 * 1024)
                        }
                        total += copied
                        require(total <= 256L * 1024 * 1024) { "Project folder exceeds 256 MiB" }
                    }
                }
            }
        }
        copyChildren(rootId, destination)
    }

    private fun createSource(state: EditorSessionState, path: String): EditorSessionState {
        editorRepository().createSource(state.projectId, path)
        return state.add(path)
    }

    private fun openSource(state: EditorSessionState, path: String): EditorSessionState =
        if (state.tabs.any { it.path == path }) state.select(path)
        else state.add(path, editorRepository().read(state.projectId, path))

    private fun renameSource(state: EditorSessionState, path: String): EditorSessionState {
        val activePath = requireNotNull(state.activePath) { "No file is open" }
        editorRepository().renameSource(state.projectId, activePath, path)
        return state.rename(activePath, path)
    }

    private fun saveSource(state: EditorSessionState): EditorSessionState {
        val activePath = requireNotNull(state.activePath) { "No file is open" }
        val tab = state.tabs.single { it.path == activePath }
        editorRepository().save(state.projectId, activePath, tab.contents)
        return state.markSaved(activePath)
    }

    private fun saveSourceAs(state: EditorSessionState, path: String): EditorSessionState {
        val activePath = requireNotNull(state.activePath) { "No file is open" }
        val tab = state.tabs.single { it.path == activePath }
        editorRepository().copySource(state.projectId, activePath, path, tab.contents)
        return state.saveAs(activePath, path)
    }

    private fun deleteSource(state: EditorSessionState): EditorSessionState {
        val activePath = requireNotNull(state.activePath) { "No file is open" }
        editorRepository().deleteSource(state.projectId, activePath)
        return state.remove(activePath)
    }

    private fun checkLeanSource(sources: Map<String, String>, update: (EditorRunState) -> Unit) {
        thread(name = "lean-editor-check") {
            runCatching {
                val locator = AndroidToolchainLocator(applicationContext)
                locator.installSysroot()
                val layout = when (val health = locator.locate()) {
                    is ToolchainHealth.Ready -> health.layout
                    is ToolchainHealth.Missing -> error(health.problems.joinToString("\n"))
                }

                val repository = LeanProjectRepository(filesDir.resolve("projects"), layout.id.value)
                if (!filesDir.resolve("projects/$activeProjectId").exists()) repository.create(activeProjectId)
                sources.forEach { (path, source) -> repository.save(activeProjectId, path, source) }
                val factory = ToolchainCommandFactory(layout, filesDir, cacheDir)
                val started = TimeSource.Monotonic.markNow()
                val buildCommand = repository.lakeBuild(factory, activeProjectId)
                val entry = repository.open(activeProjectId).sourceFiles.firstOrNull { it == "Main.lean" }
                    ?: repository.open(activeProjectId).sourceFiles.first()
                runOnUiThread { startBuildThenRun(buildCommand, repository.lakeLean(factory, activeProjectId, entry), entry, started, update) }
            }.onFailure { failure -> runOnUiThread { update(EditorRunState.Failed(failure.message ?: failure::class.java.simpleName)) } }
        }
    }

    private fun startBuildThenRun(
        buildCommand: org.lean4android.process.ProcessCommand,
        runCommand: org.lean4android.process.ProcessCommand,
        entry: String,
        started: kotlin.time.TimeMark,
        update: (EditorRunState) -> Unit,
    ) {
        activeJob?.close()
        activeJob = ProcessJobSupervisor(buildCommand, JvmProcessLauncher()) { buildState ->
            when (buildState) {
                is ProcessJobState.Completed -> if (buildState.result.exitCode == 0) {
                    val build = buildState.result
                    activeJob = ProcessJobSupervisor(runCommand, JvmProcessLauncher()) { runState ->
                        val final = when (runState) {
                            is ProcessJobState.Completed -> EditorRunState.Finished(
                                runState.result.copy(
                                    stdout = build.stdout + "\nBuild completed; running $entry\n" + runState.result.stdout,
                                    stderr = build.stderr + runState.result.stderr,
                                ), started.elapsedNow(),
                            )
                            is ProcessJobState.Cancelled -> EditorRunState.Cancelled
                            is ProcessJobState.Failed -> EditorRunState.Failed(runState.message)
                            ProcessJobState.Running -> return@ProcessJobSupervisor
                        }
                        activeJob = null
                        runOnUiThread { update(final) }
                    }
                } else {
                    activeJob = null
                    runOnUiThread { update(EditorRunState.Finished(buildState.result, started.elapsedNow())) }
                }
                is ProcessJobState.Cancelled -> { activeJob = null; runOnUiThread { update(EditorRunState.Cancelled) } }
                is ProcessJobState.Failed -> { activeJob = null; runOnUiThread { update(EditorRunState.Failed(buildState.message)) } }
                ProcessJobState.Running -> Unit
            }
        }
    }

    private fun cancelRun() { activeJob?.cancel() }

    private fun verifyRuntime(update: (EditorRunState) -> Unit) {
        thread(name = "lean-runtime-integrity") {
            val started = TimeSource.Monotonic.markNow()
            val state = runCatching {
                val problems = AndroidToolchainLocator(applicationContext).verifyInstalledRuntime()
                EditorRunState.IntegrityFinished(problems, started.elapsedNow())
            }.getOrElse { EditorRunState.Failed(it.message ?: it::class.java.simpleName) }
            runOnUiThread { update(state) }
        }
    }
}

internal fun writeAtomically(destination: java.io.File, contents: String) {
    destination.parentFile?.mkdirs()
    val temporary = destination.resolveSibling("${destination.name}.saving")
    temporary.writeText(contents)
    Files.move(
        temporary.toPath(),
        destination.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
}

private sealed interface EditorRunState {
    data object Idle : EditorRunState
    data object Running : EditorRunState
    data object Cancelled : EditorRunState
    data class Finished(val result: ProcessResult, val elapsed: Duration) : EditorRunState
    data class IntegrityFinished(val problems: List<String>, val elapsed: Duration) : EditorRunState
    data class Failed(val message: String) : EditorRunState
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LeanEditorScreen(
    initialState: EditorSessionState,
    onStateChanged: (EditorSessionState) -> Unit,
    onCreateSource: (EditorSessionState, String) -> EditorSessionState,
    onOpenSource: (EditorSessionState, String) -> EditorSessionState,
    onSave: (EditorSessionState) -> EditorSessionState,
    onSaveAs: (EditorSessionState, String) -> EditorSessionState,
    onCheck: (Map<String, String>, (EditorRunState) -> Unit) -> Unit,
    onCancel: () -> Unit,
    onVerifyRuntime: ((EditorRunState) -> Unit) -> Unit,
    projects: List<String>,
    projectFiles: List<String>,
    recentProjects: List<String>,
    onOpenProject: (String) -> Unit,
    onImportArchive: () -> Unit,
    onImportFolder: () -> Unit,
    onOpenLeanFile: () -> Unit,
    onNewProject: () -> Unit,
    darkTheme: Boolean,
    onDarkThemeChanged: (Boolean) -> Unit,
) {
    var editor by remember { mutableStateOf(initialState) }
    var runState by remember { mutableStateOf<EditorRunState>(EditorRunState.Idle) }
    var fileAction by remember { mutableStateOf<String?>(null) }
    var requestedPath by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var filesMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var projectExpanded by remember { mutableStateOf(true) }
    var openWorkspace by remember { mutableStateOf(false) }
    var closeDirty by remember { mutableStateOf(false) }
    var settingsPage by remember { mutableStateOf<String?>(null) }
    val fieldValues = remember {
        mutableStateMapOf<String, TextFieldValue>().apply {
            initialState.tabs.forEach { tab ->
                this[tab.path] = TextFieldValue(tab.contents, TextRange(tab.contents.length))
            }
        }
    }
    val histories = remember {
        initialState.tabs.associate { it.path to EditorUndoHistory(it.contents) }.toMutableMap()
    }
    val running = runState == EditorRunState.Running
    BackHandler(enabled = filesMenu || moreMenu || drawerOpen || openWorkspace || settingsPage != null) {
        when (settingsPage) {
            "appearance" -> settingsPage = "settings"
            "settings" -> settingsPage = null
            else -> { filesMenu = false; moreMenu = false; drawerOpen = false; openWorkspace = false }
        }
    }

    fun publish(state: EditorSessionState) {
        editor = state
        onStateChanged(state)
    }

    fun replaceActive(value: TextFieldValue, record: Boolean = true) {
        val activePath = editor.activePath ?: return
        if (record) histories.getValue(activePath).record(value.text)
        fieldValues[activePath] = value
        publish(editor.edit(activePath, value.text))
    }

    fun undo() {
        histories[editor.activePath]?.undo()?.let { text ->
            replaceActive(TextFieldValue(text, TextRange(text.length)), record = false)
        }
    }

    fun redo() {
        histories[editor.activePath]?.redo()?.let { text ->
            replaceActive(TextFieldValue(text, TextRange(text.length)), record = false)
        }
    }

    fun navigateSearch(backwards: Boolean) {
        val activePath = editor.activePath ?: return
        val value = fieldValues.getValue(activePath)
        nextEditorMatch(findEditorMatches(value.text, searchQuery), value.selection, backwards)?.let { match ->
            fieldValues[activePath] = value.copy(selection = match)
        }
    }

    fun buildProject() {
        if (running) return
        runState = EditorRunState.Running
        onCheck(editor.tabs.associate { it.path to it.contents }) { result ->
            runState = result
            if (result is EditorRunState.Finished) publish(editor.markSaved())
        }
    }

    fun saveActive() {
        runCatching { onSave(editor) }
            .onSuccess(::publish)
            .onFailure { runState = EditorRunState.Failed(it.message.orEmpty()) }
    }

    fun closeActive(discard: Boolean = false) {
        val activePath = editor.activePath ?: return
        val tab = editor.tabs.single { it.path == activePath }
        if (tab.dirty && !discard) { closeDirty = true; return }
        fieldValues.remove(activePath)
        histories.remove(activePath)
        publish(editor.remove(activePath))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { filesMenu = false; moreMenu = false; drawerOpen = !drawerOpen }) {
                        Text("☰", modifier = Modifier.semantics { contentDescription = "Navigation drawer" })
                    }
                },
                title = {
                    Column {
                        Text(editor.activePath?.substringAfterLast('/') ?: "No file open")
                        Text(editor.activePath?.substringBeforeLast('/', editor.projectId) ?: editor.projectId,
                            style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    IconButton(enabled = !running && editor.tabs.isNotEmpty(), onClick = ::buildProject) {
                        Text("▶", modifier = Modifier.semantics { contentDescription = "Run project" })
                    }
                    androidx.compose.foundation.layout.Box {
                        IconButton(onClick = { drawerOpen = false; moreMenu = false; filesMenu = !filesMenu }) {
                            Text("▣", modifier = Modifier.semantics { contentDescription = "Files menu" })
                        }
                        DropdownMenu(expanded = filesMenu, onDismissRequest = { filesMenu = false }) {
                            DropdownMenuItem(text = { Text("＋  New") }, enabled = !running, onClick = { filesMenu = false; requestedPath = "New.lean"; fileAction = "New source" })
                            DropdownMenuItem(text = { Text("▤  Open") }, onClick = { filesMenu = false; openWorkspace = true })
                            DropdownMenuItem(text = { Text("✓  Save") }, enabled = !running && editor.activePath != null && editor.tabs.single { it.path == editor.activePath }.dirty, onClick = { filesMenu = false; saveActive() })
                            DropdownMenuItem(text = { Text("⧉  Save As") }, enabled = !running && editor.activePath != null, onClick = { filesMenu = false; requestedPath = editor.activePath.orEmpty(); fileAction = "Save As" })
                            DropdownMenuItem(text = { Text("×  Close") }, enabled = !running && editor.activePath != null, onClick = { filesMenu = false; closeActive() })
                        }
                    }
                    androidx.compose.foundation.layout.Box {
                        IconButton(onClick = { drawerOpen = false; filesMenu = false; moreMenu = !moreMenu }) {
                            Text("⋮", modifier = Modifier.semantics { contentDescription = "More menu" })
                        }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(text = { Text("↶  Undo") }, enabled = histories[editor.activePath]?.canUndo == true, onClick = { moreMenu = false; undo() })
                            DropdownMenuItem(text = { Text("↷  Redo") }, enabled = histories[editor.activePath]?.canRedo == true, onClick = { moreMenu = false; redo() })
                            DropdownMenuItem(text = { Text("⌕  Find") }, enabled = editor.activePath != null, onClick = { moreMenu = false; searchVisible = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (event.key == Key.Escape && (filesMenu || moreMenu || drawerOpen)) {
                        filesMenu = false; moreMenu = false; drawerOpen = false
                        return@onPreviewKeyEvent true
                    }
                    if (!event.isCtrlPressed) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.F -> { searchVisible = true; true }
                        Key.Z -> { if (event.isShiftPressed) redo() else undo(); true }
                        Key.Y -> { redo(); true }
                        Key.S -> { saveActive(); true }
                        else -> false
                    }
                },
        ) {
            val wide = maxWidth >= 600.dp
            val workspaceModifier = Modifier.fillMaxSize().padding(12.dp)
            if (wide) {
                Row(workspaceModifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EditorContent(
                        modifier = Modifier.weight(1f),
                        editor = editor,
                        value = editor.activePath?.let(fieldValues::getValue) ?: TextFieldValue(),
                        running = running,
                        runState = runState,
                        searchVisible = searchVisible,
                        searchQuery = searchQuery,
                        canUndo = histories[editor.activePath]?.canUndo == true,
                        canRedo = histories[editor.activePath]?.canRedo == true,
                        onValueChange = ::replaceActive,
                        onSearchQuery = { searchQuery = it },
                        onSearchOpen = { searchVisible = true },
                        onSearchClose = { searchVisible = false; searchQuery = "" },
                        onSearchPrevious = { navigateSearch(true) },
                        onSearchNext = { navigateSearch(false) },
                        onUndo = ::undo,
                        onRedo = ::redo,
                        onBuild = ::buildProject,
                        onCancel = onCancel,
                        onVerify = { runState = EditorRunState.Running; onVerifyRuntime { runState = it } },
                        onSelectTab = { publish(editor.select(it)) },
                    )
                }
            } else {
                Column(workspaceModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorContent(
                        modifier = Modifier.weight(1f),
                        editor = editor,
                        value = editor.activePath?.let(fieldValues::getValue) ?: TextFieldValue(),
                        running = running,
                        runState = runState,
                        searchVisible = searchVisible,
                        searchQuery = searchQuery,
                        canUndo = histories[editor.activePath]?.canUndo == true,
                        canRedo = histories[editor.activePath]?.canRedo == true,
                        onValueChange = ::replaceActive,
                        onSearchQuery = { searchQuery = it },
                        onSearchOpen = { searchVisible = true },
                        onSearchClose = { searchVisible = false; searchQuery = "" },
                        onSearchPrevious = { navigateSearch(true) },
                        onSearchNext = { navigateSearch(false) },
                        onUndo = ::undo,
                        onRedo = ::redo,
                        onBuild = ::buildProject,
                        onCancel = onCancel,
                        onVerify = { runState = EditorRunState.Running; onVerifyRuntime { runState = it } },
                        onSelectTab = { publish(editor.select(it)) },
                    )
                }
            }
            if (drawerOpen) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().clickable { drawerOpen = false })
                Surface(Modifier.width(300.dp).fillMaxHeight().clickable { }, tonalElevation = 8.dp) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { projectExpanded = !projectExpanded }) {
                            Text((if (projectExpanded) "▾" else "▸") + " Project")
                        }
                        if (projectExpanded) projectFiles.forEach { path ->
                            val tab = editor.tabs.singleOrNull { it.path == path }
                            TextButton(
                                modifier = Modifier.fillMaxWidth().semantics {
                                    contentDescription = "Open $path"
                                    stateDescription = if (path == editor.activePath) "Selected" else "Not selected"
                                },
                                onClick = {
                                    val next = onOpenSource(editor, path)
                                    if (path !in fieldValues) {
                                        val opened = next.tabs.single { it.path == path }
                                        fieldValues[path] = TextFieldValue(opened.contents, TextRange(opened.contents.length))
                                        histories[path] = EditorUndoHistory(opened.contents)
                                    }
                                    publish(next); drawerOpen = false
                                },
                            ) { Text(path + if (tab?.dirty == true) " •" else "") }
                        }
                        TextButton(enabled = !running, onClick = { drawerOpen = false; buildProject() }) { Text("Build project") }
                        TextButton(enabled = !running, onClick = { drawerOpen = false; runState = EditorRunState.Running; onVerifyRuntime { runState = it } }) { Text("Verify runtime") }
                        TextButton(onClick = { drawerOpen = false; settingsPage = "settings" }) { Text("⚙ Settings") }
                    }
                }
            }
        }
    }

    if (openWorkspace) {
        Dialog(onDismissRequest = { openWorkspace = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Open workspace", style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = { openWorkspace = false }) { Text("Back") }
                }
                Text("Recent", style = MaterialTheme.typography.titleSmall)
                if (recentProjects.isEmpty()) Text("No recent projects")
                recentProjects.forEach { id -> TextButton(onClick = { openWorkspace = false; onOpenProject(id) }) { Text(id) } }
                Text("My Projects", style = MaterialTheme.typography.titleSmall)
                projects.forEach { id -> TextButton(onClick = { openWorkspace = false; onOpenProject(id) }) { Text(id) } }
                TextButton(onClick = { openWorkspace = false; onImportArchive() }) { Text("Import Project Archive") }
                TextButton(onClick = { openWorkspace = false; onImportFolder() }) { Text("Import Project Folder") }
                TextButton(onClick = { openWorkspace = false; onOpenLeanFile() }) { Text("Open Lean File") }
                TextButton(onClick = { openWorkspace = false; onNewProject() }) { Text("New Project") }
                Text("Projects are app-managed copies and are removed on uninstall. Export important work.", style = MaterialTheme.typography.bodySmall)
            } }
        }
    }

    settingsPage?.let { page ->
        Dialog(onDismissRequest = { settingsPage = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { settingsPage = if (page == "appearance") "settings" else null }) { Text("Back") }
                        Text(if (page == "appearance") "Appearance" else "Settings", style = MaterialTheme.typography.headlineSmall)
                    }
                    if (page == "settings") {
                        TextButton(
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Open Appearance settings" },
                            onClick = { settingsPage = "appearance" },
                        ) { Text("Appearance") }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().clickable { onDarkThemeChanged(!darkTheme) }.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column { Text("Dark theme"); Text("Use dark colors throughout the app", style = MaterialTheme.typography.bodySmall) }
                            Switch(
                                checked = darkTheme,
                                onCheckedChange = onDarkThemeChanged,
                                modifier = Modifier.semantics { contentDescription = "Dark theme" },
                            )
                        }
                    }
                }
            }
        }
    }

    if (closeDirty) {
        AlertDialog(
            onDismissRequest = { closeDirty = false },
            title = { Text("Save changes?") },
            text = { Text("Save changes before closing ${editor.activePath}?") },
            confirmButton = { TextButton(onClick = { closeDirty = false; saveActive(); closeActive(true) }) { Text("Save") } },
            dismissButton = {
                Row { TextButton(onClick = { closeDirty = false; closeActive(true) }) { Text("Discard") }; TextButton(onClick = { closeDirty = false }) { Text("Cancel") } }
            },
        )
    }

    fileAction?.let { action ->
        AlertDialog(
            onDismissRequest = { fileAction = null },
            title = { Text(action) },
            text = {
                if (action == "Delete source") Text("Delete $requestedPath? Unsaved changes in this tab will be lost.")
                else OutlinedTextField(
                        value = requestedPath,
                        onValueChange = { requestedPath = it },
                        singleLine = true,
                        label = { Text("Project-relative .lean path") },
                    )
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        when (action) {
                            "New source" -> onCreateSource(editor, requestedPath)
                            "Save As" -> onSaveAs(editor, requestedPath)
                            else -> error("Unknown file action")
                        }
                    }.onSuccess {
                        val oldPath = editor.activePath
                        val oldValue = oldPath?.let(fieldValues::get)
                        val oldHistory = oldPath?.let(histories::get)
                        val newState = it
                        newState.tabs.forEach { tab ->
                            if (tab.path !in fieldValues) {
                                val renamed = oldValue?.takeIf { oldPath !in newState.tabs.map(EditorTab::path) }
                                fieldValues[tab.path] = renamed ?: TextFieldValue(tab.contents, TextRange(tab.contents.length))
                                histories[tab.path] = oldHistory?.takeIf { renamed != null } ?: EditorUndoHistory(tab.contents)
                            }
                        }
                        fieldValues.keys.retainAll(newState.tabs.map(EditorTab::path).toSet())
                        histories.keys.retainAll(newState.tabs.map(EditorTab::path).toSet())
                        publish(newState)
                        fileAction = null
                    }.onFailure {
                        runState = EditorRunState.Failed(it.message.orEmpty())
                    }
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { fileAction = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EditorFilePanel(
    editor: EditorSessionState,
    running: Boolean,
    vertical: Boolean,
    modifier: Modifier,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    if (vertical) {
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Project files", style = MaterialTheme.typography.titleSmall)
                editor.tabs.forEach { tab ->
                    TextButton(
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = "Open ${tab.path}"
                            stateDescription = if (tab.dirty) "Unsaved changes" else "Saved"
                        },
                        enabled = !running && editor.activePath != tab.path,
                        onClick = { onSelect(tab.path) },
                    ) { Text(tab.path + if (tab.dirty) " •" else "") }
                }
                EditorFileActions(editor.tabs.size, running, onNew, onRename, onDelete)
            }
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                editor.tabs.forEach { tab ->
                    Button(
                        modifier = Modifier.semantics {
                            contentDescription = "Open ${tab.path}"
                            stateDescription = if (tab.dirty) "Unsaved changes" else "Saved"
                        },
                        onClick = { onSelect(tab.path) },
                        enabled = !running && editor.activePath != tab.path,
                    ) { Text(tab.path.substringAfterLast('/') + if (tab.dirty) " •" else "") }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                EditorFileActions(editor.tabs.size, running, onNew, onRename, onDelete)
            }
        }
    }
}

@Composable
private fun EditorFileActions(
    tabCount: Int,
    running: Boolean,
    onNew: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    TextButton(enabled = !running, onClick = onNew) { Text("New") }
    TextButton(enabled = !running, onClick = onRename) { Text("Rename") }
    TextButton(enabled = !running && tabCount > 1, onClick = onDelete) { Text("Delete") }
}

@Composable
private fun EditorContent(
    modifier: Modifier,
    editor: EditorSessionState,
    value: TextFieldValue,
    running: Boolean,
    runState: EditorRunState,
    searchVisible: Boolean,
    searchQuery: String,
    canUndo: Boolean,
    canRedo: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onSearchQuery: (String) -> Unit,
    onSearchOpen: () -> Unit,
    onSearchClose: () -> Unit,
    onSearchPrevious: () -> Unit,
    onSearchNext: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBuild: () -> Unit,
    onCancel: () -> Unit,
    onVerify: () -> Unit,
    onSelectTab: (String) -> Unit,
) {
    if (editor.activePath == null) {
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No file open", style = MaterialTheme.typography.titleMedium)
                Text("Use Files → New or Open, or choose a file from the Project drawer.")
                OutputPanel(runState)
            }
        }
        return
    }
    val matches = findEditorMatches(value.text, searchQuery)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FileTabStrip(editor = editor, enabled = !running, onSelect = onSelectTab)
        if (searchVisible) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQuery,
                    singleLine = true,
                    label = { Text("Find in file") },
                    modifier = Modifier.width(240.dp).semantics { contentDescription = "Find text in current Lean file" },
                )
                Text("${matches.size} matches")
                TextButton(enabled = matches.isNotEmpty(), onClick = onSearchPrevious) { Text("Previous") }
                TextButton(enabled = matches.isNotEmpty(), onClick = onSearchNext) { Text("Next") }
                IconButton(onClick = onSearchClose) { Text("×", modifier = Modifier.semantics { contentDescription = "Close search" }) }
            }
        }
        val editorScroll = rememberScrollState()
        val editorStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        Surface(Modifier.fillMaxWidth().weight(1f), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
            Row(Modifier.fillMaxSize().verticalScroll(editorScroll).padding(vertical = 12.dp)) {
                Text(
                    editorLineNumbers(value.text),
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 10.dp)
                        .semantics { contentDescription = "Line numbers" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = editorStyle,
                )
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp)
                        .semantics {
                            contentDescription = "Lean source editor for ${editor.activePath}"
                            stateDescription = if (editor.tabs.single { it.path == editor.activePath }.dirty) "Unsaved changes" else "Saved"
                        },
                    enabled = !running,
                    textStyle = editorStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    visualTransformation = LeanSyntaxVisualTransformation(searchQuery),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                )
            }
        }
        if (running) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator()
            Text("Working…")
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        OutputPanel(runState)
    }
}

@Composable
private fun FileTabStrip(editor: EditorSessionState, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        editor.tabs.forEach { tab ->
            val active = tab.path == editor.activePath
            Surface(
                color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .clickable(enabled = enabled && !active) { onSelect(tab.path) }
                    .semantics {
                        contentDescription = "File tab ${tab.path}"
                        stateDescription = listOf(if (active) "Selected" else "Not selected", if (tab.dirty) "Unsaved changes" else "Saved").joinToString(", ")
                    },
            ) {
                Text(tab.path.substringAfterLast('/') + if (tab.dirty) " •" else "", Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
}

internal fun editorLineNumbers(text: String): String = (1..(text.count { it == '\n' } + 1)).joinToString("\n")

@Composable
private fun OutputPanel(state: EditorRunState) {
    val output = when (state) {
        EditorRunState.Idle -> "Edit the source, then use Run or the Project drawer actions."
        EditorRunState.Running -> "Working…"
        EditorRunState.Cancelled -> "Run cancelled."
        is EditorRunState.Failed -> "Could not run Lean:\n${state.message}"
        is EditorRunState.Finished -> formatResult(state.result, state.elapsed)
        is EditorRunState.IntegrityFinished -> formatIntegrityResult(state.problems, state.elapsed)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 100.dp, max = 200.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        SelectionContainer {
            Text(
                text = output,
                modifier = Modifier
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

internal fun formatIntegrityResult(problems: List<String>, elapsed: Duration): String = buildString {
    append("Runtime integrity • ${elapsed.inWholeMilliseconds} ms\n\n")
    if (problems.isEmpty()) {
        append("All packaged runtime files match the installed manifest.")
    } else {
        append("Integrity check found ${problems.size} problem(s):\n")
        problems.forEach { append("• $it\n") }
    }
}

internal fun formatResult(result: ProcessResult, elapsed: Duration): String = buildString {
    append("Exit: ${result.exitCode}")
    if (result.timedOut) append(" (timed out)")
    append(" • ${elapsed.inWholeMilliseconds} ms")
    val stdout = result.stdout.trimEnd()
    val stderr = result.stderr.trimEnd()
    if (stdout.isNotEmpty()) append("\n\nstdout:\n$stdout")
    if (stderr.isNotEmpty()) append("\n\nstderr:\n$stderr")
    if (stdout.isEmpty() && stderr.isEmpty()) append("\n\nLean produced no output.")
}
