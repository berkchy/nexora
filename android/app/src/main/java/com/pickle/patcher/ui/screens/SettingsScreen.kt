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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
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
import com.pickle.patcher.ui.DrawerWidthController

/**
 * Everything the app can be tuned with, kept out of the three task screens so
 * they stay about doing one thing.
 *
 * The compile paths and the worker count moved here from the Compiler screen:
 * both are setup you do once, and having them above the plugin list meant the
 * first thing on the screen was configuration.
 */
@Composable
fun SettingsScreen(vm: PatcherViewModel, drawerWidthController: DrawerWidthController) {
    val context = LocalContext.current
    val workers by vm.compileWorkers.collectAsState()
    val patchWorkers by vm.patchWorkers.collectAsState()
    val drawerWidth by drawerWidthController.fraction.collectAsState()
    val maxWorkers = vm.maxCompileWorkers()
    val maxPatchWorkers = vm.maxPatchWorkers()
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
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        ScreenHeader(
            icon = Icons.Filled.Settings,
            title = "Settings",
            subtitle = "Build workers, folders, and drawer width.",
            status = "System",
            statusTone = Tone.Secondary,
        )

        Spacer(Modifier.height(20.dp))

        SectionHeader("Compiler", "Parallel build workers")
        AppCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Compile at once",
                    style = MaterialTheme.typography.bodyMedium,
                    // The card surface is Gray85, so Gray90 as a text colour
                    // put near-black letters on near-black. Card titles go
                    // through onSurface, which the theme keeps readable.
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "$workers",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "How many .sma files are compiled at the same time. Higher is " +
                    "faster but uses more memory and heats the device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = workers.toFloat(),
                onValueChange = { vm.setCompileWorkers(it.toInt()) },
                valueRange = 1f..maxWorkers.toFloat(),
                // One step per core, so the slider always lands on a whole number.
                steps = (maxWorkers - 2).coerceAtLeast(0),
            )
        }

        Spacer(Modifier.height(20.dp))

        SectionHeader("Patcher", "Parallel APK workers")
        AppCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Compress at once",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "$patchWorkers",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "How many APK entries are compressed at the same time. Higher is " +
                    "faster but uses more memory and heats the device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = patchWorkers.toFloat(),
                onValueChange = { vm.setPatchWorkers(it.toInt()) },
                valueRange = 1f..maxPatchWorkers.toFloat(),
                steps = (maxPatchWorkers - 2).coerceAtLeast(0),
            )
        }

        Spacer(Modifier.height(20.dp))

        SectionHeader("Folders", "Scripts and compiler output")
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
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Storage permission is required.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        SectionHeader("Navigation", "Side menu width")
        AppCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Drawer width",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${(drawerWidth * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "Width of the side menu as a percentage of the screen. Increase if the menu feels too narrow.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = drawerWidth,
                onValueChange = { drawerWidthController.setFraction(it) },
                valueRange = 0.3f..0.85f,
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
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                path ?: empty,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.TextButton(onClick = onAction) { Text(action) }
        if (onClear != null) {
            androidx.compose.material3.TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}