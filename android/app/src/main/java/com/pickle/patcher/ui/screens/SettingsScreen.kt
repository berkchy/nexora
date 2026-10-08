package com.pickle.patcher.ui.screens

import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pickle.patcher.patcher.PatcherViewModel
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.AlertRed
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.Gray90

/**
 * Everything the app can be tuned with, kept out of the three task screens so
 * they stay about doing one thing.
 *
 * The compile paths and the worker count moved here from the Compiler screen:
 * both are setup you do once, and having them above the plugin list meant the
 * first thing on the screen was configuration.
 */
@Composable
fun SettingsScreen(vm: PatcherViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val workers by vm.compileWorkers.collectAsState()
    val maxWorkers = vm.maxCompileWorkers()
    val scriptRoot by vm.scriptRoot.collectAsState()
    val outputRoot by vm.outputRoot.collectAsState()

    var pickForOutput by remember { mutableStateOf(false) }
    var showPermissionRationale by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            if (pickForOutput) vm.setOutputRoot(uri) else vm.setScriptRoot(uri)
        }
        pickForOutput = false
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            folderPicker.launch(null)
        }
    }

    fun requestStorageAndPickFolder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                folderPicker.launch(null)
            } else {
                storagePermissionLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                    }
                )
            }
        } else {
            folderPicker.launch(null)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Spacer(Modifier.height(8.dp))

        SectionHeader("COMPILER")
        AppCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Compile at once",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Gray90,
                )
                Text("$workers", style = MaterialTheme.typography.titleMedium, color = Accent)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "How many .sma files are compiled at the same time. Higher is " +
                    "faster but uses more memory and heats the device.",
                style = MaterialTheme.typography.bodySmall,
                color = Gray40,
            )
            Slider(
                value = workers.toFloat(),
                onValueChange = { vm.setCompileWorkers(it.toInt()) },
                valueRange = 1f..maxWorkers.toFloat(),
                // One step per core, so the slider always lands on a whole number.
                steps = (maxWorkers - 2).coerceAtLeast(0),
            )
        }

        Spacer(Modifier.height(16.dp))

        SectionHeader("FOLDERS")
        AppCard {
            SettingsPathRow(
                label = "Scripts",
                path = scriptRoot,
                empty = "No folder selected",
                action = if (scriptRoot != null) "Change" else "Pick",
                onAction = { pickForOutput = false; requestStorageAndPickFolder() },
                onClear = if (scriptRoot != null) ({ vm.clearScriptRoot() }) else null,
            )
            Spacer(Modifier.height(12.dp))
            SettingsPathRow(
                label = "Output",
                path = outputRoot,
                empty = "addons/amxmodx/plugins",
                action = if (outputRoot != null) "Change" else "Pick",
                onAction = { pickForOutput = true; requestStorageAndPickFolder() },
                onClear = if (outputRoot != null) ({ vm.clearOutputRoot() }) else null,
            )

            if (showPermissionRationale) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AlertRed.copy(alpha = 0.1f),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            "Storage permission is required.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AlertRed,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionHeader("ABOUT")
        AppCard {
            Text(
                "Nexora",
                style = MaterialTheme.typography.titleSmall,
                color = Gray90,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Patches the CS 1.6 Android client so AMX Mod X runs on it. " +
                    "Not affiliated with or endorsed by Valve.",
                style = MaterialTheme.typography.bodySmall,
                color = Gray40,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "github.com/berkchy/nexora",
                style = MaterialTheme.typography.bodySmall,
                color = Gray40,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsPathRow(
    label: String,
    path: String?,
    empty: String,
    action: String,
    onAction: () -> Unit,
    onClear: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Gray90)
            Text(
                path ?: empty,
                style = MaterialTheme.typography.bodySmall,
                color = Gray40,
            )
        }
        androidx.compose.material3.TextButton(onClick = onAction) { Text(action) }
        if (onClear != null) {
            androidx.compose.material3.TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}