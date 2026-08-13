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
import org.lean4android.process.ProcessCommand
import org.lean4android.process.ProcessResult
import org.lean4android.toolchain.AndroidToolchainLocator
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val DEFAULT_SOURCE = """def greeting : String := "Hello from Lean on Android"

theorem one_plus_one : 1 + 1 = 2 := by
  rfl

#check one_plus_one
#eval greeting
"""

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LeanEditorScreen(onCheck = ::checkLeanSource)
            }
        }
    }

    private fun checkLeanSource(source: String, update: (EditorRunState) -> Unit) {
        thread(name = "lean-editor-check") {
            val state = runCatching {
                val projectDirectory = filesDir.resolve("projects/visual-probe").apply { mkdirs() }
                val sourceFile = projectDirectory.resolve("Main.lean")
                writeAtomically(sourceFile, source)

                val locator = AndroidToolchainLocator(applicationContext)
                locator.installSysroot()
                val layout = when (val health = locator.locate()) {
                    is ToolchainHealth.Ready -> health.layout
                    is ToolchainHealth.Missing -> error(health.problems.joinToString("\n"))
                }

                val started = TimeSource.Monotonic.markNow()
                val result = runBlocking {
                    JvmCommandRunner().run(
                        ProcessCommand(
                            executable = layout.leanExecutable,
                            arguments = listOf(sourceFile.path),
                            workingDirectory = projectDirectory,
                            environment = mapOf(
                                "HOME" to filesDir.path,
                                "TMPDIR" to cacheDir.path,
                                "LEAN_SYSROOT" to layout.sysroot.path,
                                "LD_LIBRARY_PATH" to layout.leanExecutable.parentFile!!.path,
                                "PATH" to "/system/bin",
                            ),
                            timeout = 30.seconds,
                        ),
                    )
                }
                EditorRunState.Finished(result, started.elapsedNow())
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
    data class Failed(val message: String) : EditorRunState
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LeanEditorScreen(onCheck: (String, (EditorRunState) -> Unit) -> Unit) {
    var source by remember { mutableStateOf(DEFAULT_SOURCE) }
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
            Text("Main.lean", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = source,
                onValueChange = { source = it },
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
                    enabled = !running,
                    onClick = {
                        runState = EditorRunState.Running
                        onCheck(source) { runState = it }
                    },
                ) {
                    Text(if (running) "Checking…" else "Check Lean")
                }
                if (running) {
                    CircularProgressIndicator()
                    Text("Installing or checking…")
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
        EditorRunState.Running -> "Waiting for Lean…"
        is EditorRunState.Failed -> "Could not run Lean:\n${state.message}"
        is EditorRunState.Finished -> formatResult(state.result, state.elapsed)
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
