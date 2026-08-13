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
import org.lean4android.toolchain.AndroidToolchainLocator
import kotlin.concurrent.thread

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
                                locator.probe()
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
