package com.chipit.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chipit.app.data.db.ComputeBackend
import com.chipit.app.ui.ChipITViewModel

@Composable
fun BenchmarksScreen(vm: ChipITViewModel) {
    val runs by vm.benchmarkRuns.collectAsState()
    val progress by vm.benchmarkProgress.collectAsState()
    val logs by vm.agentLogs.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Standard Benchmark Suite", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.runBenchmark(ComputeBackend.CPU) },
                    enabled = !progress.isRunning,
                    modifier = Modifier.weight(1f)
                ) { Text("Run CPU") }
                Button(
                    onClick = { vm.runBenchmark(ComputeBackend.VULKAN) },
                    enabled = !progress.isRunning,
                    modifier = Modifier.weight(1f)
                ) { Text("Run GPU (Vulkan)") }
            }
        }

        if (progress.isRunning) {
            item {
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Text("Running: ${progress.currentTitle} (${progress.currentPromptIndex}/${progress.totalPrompts})")
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (progress.totalPrompts > 0) progress.currentPromptIndex.toFloat() / progress.totalPrompts else 0f
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        progress.thermalWarning?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        if (runs.isNotEmpty()) {
            item {
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Text("Decode Speed Comparison (tok/s)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        val recent = runs.take(6).reversed()
                        val maxTps = (recent.maxOfOrNull { it.avgDecodeTps } ?: 1.0).coerceAtLeast(1.0)
                        Canvas(modifier = Modifier.fillMaxWidth().height(110.dp)) {
                            val barWidth = (size.width / recent.size.coerceAtLeast(1)) * 0.6f
                            val step = size.width / recent.size.coerceAtLeast(1)
                            recent.forEachIndexed { idx, run ->
                                val h = ((run.avgDecodeTps / maxTps) * size.height).toFloat()
                                val color = if (run.backend == ComputeBackend.CPU.name) Color(0xFFF6AD55) else Color(0xFF4FD1C5)
                                drawRect(
                                    color = color,
                                    topLeft = Offset(idx * step + (step - barWidth) / 2f, size.height - h),
                                    size = Size(barWidth, h)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("Amber = CPU · Teal = GPU", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.exportBenchmarks(asJson = false) }, modifier = Modifier.weight(1f)) {
                        Text("Export CSV")
                    }
                    OutlinedButton(onClick = { vm.exportBenchmarks(asJson = true) }, modifier = Modifier.weight(1f)) {
                        Text("Export JSON")
                    }
                }
            }
        }

        items(runs, key = { it.id }) { run ->
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("${run.modelName} · ${run.backend}", fontWeight = FontWeight.SemiBold)
                    Text(
                        "TTFT: ${run.avgTtftMs}ms · Prefill: ${"%.1f".format(run.avgPrefillTps)} tok/s · Decode: ${"%.1f".format(run.avgDecodeTps)} tok/s",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Peak RAM: ${run.peakRamMb}MB · Battery: ${"%.2f".format(run.batteryPctPer1kTok)}%/1k tok · Max Thermal: ${run.maxThermalStatus}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (logs.isNotEmpty()) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Agent Action Audit Log", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            items(logs.take(20), key = { it.id }) { log ->
                Card {
                    Column(Modifier.padding(10.dp)) {
                        Text("Step ${log.stepIndex} · ${log.tier} · ${log.status} (${log.targetPackage})", style = MaterialTheme.typography.labelMedium)
                        Text(log.toolJson, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
