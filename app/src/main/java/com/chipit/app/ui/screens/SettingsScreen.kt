package com.chipit.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chipit.app.agent.accessibility.AccessibilityBridge
import com.chipit.app.ui.ChipITViewModel

@Composable
fun SettingsScreen(vm: ChipITViewModel) {
    val context = LocalContext.current
    val store = remember { vm.getSettingsStore() }
    val accConnected by AccessibilityBridge.isConnected.collectAsState()

    var hfToken by remember { mutableStateOf(store.hfToken.orEmpty()) }
    var maxSteps by remember { mutableIntStateOf(store.agentMaxSteps) }
    var allowedPackages by remember { mutableStateOf(store.allowedAgentPackages) }
    var newPkg by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Accessibility & Sideload Onboarding", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (accConnected) "AccessibilityService: Connected" else "AccessibilityService: Not Enabled",
                        fontWeight = FontWeight.SemiBold,
                        color = if (accConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        "On Android 13+ sideloaded APKs, open App Info → ⋮ → Allow restricted settings before enabling ChipIT in Accessibility Settings.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }) { Text("App Info") }
                        Button(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }) { Text("Accessibility Settings") }
                    }
                }
            }
        }

        item {
            Text("Agent Guardrails", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Max Agent Steps: $maxSteps", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = maxSteps.toFloat(),
                onValueChange = {
                    maxSteps = it.toInt()
                    store.agentMaxSteps = maxSteps
                },
                valueRange = 1f..25f
            )
        }

        item {
            Text("Allowed Apps (Tier 3 Accessibility)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            allowedPackages.sorted().forEach { pkg ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(pkg, style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = true,
                        onCheckedChange = { checked ->
                            store.togglePackageAllowed(pkg, checked)
                            allowedPackages = store.allowedAgentPackages
                        }
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newPkg,
                    onValueChange = { newPkg = it },
                    label = { Text("Add package (e.g. com.whatsapp)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val trimmed = newPkg.trim()
                    if (trimmed.isNotEmpty()) {
                        store.togglePackageAllowed(trimmed, true)
                        allowedPackages = store.allowedAgentPackages
                        newPkg = ""
                    }
                }) { Text("Add") }
            }
        }

        item {
            HorizontalDivider()
            Text("Hugging Face Access Token (Optional)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Stored locally in EncryptedSharedPreferences for gated models (Llama, Gemma).",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = hfToken,
                onValueChange = { hfToken = it },
                label = { Text("hf_...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Button(onClick = { store.hfToken = hfToken }) { Text("Save Token") }
        }

        item {
            HorizontalDivider()
            Text("License & Privacy", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "ChipIT is open-source under the MIT License. All inference, UI trees, and telemetry stay 100% on-device. Downloaded model weights retain their upstream Hugging Face licenses.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
