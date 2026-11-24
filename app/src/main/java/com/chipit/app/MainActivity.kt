package com.chipit.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chipit.app.ui.ChipITViewModel
import com.chipit.app.ui.screens.BenchmarksScreen
import com.chipit.app.ui.screens.ChatAgentScreen
import com.chipit.app.ui.screens.ModelsScreen
import com.chipit.app.ui.screens.SettingsScreen
import com.chipit.app.ui.theme.ChipITTheme

enum class ChipITTab(val label: String) {
    MODELS("Models"),
    CHAT("Chat & Agent"),
    BENCHMARKS("Benchmarks"),
    SETTINGS("Settings")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChipITTheme {
                val vm: ChipITViewModel = viewModel(factory = ChipITViewModel.Factory(application))
                val context = LocalContext.current
                val banner by vm.statusBanner.collectAsState()
                var currentTab by remember { mutableStateOf(ChipITTab.MODELS) }
                val snackbarHostState = remember { SnackbarHostState() }

                val notificationPermLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { /* User chose whether to grant notifications */ }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                LaunchedEffect(banner) {
                    banner?.let {
                        snackbarHostState.showSnackbar(it)
                        vm.clearBanner()
                    }
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        NavigationBar {
                            ChipITTab.entries.forEach { tab ->
                                val icon = when (tab) {
                                    ChipITTab.MODELS -> Icons.Default.Memory
                                    ChipITTab.CHAT -> Icons.AutoMirrored.Filled.Chat
                                    ChipITTab.BENCHMARKS -> Icons.Default.Speed
                                    ChipITTab.SETTINGS -> Icons.Default.Settings
                                }
                                NavigationBarItem(
                                    selected = currentTab == tab,
                                    onClick = { currentTab = tab },
                                    icon = { Icon(icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) }
                                )
                            }
                        }
                    }
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        when (currentTab) {
                            ChipITTab.MODELS -> ModelsScreen(vm)
                            ChipITTab.CHAT -> ChatAgentScreen(vm)
                            ChipITTab.BENCHMARKS -> BenchmarksScreen(vm)
                            ChipITTab.SETTINGS -> SettingsScreen(vm)
                        }
                    }
                }
            }
        }
    }
}
