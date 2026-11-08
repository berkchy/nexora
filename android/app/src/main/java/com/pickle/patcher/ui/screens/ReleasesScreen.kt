package com.pickle.patcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pickle.patcher.patcher.AddonsState
import com.pickle.patcher.patcher.BundleState
import com.pickle.patcher.patcher.PatcherViewModel
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.AlertRed
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.Gray70
import com.pickle.patcher.ui.theme.Gray80
import com.pickle.patcher.ui.theme.Gray90
import com.pickle.patcher.ui.theme.SuccessGreen
import com.pickle.patcher.ui.theme.Gray30
import java.io.File

@Composable
fun ReleasesScreen(vm: PatcherViewModel) {
    val scroll = rememberScrollState()
    val note by vm.releaseNote.collectAsState()
    val bundle by vm.bundle.collectAsState()
    val addons by vm.addons.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        ScreenHeader(
            icon = Icons.Filled.CloudDownload,
            title = "Downloads",
            subtitle = "Install addons, bundles, and app updates.",
            status = when {
                addons is AddonsState.Downloading || bundle is BundleState.Downloading -> "Downloading"
                addons is AddonsState.Error || bundle is BundleState.DownloadError -> "Error"
                addons is AddonsState.Done || bundle is BundleState.Ready || bundle is BundleState.Loaded -> "Ready"
                else -> "Idle"
            },
            statusTone = when {
                addons is AddonsState.Downloading || bundle is BundleState.Downloading -> Tone.Tertiary
                addons is AddonsState.Error || bundle is BundleState.DownloadError -> Tone.Error
                addons is AddonsState.Done || bundle is BundleState.Ready || bundle is BundleState.Loaded -> Tone.Primary
                else -> Tone.Neutral
            },
        )

        Spacer(Modifier.height(20.dp))

        SectionHeader("01", "Addons", "Plugins, modules, and configs")
        AppCard {
            Text(
                "Plugins, modules, and configs for cstrike/.",
                style = MaterialTheme.typography.bodySmall,
                color = Gray40,
            )
            Spacer(Modifier.height(10.dp))

            when (val a = addons) {
                is AddonsState.Downloading -> {
                    AppProgressBar(a.percent)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        a.step,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is AddonsState.Done -> StatusRow(Tone.Primary, a.message)
                is AddonsState.Error -> StatusRow(Tone.Error, a.message)
                is AddonsState.None -> StatusRow(Tone.Neutral, "Not downloaded yet.")
            }

            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                text = "Download & Install",
                onClick = { vm.fetchAndInstallAddons() },
                icon = { Icon(Icons.Filled.CloudDownload, null, modifier = Modifier.size(18.dp)) },
            )
        }

        Spacer(Modifier.height(20.dp))

        SectionHeader("02", "Mod bundle", "Game libraries and metadata")
        AppCard {
            note?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CloudDownload,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                }
                Spacer(Modifier.height(8.dp))
            }

            when (val b = bundle) {
                is BundleState.Downloading -> {
                    if (b.currentFile.isNotBlank()) {
                        Text(
                            "Downloading ${b.fileIndex + 1}/${b.fileTotal}: ${b.currentFile}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "Downloading…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is BundleState.DownloadError -> StatusRow(Tone.Error, b.message)
                is BundleState.Ready -> StatusRow(Tone.Primary, "${b.bundleName}  ·  v${b.version}")
                is BundleState.Loaded -> StatusRow(Tone.Primary, "Libs loaded  ·  Up to date")
                is BundleState.None -> StatusRow(Tone.Neutral, "Not downloaded yet.")
            }

            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                text = "Download",
                onClick = { vm.fetchAndDownloadBundle() },
                icon = { Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp)) },
            )

            if (vm.hasCachedBundle) {
                Spacer(Modifier.height(6.dp))
                SecondaryButton(
                    text = "Load cached",
                    onClick = { vm.useCachedBundle() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        SectionHeader("03", "App update", "Check for a newer patcher")
        AppCard {
            val context = LocalContext.current
            val pm = context.packageManager
            val current = try {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName
                } else {
                    @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0).versionName
                }
            } catch (_: Throwable) {
                null
            }
            Text(
                "Current: ${current ?: "unknown"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Check GitHub releases for a newer patcher APK. If one is found, " +
                    "a popup offers download with progress and automatic install.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                text = "Check for updates",
                onClick = { vm.checkAppUpdate() },
                icon = { Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp)) },
            )
        }

        Spacer(Modifier.height(20.dp))

        SectionHeader("04", "Game folders", "Verify your install layout")
        GameFoldersCard()
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun StatusRow(tone: Tone, text: String) {
    val (_, content) = when (tone) {
        Tone.Primary ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
        Tone.Error ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
        else ->
            MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            when (tone) {
                Tone.Primary -> Icons.Filled.CheckCircle
                Tone.Error -> Icons.Filled.Error
                else -> Icons.Filled.Info
            },
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = content)
    }
}

@Composable
private fun GameFoldersCard() {
    val folders = listOf(
        "addons/amxmodx/bin/" to "Core binaries",
        "addons/amxmodx/modules/" to "Module .so files",
        "addons/amxmodx/plugins/" to "Compiled plugins",
        "addons/amxmodx/configs/" to "Configuration files",
        "addons/amxmodx/includes/" to "Pawn headers",
    )

    AppCard {
        folders.forEachIndexed { index, (path, desc) ->
            val dir = File("/storage/emulated/0/xash/cstrike/$path")
            val exists = dir.exists()
            if (index > 0) Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = if (exists) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.FolderSpecial, null,
                        tint = if (exists) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            path,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (exists) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                        )
                        Text(
                            if (exists) desc else "$desc · missing",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (exists) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                        )
                    }
                }
            }
        }
    }
}
