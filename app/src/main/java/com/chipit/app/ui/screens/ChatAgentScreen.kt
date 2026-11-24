package com.chipit.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chipit.app.ui.ChipITViewModel

@Composable
fun ChatAgentScreen(vm: ChipITViewModel) {
    val runtime by vm.activeRuntime.collectAsState()
    val hw by vm.liveHardware.collectAsState()
    val messages by vm.messages.collectAsState()
    val isGenerating by vm.isGenerating.collectAsState()
    val liveTps by vm.liveTokensPerSec.collectAsState()
    val lastTtft by vm.lastTtftMs.collectAsState()
    val agentMode by vm.agentMode.collectAsState()
    val agentState by vm.agentState.collectAsState()

    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // Live Hardware & Inference HUD
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        runtime.model?.name ?: "No model loaded",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        if (runtime.model != null) runtime.config.backend.displayName else "Idle",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    "TTFT: ${lastTtft}ms · Speed: ${"%.1f".format(liveTps)} tok/s · RAM: ${hw.pssRamMb}MB · CPU: ${"%.0f".format(hw.cpuPct)}% · ${"%.1f".format(hw.batteryTempC)}°C (${hw.thermalLabel})",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Agent Mode toggle & Kill Switch
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = agentMode, onCheckedChange = { vm.toggleAgentMode(it) })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("App Interaction Agent", style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (agentState.isRunning) agentState.statusMessage else "Intents → AppFunctions → Accessibility",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (isGenerating || agentState.isRunning) {
                Button(
                    onClick = { vm.stopGenerationOrAgent() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Kill Switch")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Chat / Agent transcript
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = if (msg.isUser) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (msg.isUser) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                        modifier = Modifier.widthIn(max = 310.dp)
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(msg.text.ifEmpty { "..." }, style = MaterialTheme.typography.bodyMedium)
                            msg.meta?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = {
                    Text(if (agentMode) "e.g., Open Spotify and play Lo-Fi Beats" else "Ask local model...")
                },
                modifier = Modifier.weight(1f),
                maxLines = 3
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    val toSend = input
                    input = ""
                    vm.sendPrompt(toSend)
                },
                enabled = !isGenerating && input.isNotBlank()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }

    agentState.pendingConfirmation?.let { req ->
        AlertDialog(
            onDismissRequest = { vm.respondToAgentConfirmation(false) },
            title = { Text("Confirm Agent Action") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Target App: ${req.targetPackage}", fontWeight = FontWeight.SemiBold)
                    Text(req.summary)
                    Text(req.rawJson, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = { vm.respondToAgentConfirmation(true) }) { Text("Approve") }
            },
            dismissButton = {
                TextButton(onClick = { vm.respondToAgentConfirmation(false) }) { Text("Cancel") }
            }
        )
    }
}
