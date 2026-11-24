package com.chipit.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chipit.app.data.db.ComputeBackend
import com.chipit.app.data.db.ModelEntity
import com.chipit.app.engine.InferenceConfig
import com.chipit.app.model.DeviceFitStatus
import com.chipit.app.ui.ChipITViewModel

@Composable
fun ModelsScreen(vm: ChipITViewModel) {
    val localModels by vm.localModels.collectAsState()
    val hfResults by vm.hfResults.collectAsState()
    val selectedRepo by vm.selectedRepo.collectAsState()
    val repoFiles by vm.repoFiles.collectAsState()
    val hfBusy by vm.hfBusy.collectAsState()
    val downloadProgress by vm.downloadProgress.collectAsState()
    val runtime by vm.activeRuntime.collectAsState()

    val device = remember { vm.getDeviceResources() }
    var searchQuery by remember { mutableStateOf("qwen2.5 1.5b") }
    var includeLiteRt by remember { mutableStateOf(false) }
    var fitsOnlyFilter by remember { mutableStateOf(true) }
    var modelToLoad by remember { mutableStateOf<ModelEntity?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.importLocalModel(uri)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Device Resources", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "RAM: ${device.availableRamMb} MB free / ${device.totalRamMb} MB total · Storage: ${device.freeStorageMb / 1024} GB free",
                        style = MaterialTheme.typography.bodySmall
                    )
                    runtime.model?.let { loaded ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Active: ${loaded.name} (${runtime.config.backend.displayName}, ctx=${runtime.config.contextLength})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        downloadProgress?.let { prog ->
            item {
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Downloading ${prog.fileName}",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { vm.cancelDownload() }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { prog.fraction }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${prog.downloadedBytes / (1024 * 1024)} / ${prog.totalBytes / (1024 * 1024)} MB (runs in background)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Local Models (${localModels.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import File")
                }
            }
        }

        if (localModels.isEmpty()) {
            item {
                Text(
                    "No local models yet. Search Hugging Face below or import a .gguf / .task file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(localModels, key = { it.id }) { model ->
                val isLoaded = runtime.model?.id == model.id
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(model.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${model.quantization} · ${model.sizeBytes / (1024 * 1024)} MB · ~${model.estRamMb} MB RAM · ${model.license}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row {
                            IconButton(onClick = { vm.deleteModel(model) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                            Button(onClick = { modelToLoad = model }) {
                                Text(if (isLoaded) "Reload" else "Load")
                            }
                        }
                    }
                }
            }
        }

        item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }

        item {
            Text("Hugging Face Hub", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search GGUF or LiteRT models") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.searchHuggingFace(searchQuery, includeLiteRt) }) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !includeLiteRt,
                    onClick = { includeLiteRt = false },
                    label = { Text("GGUF") }
                )
                FilterChip(
                    selected = includeLiteRt,
                    onClick = { includeLiteRt = true },
                    label = { Text("LiteRT (.task)") }
                )
                FilterChip(
                    selected = fitsOnlyFilter,
                    onClick = { fitsOnlyFilter = !fitsOnlyFilter },
                    label = { Text("Fits my device") }
                )
            }
        }

        if (hfBusy) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }

        if (selectedRepo != null) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.closeRepoFiles() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Column {
                        Text(selectedRepo!!.id, fontWeight = FontWeight.Bold)
                        Text("License: ${selectedRepo!!.license}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            val filteredFiles = if (fitsOnlyFilter) {
                repoFiles.filter { it.fitStatus != DeviceFitStatus.WONT_FIT }.ifEmpty { repoFiles }
            } else {
                repoFiles
            }
            items(filteredFiles, key = { it.fileName }) { file ->
                val badgeColor = when (file.fitStatus) {
                    DeviceFitStatus.FITS -> Color(0xFF48BB78)
                    DeviceFitStatus.TIGHT -> Color(0xFFF6AD55)
                    DeviceFitStatus.WONT_FIT -> Color(0xFFFC8181)
                }
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(file.fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    file.fitStatus.label,
                                    color = badgeColor,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${file.sizeBytes / (1024 * 1024)} MB · ~${file.estRamMb} MB RAM · ${file.quantization}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        FilledTonalButton(onClick = { vm.downloadCandidate(file) }) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Get")
                        }
                    }
                }
            }
        } else {
            items(hfResults, key = { it.id }) { repo ->
                Card(onClick = { vm.openRepoFiles(repo) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(repo.id, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Downloads: ${repo.downloads} · License: ${repo.license}" + if (repo.isGated) " · Gated" else "",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                }
            }
        }
    }

    modelToLoad?.let { target ->
        LoadConfigDialog(
            model = target,
            availableRamMb = device.availableRamMb,
            onDismiss = { modelToLoad = null },
            onConfirm = { cfg ->
                vm.loadModel(target, cfg)
                modelToLoad = null
            }
        )
    }
}

@Composable
private fun LoadConfigDialog(
    model: ModelEntity,
    availableRamMb: Int,
    onDismiss: () -> Unit,
    onConfirm: (InferenceConfig) -> Unit
) {
    var backend by remember { mutableStateOf(ComputeBackend.VULKAN) }
    var threads by remember { mutableIntStateOf(4) }
    var gpuLayers by remember { mutableIntStateOf(24) }
    var contextLen by remember { mutableIntStateOf(2048) }

    val ramWarning = model.estRamMb > availableRamMb

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Load ${model.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ramWarning) {
                    Text(
                        "Warning: Estimated RAM (~${model.estRamMb} MB) exceeds currently available RAM ($availableRamMb MB).",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text("Compute Backend", style = MaterialTheme.typography.labelMedium)
                Column {
                    listOf(
                        ComputeBackend.CPU,
                        ComputeBackend.VULKAN,
                        ComputeBackend.OPENCL,
                        ComputeBackend.HYBRID,
                        ComputeBackend.LITERT_GPU
                    ).forEach { b ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = backend == b, onClick = { backend = b })
                            Text(b.displayName, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("CPU Threads: $threads", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = threads.toFloat(),
                    onValueChange = { threads = it.toInt() },
                    valueRange = 1f..8f,
                    steps = 6
                )
                if (backend != ComputeBackend.CPU) {
                    Text("GPU Offload Layers: $gpuLayers", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = gpuLayers.toFloat(),
                        onValueChange = { gpuLayers = it.toInt() },
                        valueRange = 0f..40f
                    )
                }
                Text("Context Length", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(512, 1024, 2048, 4096).forEach { ctx ->
                        FilterChip(
                            selected = contextLen == ctx,
                            onClick = { contextLen = ctx },
                            label = { Text("$ctx") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(
                    InferenceConfig(
                        backend = backend,
                        threads = threads,
                        gpuLayers = if (backend == ComputeBackend.CPU) 0 else gpuLayers,
                        contextLength = contextLen
                    )
                )
            }) { Text("Load Model") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
