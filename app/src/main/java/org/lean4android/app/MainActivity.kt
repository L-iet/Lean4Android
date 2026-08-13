package org.lean4android.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.lean4android.model.ToolchainHealth
import org.lean4android.process.JvmCommandRunner
import org.lean4android.process.ProcessCommand
import org.lean4android.toolchain.AndroidToolchainLocator
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ToolchainProbeScreen(
                    probe = { update ->
                        thread(name = "lean-toolchain-install") {
                            val locator = AndroidToolchainLocator(applicationContext)
                            val report = runCatching {
                                locator.installSysroot()
                                when (val health = locator.locate()) {
                                    is ToolchainHealth.Missing -> locator.probe()
                                    is ToolchainHealth.Ready -> {
                                        val layout = health.layout
                                        val result = runBlocking {
                                            JvmCommandRunner().run(
                                                ProcessCommand(
                                                    executable = layout.leanExecutable,
                                                    arguments = listOf("--version"),
                                                    workingDirectory = filesDir,
                                                    environment = mapOf(
                                                        "HOME" to filesDir.path,
                                                        "LEAN_SYSROOT" to layout.sysroot.path,
                                                        "LD_LIBRARY_PATH" to layout.leanExecutable.parentFile!!.path,
                                                        "PATH" to "/system/bin",
                                                    ),
                                                    timeout = 30.seconds,
                                                ),
                                            )
                                        }
                                        buildString {
                                            append("Ready: ${layout.id.value}\n")
                                            append("lean --version: exit ${result.exitCode}")
                                            if (result.timedOut) append(" (timed out)")
                                            val output = (result.stdout + result.stderr).trim()
                                            if (output.isNotEmpty()) append("\n$output")
                                        }
                                    }
                                }
                            }.getOrElse { "Toolchain installation failed: ${it.message}" }
                            runOnUiThread { update(report) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ToolchainProbeScreen(probe: ((String) -> Unit) -> Unit) {
    var report by remember { mutableStateOf("Toolchain has not been probed.") }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Lean 4 Android") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("M1 feasibility probe", style = MaterialTheme.typography.headlineSmall)
            Text(report, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {
                report = "Installing and checking packaged toolchain…"
                probe { report = it }
            }) {
                Text("Install and inspect toolchain")
            }
        }
    }
}
