package com.pickle.patcher.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InstallDesktop
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import com.pickle.patcher.lib.ApkPatcher
import com.pickle.patcher.patcher.BundleState
import com.pickle.patcher.patcher.LibInfo
import com.pickle.patcher.patcher.PatchUiState
import com.pickle.patcher.patcher.SourceDownloadState
import com.pickle.patcher.patcher.PatcherViewModel
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.AlertRed
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.Gray60
import com.pickle.patcher.ui.theme.Gray70
import com.pickle.patcher.ui.theme.Gray80
import com.pickle.patcher.ui.theme.Gray90
import com.pickle.patcher.ui.theme.SuccessGreen
import com.pickle.patcher.ui.theme.White
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val mbFmt = DecimalFormat("0.0")
private fun Long.mb(): String = "${mbFmt.format(this / 1048576.0)} MB"

/** Manifest versions like 1.27.x print as v1.27.x; continuous builds print raw. */
fun String.toDisplayVersion(): String =
    if (firstOrNull()?.isDigit() == true) "v$this" else this

@Composable
fun PatchScreen(vm: PatcherViewModel) {
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        SectionHeader("SOURCE APK")
        SourceCard(vm)

        Spacer(Modifier.height(16.dp))

        SectionHeader("MOD BUNDLE")
        BundleCard(vm)

        Spacer(Modifier.height(12.dp))

        SectionHeader("LIBS")
        LibListCard(vm)

        Spacer(Modifier.height(16.dp))

        SectionHeader("BUILD")
        PatchCard(vm)

        Spacer(Modifier.height(16.dp))
    }
}

private fun displayAbi(abi: String): String = when (abi) {
    "armeabi-v7a" -> "Arm32"
    else -> "Arm64"
}

@Composable
private fun SourceCard(vm: PatcherViewModel) {
    val source by vm.source.collectAsState()
    val download by vm.sourceDownload.collectAsState()
    // Bind the state to a local first: a delegated property cannot be smart
    // cast, so every branch below needs a stable reference.
    val state = download

    // The client APK always comes from GitHub: check what is cached whenever
    // the card appears, adopt a good one silently and flag a broken or stale
    // one instead of patching with half a file.
    LaunchedEffect(Unit) { vm.refreshSourceApkStatus() }

    AppCard {
        if (source != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Accent.copy(alpha = 0.12f),
                ) {
                    Icon(
                        Icons.Filled.InstallDesktop,
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp).size(18.dp),
                        tint = Accent,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        source!!.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${source!!.sizeBytes.mb()}  ·  ${source!!.entryCount} entries",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
            }
        } else {
            Text(
                "No client APK yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = Gray40,
            )
        }

        Spacer(Modifier.height(12.dp))

        // An available update turns the whole button red: the cached APK is
        // usable, but what is on GitHub is newer and that is the one to patch.
        val isUpdate = state is SourceDownloadState.Outdated
        PrimaryButton(
            text = when (state) {
                is SourceDownloadState.Fetching -> "Finding release..."
                is SourceDownloadState.Downloading ->
                    if (state.total > 0) {
                        "%.0f / %.0f MB".format(
                            state.downloaded / 1048576.0,
                            state.total / 1048576.0,
                        )
                    } else {
                        "%.0f MB".format(state.downloaded / 1048576.0)
                    }
                is SourceDownloadState.Done -> "Download again"
                is SourceDownloadState.Outdated -> "Update"
                is SourceDownloadState.Corrupt -> "Re-download"
                is SourceDownloadState.Failed -> "Retry download"
                null -> "Download from GitHub"
            },
            enabled = state !is SourceDownloadState.Fetching &&
                state !is SourceDownloadState.Downloading,
            icon = {
                Icon(
                    if (isUpdate) Icons.Filled.SystemUpdateAlt else Icons.Filled.Download,
                    null,
                    modifier = Modifier.size(18.dp),
                )
            },
            containerColor = if (isUpdate) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            contentColor = if (isUpdate) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
            onClick = { vm.downloadSourceApk() },
        )

        val note = when (state) {
            is SourceDownloadState.Outdated ->
                "Update available (%s). Local %s vs GitHub %s."
                    .format(
                        state.reason,
                        state.localSize.mb(),
                        state.remoteSize.mb(),
                    )
            is SourceDownloadState.Corrupt ->
                "The downloaded client APK is broken (${state.reason}). Download it again."
            is SourceDownloadState.Failed -> state.message
            is SourceDownloadState.Done ->
                "Client APK ready." + if (source == null) "" else " Up to date."
            else -> null
        }
        if (note != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = if (state is SourceDownloadState.Failed ||
                    state is SourceDownloadState.Corrupt
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    Gray40
                },
            )
        }
    }
}

@Composable
private fun BundleCard(vm: PatcherViewModel) {
    val bundleState by vm.bundle.collectAsState()
    val abiStatus by vm.abiStatus.collectAsState()

    AppCard {
        // Read-only: the device architecture picks the ABI, there is nothing to
        // choose. The status line still shows what that means for the bundle.
        val target = abiStatus.firstOrNull { it.selected }
        Column {
            Text(
                "Target",
                style = MaterialTheme.typography.titleSmall,
                color = Gray40,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${displayAbi(target?.abi ?: vm.isDeviceAbi)} · " +
                    if (target?.abi == "arm64-v8a") "64-bit" else "32-bit",
                style = MaterialTheme.typography.titleSmall,
                color = White,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                when {
                    target == null || !target.inSource -> "Waiting for the client APK"
                    target.installed -> "Installed · ${target.libCount} libs"
                    else -> "No libraries yet"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Gray40,
            )
        }

        Spacer(Modifier.height(12.dp))

        when (val bs = bundleState) {
            is BundleState.Downloading -> {
                Text(
                    bs.tagName.ifBlank { "Bundle" },
                    style = MaterialTheme.typography.titleSmall,
                    color = Accent,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (bs.currentFile.isNotBlank()) {
                        "Downloading ${bs.fileIndex + 1}/${bs.fileTotal} \u00b7 ${bs.currentFile}"
                    } else {
                        "Downloading\u2026"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray40,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                AppProgressBar(bs.percent)
            }
            is BundleState.DownloadError -> {
                BundleSummaryLine(
                    icon = Icons.Filled.Warning,
                    tint = AlertRed,
                    title = "Download failed",
                    detail = bs.message,
                )
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Retry", onClick = { vm.fetchAndDownloadBundle() })
            }
            is BundleState.None -> {
                BundleSummaryLine(
                    icon = Icons.Filled.FolderOpen,
                    tint = Gray40,
                    title = "No bundle for this target",
                    detail = "Download the modules for the selected ABI to patch.",
                )
                Spacer(Modifier.height(10.dp))
                PrimaryButton(
                    text = "Download",
                    onClick = { vm.fetchAndDownloadBundle() },
                    icon = { Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp)) },
                )
            }
            is BundleState.Loaded -> {
                BundleSummaryLine(
                    icon = Icons.Filled.CheckCircle,
                    tint = SuccessGreen,
                    title = "Ready to patch",
                    detail = "Libraries from libs/ \u00b7 ${abiStatus.firstOrNull { it.selected }?.libCount ?: 0} files",
                )
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Refresh", onClick = { vm.fetchAndDownloadBundle() })
            }
            is BundleState.Ready -> {
                BundleSummaryLine(
                    icon = Icons.Filled.CheckCircle,
                    tint = SuccessGreen,
                    title = bs.bundleName,
                    detail = "${bs.entries} entries \u00b7 ${bs.version.toDisplayVersion()}",
                )
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Refresh", onClick = { vm.fetchAndDownloadBundle() })
            }
        }
    }
}

@Composable
private fun BundleSummaryLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    title: String,
    detail: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = White)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = Gray40,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LibListCard(vm: PatcherViewModel) {
    val libs by vm.libs.collectAsState()
    val checking by vm.libsChecking.collectAsState()

    AppCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (libs.isEmpty()) "Local libs" else "${libs.size} local libs",
                style = MaterialTheme.typography.titleSmall,
                color = Gray40,
            )
            GhostButton(
                text = if (checking) "Checking…" else "Check updates",
                onClick = { vm.refreshLibStatus() },
                enabled = !checking,
            )
        }
        Spacer(Modifier.height(4.dp))
        if (libs.isEmpty()) {
            Text(
                "No .so files found in libs directory.",
                style = MaterialTheme.typography.bodyMedium,
                color = Gray40,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Press Download in MOD BUNDLE to fetch them.",
                style = MaterialTheme.typography.bodySmall,
                color = Gray60,
            )
        } else {
            // Flat list: every lib is its own row, box height ~4 rows so it
            // stays compact and the rest scrolls inside.
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 176.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(libs, key = { it.name }) { lib ->
                    LibRow(
                        lib = lib,
                        onRefresh = { vm.refreshSingleLib(lib.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibRow(lib: LibInfo, onRefresh: () -> Unit) {
    val icon = libTypeIcon(lib.name)
    val iconDesc = libTypeDesc(lib.name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = iconDesc,
            modifier = Modifier.size(20.dp).padding(end = 8.dp),
            tint = Gray70,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                lib.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (lib.downloading) {
                AppProgressBar(lib.downloadProgress.coerceAtLeast(0f))
            } else {
                Text(
                    if (lib.localSize > 0) {
                        val local = lib.localSize.mb()
                        val release = lib.releaseSize.mb()
                        if (lib.upToDate) "$local (up to date)" else "$local → $release"
                    } else if (lib.releaseSize > 0) "Outdated" else "Not installed",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (lib.upToDate) Gray60 else AlertRed,
                )
            }
        }
        if (lib.downloading) {
            Icon(
                Icons.Filled.Download,
                contentDescription = "Downloading",
                modifier = Modifier.size(20.dp),
                tint = Accent,
            )
        } else if (lib.upToDate) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Up to date",
                modifier = Modifier.size(20.dp),
                tint = Gray60,
            )
        } else {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "Refresh",
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onRefresh() },
                tint = Accent,
            )
        }
    }
}

private fun libTypeIcon(name: String): ImageVector = when {
    name == "libamxmodx.so" -> Icons.Filled.Extension
    name == "libmetamod.so" -> Icons.Filled.Bolt
    name == "libyapb.so" -> Icons.Filled.SmartToy
    name.startsWith("libclient_android_") -> Icons.Filled.PhoneAndroid
    name.startsWith("libcs_android_") -> Icons.Filled.SportsEsports
    name.contains("_amxx_") -> Icons.Filled.Build
    else -> Icons.Filled.FolderOpen
}

private fun libTypeDesc(name: String): String = when {
    name == "libamxmodx.so" -> "AMX Mod X core"
    name == "libmetamod.so" -> "Metamod HL1"
    name == "libyapb.so" -> "YaPB bot"
    name.startsWith("libclient_android_") -> "CS16Client client DLL"
    name.startsWith("libcs_android_") -> "ReGameDLL game DLL"
    name.contains("_amxx_") -> "AMXX module"
    else -> "Library"
}

@Composable
private fun PatchCard(vm: PatcherViewModel) {
    val state by vm.patch.collectAsState()
    val log by vm.patchLog.collectAsState()
    val bundle = vm.bundle.collectAsState().value
    val context = LocalContext.current
    val canPatch = vm.source.collectAsState().value != null &&
        (bundle is BundleState.Ready || bundle is BundleState.Loaded)
    var showComponents by rememberSaveable { mutableStateOf(false) }

    AppCard {
        when (val s = state) {
            is PatchUiState.Idle -> {
                PrimaryButton(
                    text = "Patch & Sign APK",
                    onClick = { showComponents = true },
                    enabled = canPatch,
                    icon = { Icon(Icons.Filled.RocketLaunch, null, modifier = Modifier.size(18.dp)) },
                )
                if (!canPatch) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Select an APK and load a bundle to continue.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
            }
            is PatchUiState.Running -> {
                PatchSteps(s)
            }
            is PatchUiState.Done -> {
                PatchResult(
                    vm = vm,
                    report = s.report,
                    log = s.log,
                    // Goes through the view model so an installed copy of the
                    // client is removed first, with a dialog first.
                    onInstall = { vm.requestInstall() },
                    onPatchAgain = { vm.reset() },
                )
            }
            is PatchUiState.Failed -> {
                Icon(Icons.Filled.Warning, null, tint = AlertRed, modifier = Modifier.size(20.dp))
                Spacer(Modifier.height(4.dp))
                Text("Patch failed", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    s.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray40,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Copy log", onClick = {
                        val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                        clip?.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                "nexora patch log",
                                (listOf(s.message) + log).joinToString("\n"),
                            )
                        )
                    })
                    SecondaryButton("Try Again", onClick = { vm.reset() })
                }
                Spacer(Modifier.height(12.dp))
                MiniConsole(lines = log)
            }
        }
    }

    if (showComponents && canPatch && state is PatchUiState.Idle) {
        val components = remember(vm) { vm.patchComponents() }
        PatchComponentsDialog(
            components = components,
            summary = vm.patchSummary(),
            onConfirm = { selectedKeys ->
                showComponents = false
                vm.startPatch(selectedKeys)
            },
            onDismiss = { showComponents = false },
        )
    }
}

@Composable
private fun PatchComponentsDialog(
    components: List<PatcherViewModel.PatchComponent>,
    summary: PatcherViewModel.PatchSummary?,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val allKeys = components.map { it.key }
    var selected by remember { mutableStateOf(allKeys.toSet()) }
    val allSelected = selected.size == allKeys.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Patch") },
        text = {
            Column {
                if (summary != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Accent.copy(alpha = 0.07f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                "Target ${displayAbi(summary.abi)}",
                                style = MaterialTheme.typography.titleSmall,
                                color = Accent,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${summary.source} \u00b7 ${summary.sourceSize.mb()}\n" +
                                    "${summary.components} components \u00b7 ${summary.entries} entries\n" +
                                    "payload ${summary.payloadSize.mb()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Gray40,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${selected.size}/${components.size} selected",
                        style = MaterialTheme.typography.labelLarge,
                        color = Gray40,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        selected = if (allSelected) emptySet() else allKeys.toSet()
                    }) {
                        Text(if (allSelected) "Clear" else "All", color = Accent)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    components.forEach { c ->
                        val on = c.key in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (on) selected - c.key else selected + c.key
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = on,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + c.key else selected - c.key
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    c.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (on) White else Gray40,
                                )
                                Text(
                                    c.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Gray40,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }, enabled = selected.isNotEmpty()) {
                Text("Patch", color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Gray60) }
        },
    )
}

@Composable
private fun PatchSteps(state: PatchUiState.Running) {
    val entries = state.counters["entries"].orEmpty()
    val libs = state.counters["libs"].orEmpty()
    val stage = when (state.step) {
        ApkPatcher.Step.ANALYZE -> "analyze"
        ApkPatcher.Step.INJECT -> "inject"
        ApkPatcher.Step.ALIGN -> "align"
        ApkPatcher.Step.SIGN -> "sign"
        ApkPatcher.Step.VERIFY -> "verify"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        RingProgress(fraction = state.progress, label = stage)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = state.detail.ifBlank { "working\u2026" },
                style = MaterialTheme.typography.bodyMedium,
                color = Accent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (entries.isNotEmpty() || libs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (entries.isNotEmpty()) {
                        StatPill("entries", entries)
                    }
                    if (libs.isNotEmpty()) {
                        StatPill("libs", libs)
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    MiniConsole(lines = state.log)
}

@Composable
private fun PatchHistoryCard(vm: PatcherViewModel) {
    val history by vm.patchHistory.collectAsState()
    if (history.isEmpty()) return
    AppCard {
        Text("Patch history", style = MaterialTheme.typography.titleSmall, color = Gray40)
        Spacer(Modifier.height(6.dp))
        history.take(5).forEach { h ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (h.ok) SuccessGreen else AlertRed)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${displayAbi(h.abi)} \u00b7 ${h.libs} libs \u00b7 ${h.entries} entries",
                        style = MaterialTheme.typography.bodySmall,
                        color = White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        h.source,
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray60,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(h.time)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray60,
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun PatchCheckCard(vm: PatcherViewModel) {
    val check by vm.patchCheck.collectAsState()
    AppCard {
        Text("Install check", style = MaterialTheme.typography.titleSmall, color = Gray40)
        Spacer(Modifier.height(6.dp))
        when (val c = check) {
            is PatcherViewModel.PatchCheck.Idle -> {
                Text(
                    "Signature, bundled libraries and the last game run.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray40,
                )
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Run check", onClick = { vm.verifyPatchedApk() })
            }
            is PatcherViewModel.PatchCheck.Running -> {
                Text("Checking\u2026", style = MaterialTheme.typography.bodySmall, color = Accent)
            }
            is PatcherViewModel.PatchCheck.Result -> {
                CheckLine("Signature", if (c.signatureOk) "verified (v1=${c.usedV1} v2=${c.usedV2})" else "NOT VERIFIED", c.signatureOk)
                CheckLine("Bundled libs", "${c.presentLibs}/${c.expectedLibs}", c.presentLibs >= c.expectedLibs && c.expectedLibs > 0)
                CheckLine("Last game run", c.engineCommit.ifBlank { "engine.log not found" }, c.engineCommit.isNotBlank())
            }
            is PatcherViewModel.PatchCheck.Failed -> {
                Text(c.message, style = MaterialTheme.typography.bodySmall, color = AlertRed)
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Retry", onClick = { vm.verifyPatchedApk() })
            }
        }
    }
}

@Composable
private fun CheckLine(label: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            null,
            tint = if (ok) SuccessGreen else AlertRed,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = Gray40)
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) White else AlertRed,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PatchResult(
    vm: PatcherViewModel,
    report: ApkPatcher.PatchReport,
    log: List<String>,
    onInstall: () -> Unit,
    onPatchAgain: () -> Unit,
) {
    AnimatedContent(
        targetState = true,
        transitionSpec = { fadeIn(tween(300)).togetherWith(fadeOut(tween(200))) },
        label = "result",
    ) {
        Column {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SuccessGreen.copy(alpha = 0.08f),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Patch Complete", style = MaterialTheme.typography.titleMedium, color = SuccessGreen)
                    Spacer(Modifier.height(4.dp))
                    val v = report.verification
                    Text(
                        "Output: ${report.signedSizeBytes.mb()}  ·  source ${report.sourceName}\n" +
                            "Added: ${report.addedEntries.size}  ·  kept: ${report.keptCount}  ·  aligned: ${report.alignedStored}\n" +
                            if (report.patchedLibs.isNotEmpty()) "LibCs: PostThink active-item guard applied\n" else "" +
                            "Signature: ${if (v != null && v.verified) "OK (v1=${v.usedV1} v2=${v.usedV2})" else "NOT VERIFIED"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                text = "Install",
                onClick = onInstall,
                icon = { Icon(Icons.Filled.InstallDesktop, null, modifier = Modifier.size(18.dp)) },
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Patch again", onClick = onPatchAgain)
            Spacer(Modifier.height(12.dp))
            MiniConsole(lines = log)
            Spacer(Modifier.height(12.dp))
            PatchHistoryCard(vm)
            Spacer(Modifier.height(8.dp))
            PatchCheckCard(vm)
            Spacer(Modifier.height(6.dp))
            Text(
                "Replaces the existing AMXX install. Signed with the debug key.",
                style = MaterialTheme.typography.bodySmall,
                color = Gray40,
            )
        }
    }
}
