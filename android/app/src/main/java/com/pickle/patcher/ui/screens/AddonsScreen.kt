package com.pickle.patcher.ui.screens

import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pickle.patcher.patcher.AddonsState
import com.pickle.patcher.patcher.PatcherViewModel
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.AlertRed
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.Gray70
import com.pickle.patcher.ui.theme.Gray80
import com.pickle.patcher.ui.theme.Gray90
import com.pickle.patcher.ui.theme.SuccessGreen
import com.pickle.patcher.ui.theme.Gray30

@Composable
fun AddonsScreen(vm: PatcherViewModel) {
    val context = LocalContext.current
    val addons by vm.addons.collectAsState()
    val addonFiles by vm.addonFiles.collectAsState()
    val installPath by vm.installPath.collectAsState()

    val missing = addonFiles.count { !it.installed }
    val outdated = addonFiles.count { it.installed && it.outdated }
    val total = addonFiles.size
    val installed = addonFiles.count { it.installed && !it.outdated }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            ScreenHeader(
                icon = Icons.Filled.FolderOpen,
                title = "Addons",
                subtitle = "Install and manage AMXX addons in your game directory.",
                status = when {
                    addons is AddonsState.Downloading -> "Installing"
                    addons is AddonsState.Error -> "Error"
                    total == 0 -> "Setup"
                    missing > 0 || outdated > 0 -> "Needs attention"
                    else -> "Ready"
                },
                statusTone = when {
                    addons is AddonsState.Error -> Tone.Error
                    addons is AddonsState.Downloading -> Tone.Tertiary
                    total > 0 && missing == 0 && outdated == 0 -> Tone.Primary
                    total > 0 -> Tone.Tertiary
                    else -> Tone.Neutral
                },
                stats = listOf(
                    Stat("Installed", "$installed", Tone.Primary),
                    Stat("Missing", "$missing", if (missing > 0) Tone.Error else Tone.Neutral),
                    Stat("Outdated", "$outdated", if (outdated > 0) Tone.Error else Tone.Neutral),
                ),
            )
        }

        item {
            SectionHeader("Game", "Choose the target install")
            AppCard {
                val game by vm.gameId.collectAsState()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = game == PatcherViewModel.GAME_CSTRIKE,
                        onClick = { vm.setGame(PatcherViewModel.GAME_CSTRIKE) },
                        label = { Text("Counter-Strike 1.6") },
                    )
                    FilterChip(
                        selected = game == PatcherViewModel.GAME_CZERO,
                        onClick = { vm.setGame(PatcherViewModel.GAME_CZERO) },
                        label = { Text("Condition Zero") },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    installPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "addons/ will be installed under this path. The CZ folder must " +
                        "contain the game files; launch it with -game czero.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionHeader("Storage access", "All files permission")
            PermissionCard()
        }

        item {
            SectionHeader("Addon status", "What is installed")
            AppCard {
                if (total == 0) {
                    Text(
                        "Addons ship separately from the mod bundle.\nTap \"Download & Install\" below to fetch and install them into your game directory.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatCard(Stat("Total", "$total"), Modifier.weight(1f))
                        StatCard(
                            Stat("Ready", "$installed", Tone.Primary),
                            Modifier.weight(1f),
                        )
                        StatCard(
                            Stat(
                                "MISSING",
                                "${missing + outdated}",
                                if (missing + outdated > 0) Tone.Error else Tone.Neutral,
                            ),
                            Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        if (total > 0 && (missing > 0 || outdated > 0)) {
            item {
                SectionHeader("Repair queue", "Missing or outdated files")
                AppCard {
                    addonFiles.filter { !it.installed || it.outdated }.forEach { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    file.path.removePrefix("addons/"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (!file.installed) "missing" else "outdated — will be replaced",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionHeader("Install", "Fetch and write files")
            AppCard {
                when (val s = addons) {
                    is AddonsState.Downloading -> {
                        Text(
                            s.step,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        AppProgressBar(s.percent)
                    }
                    is AddonsState.Done -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.CheckCircle, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                s.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    is AddonsState.Error -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Warning, null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                s.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    else -> {}
                }

                Spacer(Modifier.height(10.dp))

                val permLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == android.app.Activity.RESULT_OK) {
                        vm.fetchAndInstallAddons()
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = "Download & Install Addons",
                        onClick = {
                            val hasPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                Environment.isExternalStorageManager()
                            } else true
                            if (hasPerm) {
                                vm.fetchAndInstallAddons()
                            } else {
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                                permLauncher.launch(intent)
                            }
                        },
                        icon = {
                            Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(18.dp))
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (addonFiles.isNotEmpty()) {
            item {
                SectionHeader("All files", "${addonFiles.size} tracked files")
            }
            items(addonFiles) { file ->
                val ok = file.installed && !file.outdated
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = if (ok) {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                            contentDescription = null,
                            tint = if (ok) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                file.path.removePrefix("addons/"),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (ok) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (file.installed && file.outdated) {
                                Text(
                                    "outdated",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard() {
    val context = LocalContext.current
    val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (hasPermission) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (hasPermission) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (hasPermission) "All files access granted" else "All files access required",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (hasPermission) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Text(
                    "Needed to install addons into the game directory.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!hasPermission) {
            Spacer(Modifier.height(10.dp))
            SecondaryButton(
                text = "Grant Permission",
                onClick = {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}")
                    )
                    launcher.launch(intent)
                },
                icon = { Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}
