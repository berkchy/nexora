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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
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
import com.pickle.patcher.ui.theme.Gray30
import androidx.compose.ui.text.style.TextOverflow
import com.pickle.patcher.ui.theme.Gray60

@Composable
fun CompilerScreen(vm: PatcherViewModel, onOpenSettings: () -> Unit) {
    val scroll = rememberScrollState()
    val listScroll = rememberScrollState()
    val scripts by vm.scripts.collectAsState()
    val compile by vm.compile.collectAsState()
    val scriptRoot by vm.scriptRoot.collectAsState()
    var selected by remember { mutableStateOf(setOf<String>()) }
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

    val selectedSources = scripts.filter { it.path in selected }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        CompilerHeader(
            scriptRoot = scriptRoot,
            scriptCount = scripts.size,
            selectedCount = selected.size,
            failureCount = failures.size,
            compile = compile,
        )

        Spacer(Modifier.height(16.dp))

        // Scripts and Output folders, and the parallelism slider, live in
        // Settings: they are set once and then only get in the way of the list.
        // The chosen folder is still named here, because "no scripts found" and
        // "no folder chosen" need to be told apart at a glance.
        if (scriptRoot == null) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Scripts folder",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Choose where your .sma sources live before compiling.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton(
                        text = "Open Settings",
                        onClick = { onOpenSettings() },
                    )
                }
            }
        } else {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        scriptRoot.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onOpenSettings() }) {
                        Text("Settings", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        SectionHeader("Plugins", "Pick the sources to build")
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
                        color = if (selected.isNotEmpty()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
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
            Spacer(Modifier.height(20.dp))
            SectionHeader("Compile", "Build the selected queue")
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (selectedSources.size == 1) {
                                selectedSources.first().name
                            } else {
                                "${selectedSources.size} plugins selected"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (selectedSources.size > 1) {
                                "Compiled in order · ${selectedSources.first().scriptDir}"
                            } else {
                                selectedSources.first().scriptDir
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                PrimaryButton(
                    text = if (selectedSources.size == 1) {
                        "Compile plugin"
                    } else {
                        "Compile all (${selectedSources.size})"
                    },
                    onClick = { vm.compileAll(selectedSources) },
                    enabled = compile !is CompileState.Compiling,
                    icon = { Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp)) },
                )
                if (compile is CompileState.Compiling) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Compiler is running…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
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
                Spacer(Modifier.height(20.dp))
                SectionHeader("Output", "Compiler log")
                OutputPanel(text = c.source, busy = true)
            }
            is CompileState.Done -> {
                Spacer(Modifier.height(20.dp))
                SectionHeader("Output", "Compiler log")
                OutputPanel(
                    text = c.log,
                    error = c.failures.isNotEmpty(),
                    status = if (c.failures.isEmpty()) "Done" else "Needs attention",
                )
            }
            is CompileState.Failed -> {
                Spacer(Modifier.height(20.dp))
                SectionHeader("Output", "Compiler log")
                OutputPanel(text = c.message, error = true, status = "Failed")
            }
            else -> {}
        }

        Spacer(Modifier.height(20.dp))
    }

        notice?.let { BottomNotice(it) }
    }
}

@Composable
private fun CompilerHeader(
    scriptRoot: String?,
    scriptCount: Int,
    selectedCount: Int,
    failureCount: Int,
    compile: CompileState,
) {
    val status = when {
        compile is CompileState.Compiling -> "Compiling"
        compile is CompileState.Failed -> "Failed"
        compile is CompileState.Done && failureCount > 0 -> "Needs attention"
        compile is CompileState.Done -> "Done"
        scriptRoot == null -> "Setup"
        else -> "Ready"
    }
    val tone = when (status) {
        "Ready", "Done" -> Tone.Primary
        "Compiling", "Needs attention" -> Tone.Tertiary
        "Failed" -> Tone.Error
        else -> Tone.Neutral
    }
    val subtitle = when {
        scriptRoot == null -> "Choose a scripts folder to unlock the compiler."
        compile is CompileState.Compiling -> "amxxpc is building your queue."
        compile is CompileState.Failed -> "The last build stopped with an error."
        else -> "Compile .sma plugins with the local amxxpc."
    }

    ScreenHeader(
        icon = Icons.Filled.Build,
        title = "Compiler",
        subtitle = subtitle,
        status = status,
        statusTone = tone,
        stats = listOf(
            Stat("Files", "$scriptCount", if (scriptCount > 0) Tone.Primary else Tone.Neutral),
            Stat("Selected", "$selectedCount", if (selectedCount > 0) Tone.Primary else Tone.Neutral),
            Stat("Errors", "$failureCount", if (failureCount > 0) Tone.Error else Tone.Neutral),
        ),
    )
}

@Composable
private fun OutputPanel(
    text: String,
    error: Boolean = false,
    busy: Boolean = false,
    status: String? = null,
) {
    val context = LocalContext.current
    val label = status ?: if (busy) "RUNNING" else null
    val badge = when {
        error -> MaterialTheme.colorScheme.errorContainer
        busy -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val onBadge = when {
        error -> MaterialTheme.colorScheme.onErrorContainer
        busy -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "TERMINAL",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (label != null) {
                    Surface(shape = RoundedCornerShape(50), color = badge) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = onBadge,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                IconButton(onClick = {
                    val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                    clip?.setPrimaryClip(
                        android.content.ClipData.newPlainText("compiler log", text)
                    )
                }) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy log",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            LogBox(text = text, error = error, busy = busy)
        }
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
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.inverseSurface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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
    val container = when {
        error != null -> MaterialTheme.colorScheme.errorContainer
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when {
        error != null -> MaterialTheme.colorScheme.onErrorContainer
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                source.name,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
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
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else if (selected) {
                Icon(
                    Icons.Filled.CheckCircle, null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp),
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
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
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
                busy -> MaterialTheme.colorScheme.primary
                error -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(12.dp),
        )
    }
}
