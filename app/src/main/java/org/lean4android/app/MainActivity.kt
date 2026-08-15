package org.lean4android.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.ServiceConnection
import android.provider.DocumentsContract
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Switch
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.lean4android.model.ToolchainHealth
import org.lean4android.lsp.DiagnosticBatch
import org.lean4android.lsp.JsonRpcEnvelope
import org.lean4android.lsp.JsonValue
import org.lean4android.lsp.LeanLspEventSink
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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
    private var lspUiState by mutableStateOf(LspUiState())
    @Volatile private var lspStarting = false
    private val nextLspRequestId = AtomicLong(10L)
    private val pendingLspRequests = ConcurrentHashMap<Long, PendingLspRequest>()
    private val latestGoalPositions = ConcurrentHashMap<String, LspPosition>()
    private val leanRpcSessions = ConcurrentHashMap<String, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lspSyncRunnable = Runnable { syncLspDocuments() }
    private val goalRequestRunnables = mutableMapOf<String, Runnable>()
    private var automaticLspRestartAttempts = 0
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
    private val exportPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) {
            android.widget.Toast.makeText(this, "Export cancelled", android.widget.Toast.LENGTH_SHORT).show()
        } else exportProject(uri)
    }
    private val lspConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            lspService = (binder as LeanLspService.LocalBinder).service()
            lspService?.addListener(lspListener)
            ensureLspSession()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            lspService = null
            lspUiState = lspUiState.copy(status = "Disconnected", generation = null)
        }
    }
    private val lspListener = LeanLspService.Listener { snapshot ->
        if (snapshot.projectId != activeProjectId) return@Listener
        runOnUiThread {
            lspUiState = lspUiState.copy(
                status = when (snapshot.state) {
                    LeanLspService.SessionState.Running -> if (snapshot.initialized) "Ready" else "Initializing…"
                    is LeanLspService.SessionState.Stopped -> "Stopped"
                },
                generation = snapshot.generation,
            )
            if (snapshot.initialized) syncLspDocuments()
            if (snapshot.initialized) automaticLspRestartAttempts = 0
            val stopped = snapshot.state as? LeanLspService.SessionState.Stopped
            if (stopped != null && stopped.reason != org.lean4android.lsp.LeanLspSupervisor.StopReason.Closed) {
                scheduleAutomaticLspRestart(snapshot.generation)
            }
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
            var goalsPanePosition by remember {
                mutableStateOf(GoalsPanePosition.fromPreference(getPreferences(MODE_PRIVATE).getString("goalsPanePosition", null)))
            }
            var rightGoalsFraction by remember {
                mutableStateOf(clampPaneFraction(getPreferences(MODE_PRIVATE).getFloat("rightGoalsFraction", 0.34f)))
            }
            var bottomGoalsFraction by remember {
                mutableStateOf(clampPaneFraction(getPreferences(MODE_PRIVATE).getFloat("bottomGoalsFraction", 0.32f)))
            }
            var outputFraction by remember {
                mutableStateOf(clampPaneFraction(getPreferences(MODE_PRIVATE).getFloat("outputFraction", 0.24f)))
            }
            MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
                LeanEditorScreen(
                    initialState = editorState,
                    onStateChanged = {
                        editorState = it
                        editorStore.save(it)
                        scheduleLspSync()
                    },
                    onCreateSource = ::createSource,
                    onOpenSource = ::openSource,
                    onSave = ::saveSource,
                    onSaveAll = ::saveAllSources,
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
                    onNewProject = ::createAndSwitchProject,
                    onExportProject = { exportPicker.launch("${activeProjectId}.lean4android.zip") },
                    darkTheme = darkTheme,
                    onDarkThemeChanged = { enabled ->
                        darkTheme = enabled
                        getPreferences(MODE_PRIVATE).edit().putBoolean("darkTheme", enabled).apply()
                    },
                    lspUiState = lspUiState,
                    onCursorChanged = ::requestGoals,
                    onLspAction = ::requestLspFeature,
                    onRestartLsp = ::restartLsp,
                    goalsPanePosition = goalsPanePosition,
                    onGoalsPanePositionChanged = { position ->
                        goalsPanePosition = position
                        getPreferences(MODE_PRIVATE).edit().putString("goalsPanePosition", position.preferenceValue).apply()
                    },
                    rightGoalsFraction = rightGoalsFraction,
                    onRightGoalsFractionChanged = { fraction ->
                        rightGoalsFraction = clampPaneFraction(fraction)
                        getPreferences(MODE_PRIVATE).edit().putFloat("rightGoalsFraction", rightGoalsFraction).apply()
                    },
                    bottomGoalsFraction = bottomGoalsFraction,
                    onBottomGoalsFractionChanged = { fraction ->
                        bottomGoalsFraction = clampPaneFraction(fraction)
                        getPreferences(MODE_PRIVATE).edit().putFloat("bottomGoalsFraction", bottomGoalsFraction).apply()
                    },
                    outputFraction = outputFraction,
                    onOutputFractionChanged = { fraction ->
                        outputFraction = clampPaneFraction(fraction)
                        getPreferences(MODE_PRIVATE).edit().putFloat("outputFraction", outputFraction).apply()
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
        mainHandler.removeCallbacks(lspSyncRunnable)
        goalRequestRunnables.values.forEach(mainHandler::removeCallbacks)
        goalRequestRunnables.clear()
        editorStore.save(editorState)
        if (lspBound) {
            lspService?.removeListener(lspListener)
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

    private fun ensureLspSession() {
        val service = lspService ?: return
        service.snapshots().singleOrNull { it.projectId == activeProjectId }?.let { snapshot ->
            lspListener.onSessionChanged(snapshot)
            return
        }
        if (lspStarting) return
        lspStarting = true
        lspUiState = lspUiState.copy(status = "Starting…")
        thread(name = "lean-lsp-start") {
            runCatching {
                val locator = AndroidToolchainLocator(applicationContext)
                locator.installSysroot()
                val layout = when (val health = locator.locate()) {
                    is ToolchainHealth.Ready -> health.layout
                    is ToolchainHealth.Missing -> error(health.problems.joinToString("\n"))
                }
                val project = editorRepository().open(activeProjectId)
                val command = ToolchainCommandFactory(layout, filesDir, cacheDir).lakeServer(project.directory)
                requireNotNull(lspService) { "LSP service disconnected during startup" }.startSession(
                    projectId = activeProjectId,
                    rootUri = project.directory.toURI().toString(),
                    command = command,
                    launcher = JvmProcessLauncher(),
                    sink = activityLspSink,
                )
            }.onFailure { failure ->
                runOnUiThread { lspUiState = lspUiState.copy(status = "Failed: ${failure.message}") }
            }
            lspStarting = false
        }
    }

    private val activityLspSink = object : LeanLspEventSink {
        override fun onResponse(response: JsonRpcEnvelope.Response) {
            val responseId = (response.id as? org.lean4android.lsp.JsonRpcId.NumberId)?.source?.toLongOrNull() ?: return
            if (responseId != LeanLspService.INITIALIZE_REQUEST_ID) {
                handleInteractiveResponse(responseId, response)
                return
            }
            runOnUiThread {
                val snapshot = lspService?.snapshots()?.singleOrNull { it.projectId == activeProjectId } ?: return@runOnUiThread
                if (response.error != null) {
                    lspUiState = lspUiState.copy(status = "Initialize failed: ${response.error?.render()}")
                } else runCatching {
                    lspService?.initialized(activeProjectId, snapshot.generation)
                    syncLspDocuments()
                }.onFailure { lspUiState = lspUiState.copy(status = "Initialize failed: ${it.message}") }
            }
        }

        override fun onDiagnostics(batch: DiagnosticBatch<JsonValue.ObjectValue>) {
            runOnUiThread {
                val snapshot = lspService?.snapshots()?.singleOrNull { it.projectId == activeProjectId } ?: return@runOnUiThread
                if (lspUiState.generation != snapshot.generation) return@runOnUiThread
                val messages = batch.diagnostics.mapNotNull { diagnostic ->
                    val message = (diagnostic.fields["message"] as? JsonValue.StringValue)?.value ?: return@mapNotNull null
                    val range = lspRange(diagnostic.fields["range"])
                    LspDiagnosticUi(message, range?.first, range?.second)
                }.take(200)
                lspUiState = lspUiState.copy(diagnostics = lspUiState.diagnostics + (batch.uri to messages))
            }
        }
    }

    private fun requestGoals(path: String, text: String, offset: Int) {
        latestGoalPositions[path] = lspPositionAt(text, offset)
        lspUiState = lspUiState.copy(
            goals = lspUiState.goals + (path to ""),
            termGoals = lspUiState.termGoals + (path to ""),
        )
        goalRequestRunnables.remove(path)?.let(mainHandler::removeCallbacks)
        val request = Runnable {
            goalRequestRunnables.remove(path)
            syncLspDocuments()
            requestLspFeature(path, text, offset, LspRequestKind.Goals)
            requestLspFeature(path, text, offset, LspRequestKind.TermGoal)
            requestInteractiveGoals(path, text, offset)
        }
        goalRequestRunnables[path] = request
        mainHandler.postDelayed(request, 180L)
    }

    private fun requestLspFeature(path: String, text: String, offset: Int, kind: LspRequestKind) {
        val service = lspService ?: return
        val generation = lspUiState.generation ?: return
        if (lspUiState.status != "Ready") return
        val project = runCatching { editorRepository().open(activeProjectId) }.getOrNull() ?: return
        val uri = project.directory.resolve(path).toURI().toString()
        val version = service.documentVersion(activeProjectId, generation, uri) ?: return
        val position = lspPositionAt(text, offset)
        val requestId = nextLspRequestId.getAndIncrement()
        pendingLspRequests.entries.removeIf { (_, pending) -> pending.kind == kind && pending.uri == uri }
        pendingLspRequests[requestId] = PendingLspRequest(generation, uri, version, path, kind, position)
        val payload = when (kind) {
            LspRequestKind.Goals -> org.lean4android.lsp.LeanLspRequests.plainGoal(requestId, uri, position.line, position.character)
            LspRequestKind.TermGoal -> org.lean4android.lsp.LeanLspRequests.plainTermGoal(requestId, uri, position.line, position.character)
            LspRequestKind.Hover -> org.lean4android.lsp.LeanLspRequests.hover(requestId, uri, position.line, position.character)
            LspRequestKind.Completion -> org.lean4android.lsp.LeanLspRequests.completion(requestId, uri, position.line, position.character)
            LspRequestKind.Definition -> org.lean4android.lsp.LeanLspRequests.definition(requestId, uri, position.line, position.character)
            LspRequestKind.References -> org.lean4android.lsp.LeanLspRequests.references(requestId, uri, position.line, position.character)
            LspRequestKind.RpcConnect, LspRequestKind.InteractiveGoals -> error("Internal Lean RPC requests use their dedicated flow")
        }
        runCatching {
            service.request(activeProjectId, generation, payload)
        }.onFailure { pendingLspRequests.remove(requestId) }
    }

    private fun requestInteractiveGoals(path: String, text: String, offset: Int) {
        val service = lspService ?: return
        val generation = lspUiState.generation ?: return
        if (lspUiState.status != "Ready") return
        val project = runCatching { editorRepository().open(activeProjectId) }.getOrNull() ?: return
        val uri = project.directory.resolve(path).toURI().toString()
        val version = service.documentVersion(activeProjectId, generation, uri) ?: return
        val position = lspPositionAt(text, offset)
        val rpcKey = "$generation:$uri"
        val sessionId = leanRpcSessions[rpcKey]
        if (sessionId == null) {
            if (pendingLspRequests.values.any { it.kind == LspRequestKind.RpcConnect && it.uri == uri }) return
            val requestId = nextLspRequestId.getAndIncrement()
            pendingLspRequests[requestId] = PendingLspRequest(
                generation, uri, version, path, LspRequestKind.RpcConnect, position,
            )
            runCatching {
                service.request(activeProjectId, generation, org.lean4android.lsp.LeanLspRequests.rpcConnect(requestId, uri))
            }.onFailure { pendingLspRequests.remove(requestId) }
        } else sendInteractiveGoals(service, generation, sessionId, PendingLspRequest(
            generation, uri, version, path, LspRequestKind.InteractiveGoals, position,
        ))
    }

    private fun sendInteractiveGoals(
        service: LeanLspService,
        generation: Long,
        sessionId: Long,
        context: PendingLspRequest,
    ) {
        val requestId = nextLspRequestId.getAndIncrement()
        pendingLspRequests.entries.removeIf { (_, pending) -> pending.kind == LspRequestKind.InteractiveGoals && pending.uri == context.uri }
        pendingLspRequests[requestId] = context.copy(kind = LspRequestKind.InteractiveGoals)
        runCatching {
            service.request(
                activeProjectId,
                generation,
                org.lean4android.lsp.LeanLspRequests.interactiveGoals(
                    requestId, context.uri, context.position.line, context.position.character, sessionId,
                ),
            )
        }.onFailure { pendingLspRequests.remove(requestId) }
    }

    private fun handleInteractiveResponse(requestId: Long, response: JsonRpcEnvelope.Response) {
        val pending = pendingLspRequests.remove(requestId) ?: return
        runOnUiThread {
            val service = lspService ?: return@runOnUiThread
            if (lspUiState.generation != pending.generation ||
                service.documentVersion(activeProjectId, pending.generation, pending.uri) != pending.version
            ) return@runOnUiThread
            if (pending.kind in setOf(LspRequestKind.Goals, LspRequestKind.TermGoal, LspRequestKind.InteractiveGoals) &&
                latestGoalPositions[pending.path] != pending.position
            ) return@runOnUiThread
            when (pending.kind) {
                LspRequestKind.Goals -> {
                    val rendered = plainGoalText(response.result).ifBlank {
                        response.error?.let { "Goals unavailable: ${it.render()}" }.orEmpty()
                    }
                    lspUiState = lspUiState.copy(goals = lspUiState.goals + (pending.path to rendered))
                }
                LspRequestKind.TermGoal -> {
                    val rendered = plainTermGoalText(response.result)
                    lspUiState = lspUiState.copy(termGoals = lspUiState.termGoals + (pending.path to rendered))
                }
                LspRequestKind.Hover -> lspUiState = lspUiState.copy(
                    hover = jsonDisplayText(response.result).ifBlank { "No hover information" },
                )
                LspRequestKind.Completion -> {
                    val result = response.result
                    val values = when (result) {
                        is JsonValue.ArrayValue -> result.values
                        is JsonValue.ObjectValue -> (result.fields["items"] as? JsonValue.ArrayValue)?.values.orEmpty()
                        else -> emptyList()
                    }
                    val labels = values.mapNotNull { value ->
                        ((value as? JsonValue.ObjectValue)?.fields?.get("label") as? JsonValue.StringValue)?.value
                    }.take(100)
                    lspUiState = lspUiState.copy(completions = labels)
                }
                LspRequestKind.Definition -> {
                    val location = firstLocation(response.result)
                    lspUiState = lspUiState.copy(
                        definition = location?.toDefinitionTarget(),
                        navigationMessage = location?.let { "Definition: ${it.first}:${it.second.first + 1}:${it.second.second + 1}" }
                            ?: "No definition found",
                    )
                }
                LspRequestKind.References -> {
                    val locations = lspLocations(response.result).take(100)
                    lspUiState = lspUiState.copy(
                        references = locations.map { (uri, position) -> "$uri:${position.first + 1}:${position.second + 1}" },
                    )
                }
                LspRequestKind.RpcConnect -> {
                    val sessionId = ((response.result as? JsonValue.ObjectValue)?.fields?.get("sessionId") as? JsonValue.NumberValue)
                        ?.source?.toLongOrNull()
                    if (sessionId != null) {
                        leanRpcSessions["${pending.generation}:${pending.uri}"] = sessionId
                        sendInteractiveGoals(service, pending.generation, sessionId, pending)
                    }
                }
                LspRequestKind.InteractiveGoals -> {
                    val rendered = renderInteractiveGoals(response.result)
                    if (rendered.isNotBlank()) {
                        lspUiState = lspUiState.copy(goals = lspUiState.goals + (pending.path to rendered))
                    }
                }
            }
        }
    }

    private fun Pair<String, Pair<Int, Int>>.toDefinitionTarget(): DefinitionTarget? {
        val project = runCatching { editorRepository().open(activeProjectId) }.getOrNull() ?: return null
        val file = runCatching { java.io.File(java.net.URI(first)).canonicalFile }.getOrNull() ?: return null
        if (!file.toPath().startsWith(project.directory.canonicalFile.toPath())) return null
        return DefinitionTarget(file.relativeTo(project.directory).invariantSeparatorsPath, second.first, second.second)
    }

    private fun syncLspDocuments() {
        val service = lspService ?: return
        val generation = lspUiState.generation ?: return
        val project = runCatching { editorRepository().open(activeProjectId) }.getOrNull() ?: return
        val buffers = editorState.tabs.associate { tab ->
            project.directory.resolve(tab.path).toURI().toString() to tab.contents
        }
        runCatching { service.synchronizeDocuments(activeProjectId, generation, buffers) }
    }

    private fun scheduleLspSync() {
        mainHandler.removeCallbacks(lspSyncRunnable)
        mainHandler.postDelayed(lspSyncRunnable, 120L)
    }

    private fun restartLsp() {
        val service = lspService ?: return
        service.stopSession(activeProjectId)
        pendingLspRequests.clear()
        leanRpcSessions.clear()
        lspUiState = LspUiState(status = "Restarting…")
        lspStarting = false
        ensureLspSession()
    }

    private fun scheduleAutomaticLspRestart(failedGeneration: Long) {
        if (automaticLspRestartAttempts >= 3) {
            lspUiState = lspUiState.copy(status = "Stopped after 3 restart attempts")
            return
        }
        val delayMillis = 500L shl automaticLspRestartAttempts
        automaticLspRestartAttempts++
        lspUiState = lspUiState.copy(status = "Restarting in ${delayMillis} ms…")
        mainHandler.postDelayed({
            val service = lspService ?: return@postDelayed
            val current = service.snapshots().singleOrNull { it.projectId == activeProjectId } ?: return@postDelayed
            if (current.generation != failedGeneration || current.state !is LeanLspService.SessionState.Stopped) return@postDelayed
            service.stopSession(activeProjectId)
            pendingLspRequests.clear()
            leanRpcSessions.clear()
            lspStarting = false
            ensureLspSession()
        }, delayMillis)
    }

    private fun switchProject(id: String) {
        editorStore.save(editorState)
        val recent = (listOf(id) + recentProjects()).distinct().take(10)
        getPreferences(MODE_PRIVATE).edit().putString("activeProject", id).putString("recentProjects", recent.joinToString("\n")).apply()
        recreate()
    }

    private fun recentProjects(): List<String> = getPreferences(MODE_PRIVATE).getString("recentProjects", "")
        .orEmpty().lineSequence().filter(String::isNotBlank).filter { runCatching { editorRepository().open(it) }.isSuccess }.toList()

    private fun createAndSwitchProject(name: String): String? {
        val id = LeanProjectRepository.normalizeProjectId(name)
        return runCatching {
            require(id.isNotBlank()) { "Enter a project name" }
            editorRepository().create(id)
            mainHandler.post { switchProject(id) }
        }.exceptionOrNull()?.message ?: return null
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

    private fun exportProject(uri: Uri) {
        thread(name = "project-export") {
            val result = runCatching {
                contentResolver.openOutputStream(uri, "w").use { output ->
                    editorRepository().export(activeProjectId, requireNotNull(output) { "The selected provider cannot be opened" })
                }
            }
            if (result.isFailure) runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
            runOnUiThread {
                val message = result.fold(
                    onSuccess = { "Project exported" },
                    onFailure = { "Export failed: ${it.message ?: it::class.java.simpleName}" },
                )
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
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
        val saved = state.markSaved(activePath)
        lspUiState.generation?.let { generation ->
            runCatching {
                syncLspDocuments()
                val uri = editorRepository().open(state.projectId).directory.resolve(activePath).toURI().toString()
                lspService?.didSave(state.projectId, generation, uri, tab.contents)
            }
        }
        return saved
    }

    private fun saveAllSources(state: EditorSessionState): EditorSessionState {
        state.tabs.filter(EditorTab::dirty).forEach { tab ->
            editorRepository().save(state.projectId, tab.path, tab.contents)
        }
        return state.markSaved()
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

private data class LspUiState(
    val status: String = "Disconnected",
    val generation: Long? = null,
    val diagnostics: Map<String, List<LspDiagnosticUi>> = emptyMap(),
    val goals: Map<String, String> = emptyMap(),
    val termGoals: Map<String, String> = emptyMap(),
    val hover: String = "",
    val completions: List<String> = emptyList(),
    val definition: DefinitionTarget? = null,
    val navigationMessage: String = "",
    val references: List<String> = emptyList(),
)

private data class LspDiagnosticUi(val message: String, val start: LspPosition?, val end: LspPosition?)

private enum class LspRequestKind { Goals, TermGoal, Hover, Completion, Definition, References, RpcConnect, InteractiveGoals }

private data class DefinitionTarget(val path: String, val line: Int, val character: Int)

private data class PendingLspRequest(
    val generation: Long,
    val uri: String,
    val version: Int,
    val path: String,
    val kind: LspRequestKind,
    val position: LspPosition,
)

private fun jsonDisplayText(value: JsonValue?): String = when (value) {
    null, JsonValue.NullValue -> ""
    is JsonValue.StringValue -> value.value
    is JsonValue.ArrayValue -> value.values.joinToString("\n") { jsonDisplayText(it) }.trim()
    is JsonValue.ObjectValue -> {
        listOf("value", "contents", "text", "rendered")
            .firstNotNullOfOrNull { key -> value.fields[key]?.let(::jsonDisplayText)?.takeIf(String::isNotBlank) }
            ?: value.fields.values.joinToString(" ") { jsonDisplayText(it) }.trim()
    }
    is JsonValue.NumberValue -> value.source
    is JsonValue.BooleanValue -> value.value.toString()
}

internal fun lspLocations(value: JsonValue?): List<Pair<String, Pair<Int, Int>>> {
    val values = if (value is JsonValue.ArrayValue) value.values else listOfNotNull(value)
    return values.mapNotNull { entry ->
        val fields = (entry as? JsonValue.ObjectValue)?.fields ?: return@mapNotNull null
        val uri = ((fields["uri"] ?: fields["targetUri"]) as? JsonValue.StringValue)?.value ?: return@mapNotNull null
        val range = ((fields["range"] ?: fields["targetSelectionRange"] ?: fields["targetRange"])
            as? JsonValue.ObjectValue)?.fields ?: return@mapNotNull null
        val start = (range["start"] as? JsonValue.ObjectValue)?.fields ?: return@mapNotNull null
        val line = (start["line"] as? JsonValue.NumberValue)?.source?.toIntOrNull() ?: return@mapNotNull null
        val character = (start["character"] as? JsonValue.NumberValue)?.source?.toIntOrNull() ?: return@mapNotNull null
        uri to (line to character)
    }
}

private fun lspRange(value: JsonValue?): Pair<LspPosition, LspPosition>? {
    val fields = (value as? JsonValue.ObjectValue)?.fields ?: return null
    fun position(name: String): LspPosition? {
        val position = (fields[name] as? JsonValue.ObjectValue)?.fields ?: return null
        val line = (position["line"] as? JsonValue.NumberValue)?.source?.toIntOrNull() ?: return null
        val character = (position["character"] as? JsonValue.NumberValue)?.source?.toIntOrNull() ?: return null
        return LspPosition(line, character)
    }
    return (position("start") ?: return null) to (position("end") ?: return null)
}

private fun firstLocation(value: JsonValue?): Pair<String, Pair<Int, Int>>? = lspLocations(value).firstOrNull()

private fun renderInteractiveGoals(value: JsonValue?): String {
    val goals = ((value as? JsonValue.ObjectValue)?.fields?.get("goals") as? JsonValue.ArrayValue)?.values.orEmpty()
    return goals.mapNotNull { goalValue ->
        val goal = (goalValue as? JsonValue.ObjectValue)?.fields ?: return@mapNotNull null
        val lines = mutableListOf<String>()
        (goal["userName?"] as? JsonValue.StringValue)?.value?.takeIf(String::isNotBlank)?.let { lines += "case $it" }
        val hypotheses = (goal["hyps"] as? JsonValue.ArrayValue)?.values.orEmpty()
        hypotheses.forEach { hypothesisValue ->
            val hypothesis = (hypothesisValue as? JsonValue.ObjectValue)?.fields ?: return@forEach
            val names = (hypothesis["names"] as? JsonValue.ArrayValue)?.values.orEmpty()
                .mapNotNull { (it as? JsonValue.StringValue)?.value }
                .filter(String::isNotBlank)
                .joinToString(" ")
            val type = taggedText(hypothesis["type"])
            if (names.isNotBlank() || type.isNotBlank()) lines += "$names : $type".trim()
        }
        val prefix = (goal["goalPrefix"] as? JsonValue.StringValue)?.value ?: "⊢ "
        val type = taggedText(goal["type"])
        if (type.isNotBlank()) lines += prefix + type
        lines.joinToString("\n").takeIf(String::isNotBlank)
    }.joinToString("\n\n")
}

private fun taggedText(value: JsonValue?): String = when (value) {
    is JsonValue.StringValue -> value.value
    is JsonValue.ArrayValue -> value.values.joinToString("") { taggedText(it) }
    is JsonValue.ObjectValue -> when {
        "text" in value.fields -> taggedText(value.fields["text"])
        "append" in value.fields -> taggedText(value.fields["append"])
        "tag" in value.fields -> {
            val tagged = value.fields["tag"]
            if (tagged is JsonValue.ArrayValue) tagged.values.lastOrNull()?.let(::taggedText).orEmpty()
            else taggedText(tagged)
        }
        else -> ""
    }
    else -> ""
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LeanEditorScreen(
    initialState: EditorSessionState,
    onStateChanged: (EditorSessionState) -> Unit,
    onCreateSource: (EditorSessionState, String) -> EditorSessionState,
    onOpenSource: (EditorSessionState, String) -> EditorSessionState,
    onSave: (EditorSessionState) -> EditorSessionState,
    onSaveAll: (EditorSessionState) -> EditorSessionState,
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
    onNewProject: (String) -> String?,
    onExportProject: () -> Unit,
    darkTheme: Boolean,
    onDarkThemeChanged: (Boolean) -> Unit,
    lspUiState: LspUiState,
    onCursorChanged: (String, String, Int) -> Unit,
    onLspAction: (String, String, Int, LspRequestKind) -> Unit,
    onRestartLsp: () -> Unit,
    goalsPanePosition: GoalsPanePosition,
    onGoalsPanePositionChanged: (GoalsPanePosition) -> Unit,
    rightGoalsFraction: Float,
    onRightGoalsFractionChanged: (Float) -> Unit,
    bottomGoalsFraction: Float,
    onBottomGoalsFractionChanged: (Float) -> Unit,
    outputFraction: Float,
    onOutputFractionChanged: (Float) -> Unit,
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
    var newProjectDialog by rememberSaveable { mutableStateOf(false) }
    var newProjectName by rememberSaveable { mutableStateOf("") }
    var newProjectError by rememberSaveable { mutableStateOf<String?>(null) }
    var closeDirty by remember { mutableStateOf(false) }
    var exportDirty by remember { mutableStateOf(false) }
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
            "appearance", "editor" -> settingsPage = "settings"
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

    fun requestLsp(kind: LspRequestKind) {
        val path = editor.activePath ?: return
        val value = fieldValues[path] ?: return
        onLspAction(path, value.text, value.selection.start, kind)
    }

    LaunchedEffect(lspUiState.definition) {
        val target = lspUiState.definition ?: return@LaunchedEffect
        if (target.path !in projectFiles) return@LaunchedEffect
        val next = onOpenSource(editor, target.path)
        val tab = next.tabs.single { it.path == target.path }
        val offset = offsetAtLspPosition(tab.contents, LspPosition(target.line, target.character))
        fieldValues[target.path] = TextFieldValue(tab.contents, TextRange(offset))
        histories.putIfAbsent(target.path, EditorUndoHistory(tab.contents))
        publish(next.select(target.path))
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
                            DropdownMenuItem(text = { Text("▣  New Project") }, enabled = !running, onClick = {
                                filesMenu = false; newProjectName = ""; newProjectError = null; newProjectDialog = true
                            })
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
                            DropdownMenuItem(text = { Text("ⓘ  Hover") }, enabled = lspUiState.status == "Ready" && editor.activePath != null, onClick = { moreMenu = false; requestLsp(LspRequestKind.Hover) })
                            DropdownMenuItem(text = { Text("≡  Complete") }, enabled = lspUiState.status == "Ready" && editor.activePath != null, onClick = { moreMenu = false; requestLsp(LspRequestKind.Completion) })
                            DropdownMenuItem(text = { Text("→  Go to definition") }, enabled = lspUiState.status == "Ready" && editor.activePath != null, onClick = { moreMenu = false; requestLsp(LspRequestKind.Definition) })
                            DropdownMenuItem(text = { Text("↔  Find references") }, enabled = lspUiState.status == "Ready" && editor.activePath != null, onClick = { moreMenu = false; requestLsp(LspRequestKind.References) })
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
            val workspaceModifier = Modifier.fillMaxSize().padding(12.dp)
            val density = LocalDensity.current
            val workspaceHeightPixels = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
            val editorContent: @Composable (Modifier) -> Unit = { contentModifier ->
                EditorContent(
                    modifier = contentModifier,
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
                    lspUiState = lspUiState,
                    onCursorChanged = onCursorChanged,
                    onHover = { path, text, offset -> onLspAction(path, text, offset, LspRequestKind.Hover) },
                    hoverEnabled = lspUiState.status == "Ready",
                    outputFraction = outputFraction,
                    onOutputDrag = { pixels -> onOutputFractionChanged(outputFraction - pixels / workspaceHeightPixels) },
                    onOutputStep = { onOutputFractionChanged(outputFraction + it) },
                )
            }
            val resolvedGoalsPosition = goalsPanePosition.resolve(isPortrait = maxHeight >= maxWidth)
            if (resolvedGoalsPosition == GoalsPanePosition.Right) {
                val availablePixels = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                Row(workspaceModifier) {
                    editorContent(Modifier.weight(1f - rightGoalsFraction))
                    PaneSplitter(
                        vertical = true,
                        paneName = "Goals pane",
                        fraction = rightGoalsFraction,
                        onDrag = { pixels -> onRightGoalsFractionChanged(rightGoalsFraction - pixels / availablePixels) },
                        onStep = { onRightGoalsFractionChanged(rightGoalsFraction + it) },
                    )
                    GoalsPanel(Modifier.weight(rightGoalsFraction), editor.activePath, lspUiState)
                }
            } else {
                val availablePixels = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
                Column(workspaceModifier) {
                    editorContent(Modifier.weight(1f - bottomGoalsFraction))
                    PaneSplitter(
                        vertical = false,
                        paneName = "Goals pane",
                        fraction = bottomGoalsFraction,
                        onDrag = { pixels -> onBottomGoalsFractionChanged(bottomGoalsFraction - pixels / availablePixels) },
                        onStep = { onBottomGoalsFractionChanged(bottomGoalsFraction + it) },
                    )
                    GoalsPanel(Modifier.weight(bottomGoalsFraction), editor.activePath, lspUiState)
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
                        TextButton(onClick = { drawerOpen = false; onRestartLsp() }) { Text("Restart Lean server") }
                        TextButton(
                            enabled = !running,
                            modifier = Modifier.semantics { contentDescription = "Export active project" },
                            onClick = {
                                drawerOpen = false
                                if (editor.tabs.any(EditorTab::dirty)) exportDirty = true else onExportProject()
                            },
                        ) { Text("Export project") }
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
                TextButton(onClick = {
                    openWorkspace = false; newProjectName = ""; newProjectError = null; newProjectDialog = true
                }) { Text("New Project") }
                Text("Projects are app-managed copies and are removed on uninstall. Export important work.", style = MaterialTheme.typography.bodySmall)
            } }
        }
    }

    if (newProjectDialog) {
        AlertDialog(
            onDismissRequest = { newProjectDialog = false },
            title = { Text("New Project") },
            text = {
                OutlinedTextField(
                    value = newProjectName,
                    onValueChange = { newProjectName = it; newProjectError = null },
                    singleLine = true,
                    label = { Text("Project name") },
                    isError = newProjectError != null,
                    supportingText = {
                        Text(newProjectError ?: "Letters, numbers, dots, underscores, and hyphens; spaces become hyphens.")
                    },
                    modifier = Modifier.semantics { contentDescription = "Project name" },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newProjectName.isNotBlank(),
                    onClick = {
                        newProjectError = onNewProject(newProjectName)
                        if (newProjectError == null) newProjectDialog = false
                    },
                ) { Text("Create project") }
            },
            dismissButton = { TextButton(onClick = { newProjectDialog = false }) { Text("Cancel") } },
        )
    }

    settingsPage?.let { page ->
        Dialog(onDismissRequest = { settingsPage = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { settingsPage = if (page == "settings") null else "settings" }) { Text("Back") }
                        Text(when (page) { "appearance" -> "Appearance"; "editor" -> "Editor"; else -> "Settings" }, style = MaterialTheme.typography.headlineSmall)
                    }
                    if (page == "settings") {
                        TextButton(
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Open Appearance settings" },
                            onClick = { settingsPage = "appearance" },
                        ) { Text("Appearance") }
                        TextButton(
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Open Editor settings" },
                            onClick = { settingsPage = "editor" },
                        ) { Text("Editor") }
                    } else if (page == "appearance") {
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
                    } else {
                        Text("Goals pane position", style = MaterialTheme.typography.titleMedium)
                        listOf(
                            GoalsPanePosition.Auto to "Auto",
                            GoalsPanePosition.Right to "Right side",
                            GoalsPanePosition.Bottom to "Bottom",
                        ).forEach { (position, label) ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { onGoalsPanePositionChanged(position) }
                                    .padding(vertical = 8.dp)
                                    .semantics { contentDescription = "Goals pane position: $label" },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = goalsPanePosition == position, onClick = { onGoalsPanePositionChanged(position) })
                                Column {
                                    Text(label)
                                    if (position == GoalsPanePosition.Auto) {
                                        Text("Bottom in portrait, right side in landscape", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
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

    if (exportDirty) {
        AlertDialog(
            onDismissRequest = { exportDirty = false },
            title = { Text("Export unsaved project?") },
            text = { Text("Choose whether the portable archive should include all current editor changes or only the last saved project files.") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { onSaveAll(editor) }
                        .onSuccess { publish(it); exportDirty = false; onExportProject() }
                        .onFailure { runState = EditorRunState.Failed("Save failed: ${it.message.orEmpty()}") }
                }) { Text("Save and Export") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { exportDirty = false; onExportProject() }) { Text("Export saved version") }
                    TextButton(onClick = { exportDirty = false }) { Text("Cancel") }
                }
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
    lspUiState: LspUiState,
    onCursorChanged: (String, String, Int) -> Unit,
    onHover: (String, String, Int) -> Unit,
    hoverEnabled: Boolean,
    outputFraction: Float,
    onOutputDrag: (Float) -> Unit,
    onOutputStep: (Float) -> Unit,
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
    val activeDiagnostics = lspUiState.diagnostics.entries
        .firstOrNull { (uri, _) -> uri.endsWith("/${editor.activePath}") }
        ?.value.orEmpty()
    val diagnosticRanges = activeDiagnostics.mapNotNull { diagnostic ->
        val start = diagnostic.start ?: return@mapNotNull null
        val end = diagnostic.end ?: return@mapNotNull null
        TextRange(offsetAtLspPosition(value.text, start), offsetAtLspPosition(value.text, end))
    }
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
        Surface(Modifier.fillMaxWidth().weight(1f - outputFraction), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
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
                var sourceModifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .semantics {
                        contentDescription = "Lean source editor for ${editor.activePath}"
                        stateDescription = if (editor.tabs.single { it.path == editor.activePath }.dirty) "Unsaved changes" else "Saved"
                    }
                if (hoverEnabled) {
                    sourceModifier = sourceModifier.appendTextContextMenuComponents {
                        item(HoverContextMenuKey, "Hover") {
                            onHover(editor.activePath, value.text, value.selection.start)
                            close()
                        }
                    }
                }
                BasicTextField(
                    value = value,
                    onValueChange = {
                        onValueChange(it)
                        onCursorChanged(editor.activePath, it.text, it.selection.start)
                    },
                    modifier = sourceModifier,
                    enabled = !running,
                    textStyle = editorStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    visualTransformation = LeanSyntaxVisualTransformation(searchQuery, diagnosticRanges),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                )
            }
        }
        if (running) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator()
            Text("Working…")
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        if (activeDiagnostics.isNotEmpty()) {
            Surface(
                Modifier.fillMaxWidth().heightIn(max = 160.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Column(Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
                    Text("Messages", style = MaterialTheme.typography.titleSmall)
                    activeDiagnostics.forEach { Text(it.message, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        PaneSplitter(
            vertical = false,
            paneName = "Output panel",
            fraction = outputFraction,
            onDrag = onOutputDrag,
            onStep = onOutputStep,
        )
        OutputPanel(runState, Modifier.weight(outputFraction))
    }
}

@Composable
private fun GoalsPanel(modifier: Modifier, activePath: String?, lspUiState: LspUiState) {
    Surface(
        modifier = modifier.semantics { contentDescription = "Goals pane" },
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp).verticalScroll(rememberScrollState())) {
            Text("Goals", style = MaterialTheme.typography.titleSmall)
            Text("Lean server: ${lspUiState.status}", style = MaterialTheme.typography.labelSmall)
            val sections = activePath?.let { goalPaneSections(lspUiState.goals[it], lspUiState.termGoals[it]) }.orEmpty()
            if (sections.isEmpty()) {
                Text(if (lspUiState.status == "Ready") "Move the cursor to inspect goals." else "Waiting for Lean server…")
            } else {
                sections.forEach { (title, content) ->
                    title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                    Text(content, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
            if (lspUiState.hover.isNotBlank()) {
                Text("Hover", style = MaterialTheme.typography.titleSmall)
                HoverMarkdown(lspUiState.hover)
            }
            if (lspUiState.completions.isNotEmpty()) {
                Text("Completions", style = MaterialTheme.typography.titleSmall)
                Text(lspUiState.completions.joinToString("  "), style = MaterialTheme.typography.bodySmall)
            }
            if (lspUiState.navigationMessage.isNotBlank()) {
                Text(lspUiState.navigationMessage, style = MaterialTheme.typography.bodySmall)
            }
            if (lspUiState.references.isNotEmpty()) {
                Text("References", style = MaterialTheme.typography.titleSmall)
                lspUiState.references.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private object HoverContextMenuKey

internal fun goalPaneSections(goal: String?, expectedType: String?): List<Pair<String?, String>> {
    val tactic = goal.orEmpty().trim().takeUnless { it.isEmpty() || it.equals("No goals", ignoreCase = true) }
    val term = expectedType.orEmpty().trim().takeUnless { it.isEmpty() || it.equals("No goals", ignoreCase = true) }
    return buildList {
        tactic?.let { add("Goals" to it) }
        term?.let { add("Expected type" to it) }
        if (isEmpty()) add(null to "No goals")
    }
}

internal fun plainTermGoalText(value: JsonValue?): String =
    (((value as? JsonValue.ObjectValue)?.fields?.get("goal")) as? JsonValue.StringValue)?.value.orEmpty()

internal fun plainGoalText(value: JsonValue?): String {
    val goals = ((((value as? JsonValue.ObjectValue)?.fields?.get("goals")) as? JsonValue.ArrayValue)?.values).orEmpty()
    return goals.mapNotNull { (it as? JsonValue.StringValue)?.value?.takeIf(String::isNotBlank) }.joinToString("\n\n")
}

@Composable
private fun PaneSplitter(
    vertical: Boolean,
    paneName: String,
    fraction: Float,
    onDrag: (Float) -> Unit,
    onStep: (Float) -> Unit,
) {
    val orientation = if (vertical) Orientation.Horizontal else Orientation.Vertical
    val modifier = (if (vertical) Modifier.width(12.dp).fillMaxHeight() else Modifier.height(12.dp).fillMaxWidth())
        .draggable(rememberDraggableState(onDelta = onDrag), orientation)
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionLeft, Key.DirectionUp -> { onStep(0.05f); true }
                Key.DirectionRight, Key.DirectionDown -> { onStep(-0.05f); true }
                else -> false
            }
        }
        .focusable()
        .semantics {
            contentDescription = "Resize $paneName ${if (vertical) "width" else "height"}"
            stateDescription = "${(fraction * 100).toInt()} percent"
            customActions = listOf(
                CustomAccessibilityAction("Make $paneName larger") { onStep(0.05f); true },
                CustomAccessibilityAction("Make $paneName smaller") { onStep(-0.05f); true },
            )
        }
        .background(MaterialTheme.colorScheme.outlineVariant)
    androidx.compose.foundation.layout.Box(modifier)
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
private fun OutputPanel(state: EditorRunState, modifier: Modifier = Modifier.heightIn(min = 100.dp, max = 200.dp)) {
    val output = when (state) {
        EditorRunState.Idle -> "Edit the source, then use Run or the Project drawer actions."
        EditorRunState.Running -> "Working…"
        EditorRunState.Cancelled -> "Run cancelled."
        is EditorRunState.Failed -> "Could not run Lean:\n${state.message}"
        is EditorRunState.Finished -> formatResult(state.result, state.elapsed)
        is EditorRunState.IntegrityFinished -> formatIntegrityResult(state.problems, state.elapsed)
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
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
