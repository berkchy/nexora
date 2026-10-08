package com.pickle.patcher.ui.screens

import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pickle.patcher.patcher.CompileState
import com.pickle.patcher.patcher.PatcherViewModel
import com.pickle.patcher.patcher.SmaSource
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.AlertRed
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.Gray80
import com.pickle.patcher.ui.theme.Gray90
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import com.pickle.patcher.ui.theme.Gray30
import androidx.compose.ui.text.style.TextOverflow
import com.pickle.patcher.ui.theme.Gray60

@Composable
fun CompilerScreen(vm: PatcherViewModel) {
    val scroll = rememberScrollState()
    val listScroll = rememberScrollState()
    val scripts by vm.scripts.collectAsState()
    val compile by vm.compile.collectAsState()
    val scriptRoot by vm.scriptRoot.collectAsState()
    var selected by remember { mutableStateOf(setOf<String>()) }
    var showPermissionRationale by remember { mutableStateOf(false) }
    val outputRoot by vm.outputRoot.collectAsState()
    val workers by vm.compileWorkers.collectAsState()
    val maxWorkers = vm.maxCompileWorkers()
    var pickForOutput by remember { mutableStateOf(false) }
    val failures = (compile as? CompileState.Done)?.failures ?: emptyMap()
    var stampError by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Set when the folder picker came back without a folder, so we can offer
    // Auto Find instead of silently doing nothing. true = the Output row was
    // being picked, false = the Scripts row.
    var pickCancelled by remember { mutableStateOf<Boolean?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    // .sma sources are edited outside the app, so watch the folder while this
    // screen is open instead of making the user hit refresh after every save.
    DisposableEffect(Unit) {
        vm.startScriptWatcher()
        onDispose { vm.stopScriptWatcher() }
    }

    // Small bottom notice, disappears on its own.
    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(NOTICE_MS)
            notice = null
        }
    }

    // Scroll the outer column to the compiler output when a compile starts.
    LaunchedEffect(compile) {
        if (compile is CompileState.Compiling) {
            scroll.animateScrollTo(scroll.maxValue)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) {
            // Backed out of the picker: nothing was selected. Remember which
            // row asked for it so Auto Find knows what to look for.
            pickCancelled = pickForOutput
        } else if (pickForOutput) {
            vm.setOutputRoot(uri)
        } else {
            vm.setScriptRoot(uri)
        }
        pickForOutput = false
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                folderPicker.launch(null)
            } else {
                showPermissionRationale = true
            }
        }
    }

    fun requestStorageAndPickFolder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                folderPicker.launch(null)
            } else {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                }
                storagePermissionLauncher.launch(intent)
            }
        } else {
            folderPicker.launch(null)
        }
    }

    val selectedSources = scripts.filter { it.path in selected }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("Compiler", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(2.dp))
        Text(
            "Compile .sma plugins with the local amxxpc.",
            style = MaterialTheme.typography.bodyMedium,
            color = Gray40,
        )

        Spacer(Modifier.height(20.dp))

        AppCard {
            CompactPathRow(
                label = "Scripts",
                path = scriptRoot,
                empty = "No folder selected",
                action = if (scriptRoot != null) "Change" else "Pick",
                onAction = { pickForOutput = false; requestStorageAndPickFolder() },
                onRefresh = if (scriptRoot != null) ({ vm.refreshScripts() }) else null,
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
                            style = MaterialTheme.typography.titleSmall,
                            color = AlertRed,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "The compiler needs access to your files to read .sma scripts and write compiled .amxx output. Please grant \"All files access\" in the system settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Gray40,
                        )
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton(
                            text = "Open Settings",
                            onClick = {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                storagePermissionLauncher.launch(intent)
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        AppCard {
            CompactPathRow(
                label = "Output",
                path = outputRoot,
                empty = "addons/amxmodx/plugins",
                action = if (outputRoot != null) "Change" else "Pick",
                onAction = { pickForOutput = true; requestStorageAndPickFolder() },
                onReset = if (outputRoot != null) ({ vm.clearOutputRoot() }) else null,
            )
        }

        Spacer(Modifier.height(16.dp))

        SectionHeader("PARALLELISM")
        AppCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Compile at once",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Gray90,
                )
                Text(
                    "$workers",
                    style = MaterialTheme.typography.titleMedium,
                    color = Accent,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "How many .sma files are compiled at the same time. " +
                    "Higher is faster but uses more memory and heats the device.",
                style = MaterialTheme.typography.bodySmall,
                color = Gray40,
            )
            Slider(
                value = workers.toFloat(),
                onValueChange = { vm.setCompileWorkers(it.toInt()) },
                valueRange = 1f..maxWorkers.toFloat(),
                // One step per core, so the slider lands on a whole number.
                steps = (maxWorkers - 2).coerceAtLeast(0),
            )
        }

        Spacer(Modifier.height(16.dp))

        SectionHeader("PLUGINS (.sma)")
        AppCard {
            if (scriptRoot == null) {
                Text("Select a folder above.", style = MaterialTheme.typography.bodySmall, color = Gray40)
            } else if (scripts.isEmpty()) {
                Text("No .sma files found.", style = MaterialTheme.typography.bodySmall, color = Gray40)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (selected.isEmpty()) "${scripts.size} scripts" else "${selected.size} selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected.isNotEmpty()) Accent else Gray40,
                    )
                    Row {
                        if (scripts.size > 1) {
                            GhostButton(
                                text = if (selected.size == scripts.size) "Clear" else "All",
                                onClick = {
                                    selected = if (selected.size != scripts.size) scripts.map { it.path }.toSet() else emptySet()
                                },
                            )
                        }
                    }
                }
                // Inner scroll box (like LIBS): keeps the COMPILE card
                // reachable even with 100+ plugins.
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 168.dp).verticalScroll(listScroll),
                ) {
                    scripts.forEach { s ->
                        ScriptRow(
                            source = s,
                            selected = s.path in selected,
                            error = failures[s.path],
                            onClick = {
                                selected = if (s.path in selected) selected - s.path else selected + s.path
                            },
                            onErrorClick = { err -> stampError = s.path to err },
                        )
                    }
                }
            }
        }


        if (selectedSources.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionHeader("COMPILE")
            AppCard {
                Text(
                    if (selectedSources.size == 1)
                        selectedSources.first().name
                    else
                        "${selectedSources.size} plugins selected",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (selectedSources.size > 1) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "All selected plugins will be compiled in order.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
                Text(selectedSources.first().scriptDir, style = MaterialTheme.typography.bodySmall, color = Gray40)
                Spacer(Modifier.height(10.dp))
                PrimaryButton(
                    text = if (selectedSources.size == 1) "Compile" else "Compile All (${selectedSources.size})",
                    onClick = { vm.compileAll(selectedSources) },
                    enabled = compile !is CompileState.Compiling,
                    icon = { Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp)) },
                )
            }
        }

        stampError?.let { (name, message) ->
            ErrorDialog(name = name.substringAfterLast('/'), message = message) { stampError = null }
        }

        pickCancelled?.let { forOutput ->
            PickFolderDialog(
                target = vm.autoFindTargetName(forOutput),
                onClose = { pickCancelled = null },
                onAutoFind = {
                    pickCancelled = null
                    val what = if (forOutput) "Output" else "Scripts"
                    notice = if (vm.autoFindAmxxFolder(forOutput))
                        "$what folder found."
                    else
                        "Could not find xash/<game>/${vm.autoFindTargetName(forOutput)}"
                },
            )
        }

        when (val c = compile) {
            is CompileState.Compiling -> {
                Spacer(Modifier.height(12.dp))
                SectionHeader("OUTPUT")
                LogBox(c.source, busy = true)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
            }
            is CompileState.Done -> {
                Spacer(Modifier.height(12.dp))
                SectionHeader("OUTPUT")
                LogBox(c.log)
            }
            is CompileState.Failed -> {
                Spacer(Modifier.height(12.dp))
                SectionHeader("OUTPUT")
                LogBox(c.message, error = true)
            }
            else -> {}
        }
    }

        notice?.let { BottomNotice(it) }
    }
}

/** How long the little bottom notice stays on screen. */
private const val NOTICE_MS = 4000L

/**
 * Shown when the SAF folder picker was left without choosing anything. The
 * manual tree walk is the painful part on a phone, so Auto Find does it.
 */
@Composable
private fun PickFolderDialog(
    target: String,
    onClose: () -> Unit,
    onAutoFind: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Can't pick a folder?", color = AlertRed) },
        text = {
            Text(
                "No folder was selected.\n\n" +
                    "Auto Find looks for xash/<game>/$target under the device's " +
                    "internal storage and fills it in for you.",
                style = MaterialTheme.typography.bodySmall,
                color = Gray30,
            )
        },
        confirmButton = {
            TextButton(onClick = onAutoFind) { Text("Auto Find", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Close", color = Gray60) }
        },
    )
}

/** Small bottom notification, the AppCard styling at snackbar size. */
@Composable
private fun BottomNotice(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 18.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Gray80,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = Accent,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ScriptRow(
    source: SmaSource,
    selected: Boolean,
    error: String?,
    onClick: () -> Unit,
    onErrorClick: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) Gray80 else Gray90,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                source.name,
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) AlertRed else MaterialTheme.typography.titleSmall.color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (error != null) {
                IconButton(
                    onClick = { onErrorClick(error) },
                    modifier = Modifier.size(26.dp),
                ) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = "Compile error",
                        tint = AlertRed,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else if (selected) {
                Icon(
                    Icons.Filled.CheckCircle, null,
                    tint = Accent, modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun CompactPathRow(
    label: String,
    path: String?,
    empty: String,
    action: String,
    onAction: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = Gray40,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            path ?: empty,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = if (path != null) Accent else Gray60,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onRefresh != null) {
            IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Refresh,
                    "Refresh",
                    modifier = Modifier.size(15.dp),
                    tint = Gray40,
                )
            }
        }
        if (onReset != null) {
            IconButton(onClick = onReset, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Close,
                    "Use default",
                    modifier = Modifier.size(15.dp),
                    tint = Gray40,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        TextButton(
            onClick = onAction,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            modifier = Modifier.height(28.dp),
        ) {
            Text(action, style = MaterialTheme.typography.labelLarge, color = Accent)
        }
    }
}

@Composable
private fun ErrorDialog(name: String, message: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name, color = AlertRed, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = Gray30,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Gray60) }
        },
        dismissButton = {
            TextButton(onClick = {
                val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                clip?.setPrimaryClip(
                    android.content.ClipData.newPlainText(name, message)
                )
            }) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Copy error",
                    tint = Accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}

@Composable
private fun LogBox(text: String, error: Boolean = false, busy: Boolean = false) {
    val scrollState = rememberScrollState()
    // Keep the log view pinned to its own bottom-out point: kick the scroll to
    // max on each update so the newest lines stay visible while compiling.
    LaunchedEffect(text) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Gray80,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp, max = 240.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            ),
            color = when {
                busy -> Accent
                error -> AlertRed
                else -> Gray40
            },
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(10.dp),
        )
    }
}
