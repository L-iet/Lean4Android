package org.lean4android.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import org.lean4android.process.ProcessResult
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
        val repository = editorRepository()
        if (!filesDir.resolve("projects/$EDITOR_PROJECT_ID").exists()) {
            repository.create(EDITOR_PROJECT_ID)
            repository.save(EDITOR_PROJECT_ID, "Main.lean", MAIN_SOURCE)
            repository.save(EDITOR_PROJECT_ID, "VisualProbe/Basic.lean", LIBRARY_SOURCE)
        }
        editorStore = EditorSessionStore(filesDir.resolve("editor-recovery/$EDITOR_PROJECT_ID.bin"))
        editorState = editorStore.loadOrCreate(repository, EDITOR_PROJECT_ID)
        setContent {
            MaterialTheme {
                LeanEditorScreen(
                    initialState = editorState,
                    onStateChanged = {
                        editorState = it
                        editorStore.save(it)
                    },
                    onCreateSource = ::createSource,
                    onRenameSource = ::renameSource,
                    onDeleteSource = ::deleteSource,
                    onCheck = ::checkLeanSource,
                    onVerifyRuntime = ::verifyRuntime,
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

    override fun onSaveInstanceState(outState: Bundle) {
        editorStore.save(editorState)
        super.onSaveInstanceState(outState)
    }

    private fun editorRepository() = LeanProjectRepository(
        filesDir.resolve("projects"),
        EDITOR_TOOLCHAIN_ID,
    )

    private fun createSource(state: EditorSessionState, path: String): EditorSessionState {
        editorRepository().createSource(state.projectId, path)
        return state.add(path)
    }

    private fun renameSource(state: EditorSessionState, path: String): EditorSessionState {
        editorRepository().renameSource(state.projectId, state.activePath, path)
        return state.rename(state.activePath, path)
    }

    private fun deleteSource(state: EditorSessionState): EditorSessionState {
        editorRepository().deleteSource(state.projectId, state.activePath)
        return state.remove(state.activePath)
    }

    private fun checkLeanSource(sources: Map<String, String>, update: (EditorRunState) -> Unit) {
        thread(name = "lean-editor-check") {
            val state = runCatching {
                val locator = AndroidToolchainLocator(applicationContext)
                locator.installSysroot()
                val layout = when (val health = locator.locate()) {
                    is ToolchainHealth.Ready -> health.layout
                    is ToolchainHealth.Missing -> error(health.problems.joinToString("\n"))
                }

                val repository = LeanProjectRepository(filesDir.resolve("projects"), layout.id.value)
                if (!filesDir.resolve("projects/$EDITOR_PROJECT_ID").exists()) repository.create(EDITOR_PROJECT_ID)
                sources.forEach { (path, source) -> repository.save(EDITOR_PROJECT_ID, path, source) }
                val factory = ToolchainCommandFactory(layout, filesDir, cacheDir)
                val started = TimeSource.Monotonic.markNow()
                val result = runBlocking {
                    JvmCommandRunner().run(repository.lakeBuild(factory, EDITOR_PROJECT_ID))
                }
                EditorRunState.Finished(result, started.elapsedNow())
            }.getOrElse { EditorRunState.Failed(it.message ?: it::class.java.simpleName) }
            runOnUiThread { update(state) }
        }
    }

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
    onRenameSource: (EditorSessionState, String) -> EditorSessionState,
    onDeleteSource: (EditorSessionState) -> EditorSessionState,
    onCheck: (Map<String, String>, (EditorRunState) -> Unit) -> Unit,
    onVerifyRuntime: ((EditorRunState) -> Unit) -> Unit,
) {
    var editor by remember { mutableStateOf(initialState) }
    var runState by remember { mutableStateOf<EditorRunState>(EditorRunState.Idle) }
    var fileAction by remember { mutableStateOf<String?>(null) }
    var requestedPath by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
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

    fun publish(state: EditorSessionState) {
        editor = state
        onStateChanged(state)
    }

    fun replaceActive(value: TextFieldValue, record: Boolean = true) {
        if (record) histories.getValue(editor.activePath).record(value.text)
        fieldValues[editor.activePath] = value
        publish(editor.edit(editor.activePath, value.text))
    }

    fun undo() {
        histories.getValue(editor.activePath).undo()?.let { text ->
            replaceActive(TextFieldValue(text, TextRange(text.length)), record = false)
        }
    }

    fun redo() {
        histories.getValue(editor.activePath).redo()?.let { text ->
            replaceActive(TextFieldValue(text, TextRange(text.length)), record = false)
        }
    }

    fun navigateSearch(backwards: Boolean) {
        val value = fieldValues.getValue(editor.activePath)
        nextEditorMatch(findEditorMatches(value.text, searchQuery), value.selection, backwards)?.let { match ->
            fieldValues[editor.activePath] = value.copy(selection = match)
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

    Scaffold(
        topBar = { TopAppBar(title = { Text("Lean 4 Android") }) },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.F -> { searchVisible = true; true }
                        Key.Z -> { if (event.isShiftPressed) redo() else undo(); true }
                        Key.Y -> { redo(); true }
                        Key.S -> { buildProject(); true }
                        else -> false
                    }
                },
        ) {
            val wide = maxWidth >= 600.dp
            val workspaceModifier = Modifier.fillMaxSize().padding(12.dp)
            if (wide) {
                Row(workspaceModifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EditorFilePanel(
                        editor = editor,
                        running = running,
                        vertical = true,
                        modifier = Modifier.width(250.dp).fillMaxHeight(),
                        onSelect = { publish(editor.select(it)) },
                        onNew = { requestedPath = "New.lean"; fileAction = "New source" },
                        onRename = { requestedPath = editor.activePath; fileAction = "Rename source" },
                        onDelete = { requestedPath = editor.activePath; fileAction = "Delete source" },
                    )
                    EditorContent(
                        modifier = Modifier.weight(1f),
                        editor = editor,
                        value = fieldValues.getValue(editor.activePath),
                        running = running,
                        runState = runState,
                        searchVisible = searchVisible,
                        searchQuery = searchQuery,
                        canUndo = histories.getValue(editor.activePath).canUndo,
                        canRedo = histories.getValue(editor.activePath).canRedo,
                        onValueChange = ::replaceActive,
                        onSearchQuery = { searchQuery = it },
                        onSearchOpen = { searchVisible = true },
                        onSearchClose = { searchVisible = false; searchQuery = "" },
                        onSearchPrevious = { navigateSearch(true) },
                        onSearchNext = { navigateSearch(false) },
                        onUndo = ::undo,
                        onRedo = ::redo,
                        onBuild = ::buildProject,
                        onVerify = { runState = EditorRunState.Running; onVerifyRuntime { runState = it } },
                    )
                }
            } else {
                Column(workspaceModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorFilePanel(
                        editor = editor,
                        running = running,
                        vertical = false,
                        modifier = Modifier.fillMaxWidth(),
                        onSelect = { publish(editor.select(it)) },
                        onNew = { requestedPath = "New.lean"; fileAction = "New source" },
                        onRename = { requestedPath = editor.activePath; fileAction = "Rename source" },
                        onDelete = { requestedPath = editor.activePath; fileAction = "Delete source" },
                    )
                    EditorContent(
                        modifier = Modifier.weight(1f),
                        editor = editor,
                        value = fieldValues.getValue(editor.activePath),
                        running = running,
                        runState = runState,
                        searchVisible = searchVisible,
                        searchQuery = searchQuery,
                        canUndo = histories.getValue(editor.activePath).canUndo,
                        canRedo = histories.getValue(editor.activePath).canRedo,
                        onValueChange = ::replaceActive,
                        onSearchQuery = { searchQuery = it },
                        onSearchOpen = { searchVisible = true },
                        onSearchClose = { searchVisible = false; searchQuery = "" },
                        onSearchPrevious = { navigateSearch(true) },
                        onSearchNext = { navigateSearch(false) },
                        onUndo = ::undo,
                        onRedo = ::redo,
                        onBuild = ::buildProject,
                        onVerify = { runState = EditorRunState.Running; onVerifyRuntime { runState = it } },
                    )
                }
            }
        }
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
                            "Rename source" -> onRenameSource(editor, requestedPath)
                            else -> onDeleteSource(editor)
                        }
                    }.onSuccess {
                        val oldPath = editor.activePath
                        val oldValue = fieldValues[oldPath]
                        val oldHistory = histories[oldPath]
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
    onVerify: () -> Unit,
) {
    val matches = findEditorMatches(value.text, searchQuery)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                TextButton(onClick = onSearchClose) { Text("Close search") }
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .semantics {
                    contentDescription = "Lean source editor for ${editor.activePath}"
                    stateDescription = if (editor.tabs.single { it.path == editor.activePath }.dirty) {
                        "Unsaved changes"
                    } else {
                        "Saved"
                    }
                },
            enabled = !running,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            label = { Text(editor.activePath) },
            visualTransformation = LeanSyntaxVisualTransformation(searchQuery),
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(enabled = !running && canUndo, onClick = onUndo) { Text("Undo") }
            TextButton(enabled = !running && canRedo, onClick = onRedo) { Text("Redo") }
            TextButton(enabled = !running, onClick = onSearchOpen) { Text("Find") }
            Button(enabled = !running, onClick = onBuild) { Text(if (running) "Working…" else "Build project") }
            Button(enabled = !running, onClick = onVerify) { Text("Verify runtime") }
            if (running) CircularProgressIndicator()
        }
        OutputPanel(runState)
    }
}

@Composable
private fun OutputPanel(state: EditorRunState) {
    val output = when (state) {
        EditorRunState.Idle -> "Edit the source and tap Check Lean."
        EditorRunState.Running -> "Working…"
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
