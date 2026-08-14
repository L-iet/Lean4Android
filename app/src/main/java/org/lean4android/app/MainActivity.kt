package org.lean4android.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LeanEditorScreen(
                    onCheck = ::checkLeanSource,
                    onVerifyRuntime = ::verifyRuntime,
                )
            }
        }
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
    onCheck: (Map<String, String>, (EditorRunState) -> Unit) -> Unit,
    onVerifyRuntime: ((EditorRunState) -> Unit) -> Unit,
) {
    var sources by remember {
        mutableStateOf(mapOf("Main.lean" to MAIN_SOURCE, "VisualProbe/Basic.lean" to LIBRARY_SOURCE))
    }
    var activePath by remember { mutableStateOf("Main.lean") }
    var runState by remember { mutableStateOf<EditorRunState>(EditorRunState.Idle) }
    val running = runState == EditorRunState.Running

    Scaffold(
        topBar = { TopAppBar(title = { Text("Lean 4 Android") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sources.keys.forEach { path ->
                    Button(onClick = { activePath = path }, enabled = !running && activePath != path) {
                        Text(path.substringAfterLast('/'))
                    }
                }
            }
            OutlinedTextField(
                value = sources.getValue(activePath),
                onValueChange = { sources = sources + (activePath to it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                enabled = !running,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                label = { Text("Lean source") },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !running,
                    onClick = {
                        runState = EditorRunState.Running
                        onCheck(sources) { runState = it }
                    },
                ) {
                    Text(if (running) "Working…" else "Build project")
                }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !running,
                    onClick = {
                        runState = EditorRunState.Running
                        onVerifyRuntime { runState = it }
                    },
                ) {
                    Text("Verify runtime")
                }
                if (running) {
                    CircularProgressIndicator()
                }
            }
            OutputPanel(runState)
        }
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
            .heightIn(min = 120.dp, max = 240.dp),
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
