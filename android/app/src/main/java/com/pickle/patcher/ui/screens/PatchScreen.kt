package com.pickle.patcher.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import com.pickle.patcher.lib.ApkPatcher
import com.pickle.patcher.patcher.AddonsState
import com.pickle.patcher.patcher.BundleState
import com.pickle.patcher.patcher.LibInfo
import com.pickle.patcher.patcher.PatchUiState
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

private val mbFmt = DecimalFormat("0.0")
private fun Long.mb(): String = "${mbFmt.format(this / 1048576.0)} MB"

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

        SectionHeader("ADDONS")
        AddonsQuickCard(vm)

        Spacer(Modifier.height(16.dp))
    }
}

private fun displayAbi(abi: String): String = when (abi) {
    "armeabi-v7a" -> "Arm32"
    else -> "Arm64"
}

@Composable
private fun SourceCard(vm: PatcherViewModel) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            vm.pickSource(it)
        }
    }
    val source by vm.source.collectAsState()

    AppCard {
        if (source == null) {
            Text(
                "No APK selected.",
                style = MaterialTheme.typography.bodyMedium,
                color = Gray40,
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                text = "Select APK",
                onClick = { picker.launch(arrayOf("application/vnd.android.package-archive")) },
                icon = { Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(18.dp)) },
            )
        } else {
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
                GhostButton("Change", onClick = { picker.launch(arrayOf("application/vnd.android.package-archive")) })
            }
        }
    }
}

@Composable
private fun BundleCard(vm: PatcherViewModel) {
    val bundleState by vm.bundle.collectAsState()
    val abi by vm.abi.collectAsState()
    val sourceAbis = vm.sourceAbis

    AppCard {
        // ABI selector — integrated into bundle card
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "ABI",
                style = MaterialTheme.typography.titleSmall,
                color = Gray40,
            )
            Spacer(Modifier.width(12.dp))
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                vm.supportedAbis.forEach { a ->
                    FilterChip(
                        selected = a == abi,
                        onClick = { vm.setAbi(a) },
                        enabled = sourceAbis.isEmpty() || a in sourceAbis,
                        label = { Text(displayAbi(a)) },
                    )
                }
            }
        }
        if (sourceAbis.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Source: ${sourceAbis.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = Gray60,
            )
        }

        Spacer(Modifier.height(12.dp))

        when (val bs = bundleState) {
            is BundleState.None -> {
                Text(
                    "A mod bundle is required.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Gray40,
                )
                Spacer(Modifier.height(10.dp))
                PrimaryButton(
                    text = "Download",
                    onClick = { vm.fetchAndDownloadBundle() },
                    icon = { Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp)) },
                )
            }
            is BundleState.Downloading -> {
                if (bs.tagName.isNotBlank()) {
                    Text(
                        bs.tagName,
                        style = MaterialTheme.typography.titleSmall,
                        color = Accent,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (bs.currentFile.isNotBlank()) {
                    Text(
                        "Downloading ${bs.fileIndex + 1}/${bs.fileTotal}: ${bs.currentFile}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                } else {
                    Text("Downloading…", style = MaterialTheme.typography.bodySmall, color = Gray40)
                }
                Spacer(Modifier.height(8.dp))
                AppProgressBar(bs.percent)
            }
            is BundleState.DownloadError -> {
                Text(bs.message, style = MaterialTheme.typography.bodySmall, color = AlertRed)
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Retry", onClick = { vm.fetchAndDownloadBundle() })
            }
            is BundleState.Loaded -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle, null,
                        tint = SuccessGreen, modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Libs loaded", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Up to date",
                            style = MaterialTheme.typography.bodySmall, color = Gray40,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Refresh", onClick = { vm.fetchAndDownloadBundle() })
            }
            is BundleState.Ready -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle, null,
                        tint = SuccessGreen, modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(bs.bundleName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${bs.entries} entries  ·  v${bs.version}",
                            style = MaterialTheme.typography.bodySmall, color = Gray40,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibListCard(vm: PatcherViewModel) {
    val libs by vm.libs.collectAsState()

    AppCard {
        if (libs.isEmpty()) {
            Text(
                "No .so files found in libs directory.",
                style = MaterialTheme.typography.bodyMedium,
                color = Gray40,
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
                    } else "Not installed",
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
                PatchSteps(s.step, s.progress)
            }
            is PatchUiState.Done -> {
                PatchResult(s.report, onInstall = { context.startActivity(vm.installIntent()) })
            }
            is PatchUiState.Failed -> {
                Icon(Icons.Filled.Warning, null, tint = AlertRed, modifier = Modifier.size(20.dp))
                Spacer(Modifier.height(4.dp))
                Text("Patch failed", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(s.message, style = MaterialTheme.typography.bodySmall, color = Gray40)
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Try Again", onClick = { vm.reset() })
            }
        }
    }

    if (showComponents && canPatch && state is PatchUiState.Idle) {
        val components = remember(vm) { vm.patchComponents() }
        PatchComponentsDialog(
            components = components,
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
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val allKeys = components.map { it.key }
    var selected by remember { mutableStateOf(allKeys.toSet()) }
    val allSelected = selected.size == allKeys.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Patch Components") },
        text = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selected = if (allSelected) emptySet() else allKeys.toSet()
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = {
                            selected = if (it) allKeys.toSet() else emptySet()
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (allSelected) "All components (${selected.size})" else "Select all",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (components.isEmpty()) {
                    Text(
                        "No components found in the loaded bundle.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        components.forEach { c ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (c.key in selected) {
                                            selected - c.key
                                        } else {
                                            selected + c.key
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = c.key in selected,
                                    onCheckedChange = { on ->
                                        selected = if (on) selected + c.key else selected - c.key
                                    },
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        c.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (c.key in selected) White else Gray40,
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
                Spacer(Modifier.height(8.dp))
                Text(
                    "Only selected components will be injected into the APK.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray60,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected) },
                enabled = selected.isNotEmpty(),
            ) {
                Text("Patch", color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Gray60) }
        },
    )
}

@Composable
private fun PatchSteps(step: ApkPatcher.Step, progress: Float) {
    val steps = listOf(
        ApkPatcher.Step.ANALYZE to "Analyze",
        ApkPatcher.Step.INJECT to "Inject",
        ApkPatcher.Step.ALIGN to "Align",
        ApkPatcher.Step.SIGN to "Sign",
        ApkPatcher.Step.VERIFY to "Verify",
    )
    val currentIndex = steps.indexOfFirst { it.first == step }

    Column {
        steps.forEachIndexed { i, (_, label) ->
            val st = when {
                i < currentIndex -> StepState.DONE
                i == currentIndex -> StepState.ACTIVE
                else -> StepState.PENDING
            }
            StepRow(
                title = label,
                detail = if (st == StepState.ACTIVE) "${(progress * 100).toInt()}%" else "",
                state = st,
            )
            if (i < steps.lastIndex) Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        AppProgressBar(progress)
    }
}

@Composable
private fun PatchResult(report: ApkPatcher.PatchReport, onInstall: () -> Unit) {
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
            Spacer(Modifier.height(6.dp))
            Text(
                "Replaces the existing AMXX install. Signed with the debug key.",
                style = MaterialTheme.typography.bodySmall,
                color = Gray40,
            )
        }
    }
}

@Composable
private fun AddonsQuickCard(vm: PatcherViewModel) {
    val addons by vm.addons.collectAsState()
    val addonFiles by vm.addonFiles.collectAsState()
    val missing = addonFiles.count { !it.installed }
    val total = addonFiles.size

    AppCard {
        if (total == 0) {
            Text(
                "No addons scanned. Load a bundle first.",
                style = MaterialTheme.typography.bodyMedium,
                color = Gray40,
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                text = "Scan Addons",
                onClick = { vm.scanAddonsStatus() },
                icon = { Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp)) },
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (missing > 0) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = if (missing > 0) AlertRed else SuccessGreen,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (missing > 0) "$missing addon files missing" else "All $total addon files installed",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (missing > 0) AlertRed else SuccessGreen,
                    )
                    Text(
                        vm.installPath.value,
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (missing > 0) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = "Install Missing",
                        onClick = { vm.installAddonsFromBundle() },
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "Details",
                        onClick = { vm.scanAddonsStatus() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        when (val s = addons) {
            is AddonsState.Done -> {
                Spacer(Modifier.height(8.dp))
                Text(s.message, style = MaterialTheme.typography.bodySmall, color = SuccessGreen)
            }
            is AddonsState.Error -> {
                Spacer(Modifier.height(8.dp))
                Text(s.message, style = MaterialTheme.typography.bodySmall, color = AlertRed)
            }
            else -> {}
        }
    }
}
