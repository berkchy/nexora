package com.pickle.patcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.positionChange
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pickle.patcher.patcher.PatcherViewModel
import kotlinx.coroutines.launch
import com.pickle.patcher.ui.screens.AddonsScreen
import com.pickle.patcher.ui.screens.CompilerScreen
import com.pickle.patcher.ui.screens.PluginsScreen
import com.pickle.patcher.ui.screens.CrashLogScreen
import com.pickle.patcher.ui.screens.PatchScreen
import com.pickle.patcher.ui.IntroOverlay
import com.pickle.patcher.ui.screens.SettingsScreen
import com.pickle.patcher.ui.screens.toDisplayVersion
import com.pickle.patcher.ui.theme.NexoraTheme
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.Gray60
import com.pickle.patcher.ui.theme.White
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pickle.patcher.jobs.JobProgress
import com.pickle.patcher.ui.JobStrip

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(applicationContext)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NexoraTheme {
                val vm: PatcherViewModel = viewModel()
                LaunchedEffect(Unit) {
                    vm.autoInstallAddons()
                    vm.scanAddonsStatus()
                }
                val bundleVersion by vm.bundleVersion.collectAsState()
                var introKey by remember { mutableStateOf(0L) }
                LaunchedEffect(bundleVersion) {
                    if (vm.shouldReplayIntro(bundleVersion)) introKey++
                }
                Box(Modifier.fillMaxSize()) {
                    PatcherApp(vm)
                    IntroOverlay(playKey = introKey)
                }
            }
        }
    }
}

private enum class Dest(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    Patch("patch", "Patch", Icons.Filled.RocketLaunch, Icons.Outlined.RocketLaunch),
    Compiler("compiler", "Compile", Icons.Filled.Code, Icons.Outlined.Code),
    Addons("addons", "Addons", Icons.Filled.Extension, Icons.Outlined.Extension),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatcherApp(vm: PatcherViewModel) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var showAbout by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val update by vm.appUpdate.collectAsState()

    // No automatic update checks: the user triggers "Update check" from the
    // overflow menu explicitly. When the user downloads an app update from the
    // dialog, installation starts automatically once it finishes.

    androidx.compose.runtime.LaunchedEffect(update) {
        val u = update
        if (u is PatcherViewModel.AppUpdate.Downloaded) {
            vm.installIntentFor(u.file)?.let { context.startActivity(it) }
            vm.consumeDownloaded()
        }
    }

    // The install step of "remove the old client, then install the new one":
    // it fires as soon as the system uninstall returned, or as soon as the
    // rebuild that was needed for it finished.
    val pendingInstall by vm.pendingInstall.collectAsState()
    androidx.compose.runtime.LaunchedEffect(pendingInstall) {
        pendingInstall?.let {
            context.startActivity(it)
            vm.consumePendingInstall()
        }
    }

    val drawerState = androidx.compose.material3.rememberDrawerState(
        androidx.compose.material3.DrawerValue.Closed,
    )
    val drawerOpen = drawerState.isOpen

    NavDrawer(
        vm = vm,
        nav = nav,
        drawerState = drawerState,
        currentRoute = currentRoute,
        onShareLogs = {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val text = vm.buildLogShareText()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "Share logs"))
                }
            }
        },
        onRedownloadBundle = { vm.fetchAndDownloadBundle() },
        onAbout = { showAbout = true },
        onOpenSettings = { nav.navigate("settings") { launchSingleTop = true } },
    ) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                title = {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(id = R.mipmap.ic_launcher),
                            contentDescription = null,
                            modifier = Modifier.height(28.dp).width(28.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(7.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Nexora", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        val version by vm.bundleVersion.collectAsState()
                        val update by vm.appUpdate.collectAsState()
                        if (version.isNotBlank()) {
                            Surface(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                color = if (update is PatcherViewModel.AppUpdate.Available) {
                                    com.pickle.patcher.ui.theme.Tertiary.copy(alpha = 0.18f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                            ) {
                                Text(
                                    text = if (update is PatcherViewModel.AppUpdate.Available) {
                                        "update"
                                    } else {
                                        version.toDisplayVersion()
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (update is PatcherViewModel.AppUpdate.Available) {
                                        com.pickle.patcher.ui.theme.Tertiary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (currentRoute == "plugins") {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(
                                Icons.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { drawerState.open() },
                        ) {
                            Icon(
                                androidx.compose.material.icons.Icons.Filled.Menu,
                                contentDescription = "Open menu",
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = 8.dp,
            ) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp,
                ) {
                    Dest.entries.forEach { dest ->
                        val selected = currentRoute == dest.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    nav.navigate(dest.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) dest.selectedIcon else dest.icon,
                                    contentDescription = dest.label,
                                )
                            },
                            label = {
                                Text(
                                    dest.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // In-app view of the background jobs; the notification is the same
            // state once the app is not in front.
            val jobs by JobProgress.jobs.collectAsState()
            JobStrip(jobs.values.toList())
            NavHost(
                navController = nav,
                startDestination = Dest.Patch.route,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { fadeIn(tween(200)) },
                exitTransition = { fadeOut(tween(200)) },
            ) {
                composable(Dest.Patch.route) { PatchScreen(vm) }
                composable(Dest.Compiler.route) { CompilerScreen(vm, onOpenSettings = { nav.navigate("settings") { launchSingleTop = true } }) }
                composable(Dest.Addons.route) { AddonsScreen(vm) }
                composable("settings") { SettingsScreen(vm, onBack = { nav.popBackStack() }) }
                composable("plugins") { PluginsScreen(vm) }
            }
        }
    }
    }

    val replacePrompt by vm.replacePrompt.collectAsState()
    if (replacePrompt != null) {
        ReplaceDialog(
            fileName = replacePrompt!!.name,
            onConfirm = { vm.confirmReplaceAndPatch() },
            onDismiss = { vm.dismissReplacePrompt() },
        )
    }

    val uninstallPrompt by vm.uninstallPrompt.collectAsState()
    if (uninstallPrompt.isNotEmpty()) {
        UninstallDialog(
            packages = uninstallPrompt,
            onConfirm = { vm.confirmUninstallAndPatch() },
            onDismiss = { vm.dismissUninstallPrompt() },
        )
    }

    if (showAbout) {
        AboutDialog(version = appVersion(context), onDismiss = { showAbout = false })
    }

    val showUpdateDialog = update is PatcherViewModel.AppUpdate.Available ||
        update is PatcherViewModel.AppUpdate.Downloading ||
        update is PatcherViewModel.AppUpdate.UpToDate ||
        update is PatcherViewModel.AppUpdate.Failed
    if (showUpdateDialog) {
        UpdateDialog(vm, update)
    }
}

/**
 * Install asked twice, in two steps, for a reason: the patched APK is signed
 * with our key and cannot go on top of a differently signed copy, so the old
 * one has to go first. Confirming removes it and rebuilds the patch right away.
 */
@Composable
private fun UninstallDialog(
    packages: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove the installed client?") },
        text = {
            Text(
                "These are installed on the device:\n\n" +
                    packages.joinToString("\n") +
                    "\n\nAndroid refuses to replace them with the patched APK " +
                    "(different signature). Removing them starts a fresh patch right away."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Remove and patch") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Asked before a new patch replaces the APK that is already there. Confirming
 * deletes it and the patch continues on its own, which is what the user asked
 * for - no second tap after the dialog.
 */
@Composable
private fun ReplaceDialog(
    fileName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Replace the existing APK?") },
        text = {
            Text(
                "$fileName is already in the patch folder. Deleting it starts a fresh " +
                    "patch right away, and the old file cannot be recovered."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete and patch") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep it") }
        },
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes.toDouble() / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

private fun formatSpeed(bps: Long): String = "${formatBytes(bps)}/s"

/**
 * Side menu. Replaces the overflow menu: everything the three-dot button held
 * is either a place you go (Patch/Compile/Addons/Settings/Plugins) or a one-shot
 * command, and a list that does not change shape while you use it is far easier
 * to reach with a thumb.
 *
 * The sheet carries the whole menu so the items do not slide in on their own
 * and fight the drawer's animation. The open gesture is the problem: a swipe
 * that starts anywhere on the screen would hijack every horizontal scroll and
 * every list in the app, so the edge strip below is the only place that pulls
 * it open.
 */
@Composable
private fun NavDrawer(
    vm: PatcherViewModel,
    nav: androidx.navigation.NavHostController,
    drawerState: androidx.compose.material3.DrawerState,
    currentRoute: String?,
    onShareLogs: () -> Unit,
    onRedownloadBundle: () -> Unit,
    onAbout: () -> Unit,
    onOpenSettings: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current

    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        // Own gesture instead of the default one: only the left edge strip
        // opens the menu, everything else is left to the content underneath.
        gesturesEnabled = false,
        drawerContent = {
            androidx.compose.material3.ModalDrawerSheet(
                modifier = Modifier.drawerEdgeSwipe(
                    drawerState = drawerState,
                    scope = scope,
                    edgeWidthPx = with(density) { DRAWER_EDGE_DP.dp.toPx() },
                ),
            ) {
                DrawerHeader()
                DrawerItem("Patch", Icons.Filled.RocketLaunch, currentRoute == Dest.Patch.route) {
                    drawerState.close()
                    navToTab(nav, Dest.Patch.route, currentRoute)
                }
                DrawerItem("Compile", Icons.Filled.Code, currentRoute == Dest.Compiler.route) {
                    drawerState.close()
                    navToTab(nav, Dest.Compiler.route, currentRoute)
                }
                DrawerItem("Addons", Icons.Filled.Extension, currentRoute == Dest.Addons.route) {
                    drawerState.close()
                    navToTab(nav, Dest.Addons.route, currentRoute)
                }
                DrawerItem("Settings", Icons.Filled.Tune, currentRoute == "settings") {
                    drawerState.close()
                    if (currentRoute != "settings") onOpenSettings()
                }
                DrawerItem("Plugins", Icons.Filled.Code) {
                    drawerState.close()
                    if (currentRoute != "plugins") {
                        nav.navigate("plugins") { launchSingleTop = true }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                DrawerItem("Update check", Icons.Filled.SystemUpdate) {
                    drawerState.close()
                    vm.checkAppUpdate()
                    vm.refreshLibStatus()
                }
                DrawerItem("Redownload bundle", Icons.Filled.Refresh) {
                    drawerState.close()
                    onRedownloadBundle()
                }
                DrawerItem("Share logs", Icons.Filled.Share) {
                    drawerState.close()
                    onShareLogs()
                }
                DrawerItem("About", Icons.Filled.Info) {
                    drawerState.close()
                    onAbout()
                }
            }
        },
    ) {
        content()
    }
}

/**
 * Drawer swipe, restricted to a strip on the left edge.
 *
 * Material's own gesture accepts a drag from anywhere near the edge, which on a
 * phone is most of the screen, so every list swipe and horizontal scroll in the
 * app would end up opening this menu. Only a touch that STARTS inside
 * [edgeWidthPx] arms the drag; anywhere else the gesture is ignored entirely and
 * the touch belongs to the content below.
 *
 * A drag past the halfway point opens, otherwise it snaps back. This runs on the
 * Initial pass so it sees the press before any child does.
 */
private fun Modifier.drawerEdgeSwipe(
    drawerState: androidx.compose.material3.DrawerState,
    scope: androidx.compose.runtime.CoroutineScope,
    edgeWidthPx: Float,
): Modifier = this.pointerInput(edgeWidthPx, drawerState) {
    val openFraction = drawerState.requireAnchors[androidx.compose.material3.DrawerValue.Open]
        .getOffset()
    val closedFraction = drawerState.requireAnchors[androidx.compose.material3.DrawerValue.Closed]
        .getOffset()
    val width = size.width.toFloat()
    if (openFraction <= 0f) return@pointerInput

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)

        if (down.position.x > edgeWidthPx) return@awaitEachGesture

        val anchor = drawerState.requireAnchors[androidx.compose.material3.DrawerValue.Open].getOffset()
        val offset = openFraction
        val slop = viewConfiguration.touchSlop
        var dragging = false
        var dragged = 0f

        drag(down.id) { change ->
            if (!dragging && (change.position.x - down.position.x).let { it > slop || it < -slop }) {
                dragging = true
            }
            if (!dragging) return@drag

            change.consume()
            dragged = (dragged + change.positionChange().x).coerceIn(0f, anchor)
            change.positionWith(dragged)
        }

        if (!dragging) return@awaitEachGesture
        scope.launch { drawerState.animateTo(dragged > anchor * 0.5f) }
    }
}

/**
 * Switches the bottom-bar tabs, keeping each one's state. Same navigation the
 * NavigationBar does, shared so the drawer cannot drift from it.
 */
private fun navToTab(
    nav: androidx.navigation.NavHostController,
    route: String,
    currentRoute: String?,
) {
    if (currentRoute == route) return
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * How wide the left edge strip that pulls the menu open is. Wide enough to find
 * with a thumb, narrow enough that it never steals a swipe meant for a list.
 */
private const val DRAWER_EDGE_DP = 28f

@Composable
private fun DrawerHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(id = R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = Modifier
                .height(36.dp)
                .width(36.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Text("Nexora", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DrawerItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    androidx.compose.material3.NavigationDrawerItem(
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp),
    )
}

@Composable
private fun AboutDialog(version: String, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nexora") },
        text = {
            Column {
                Text("Version: $version", style = MaterialTheme.typography.bodySmall, color = Gray40)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "AMXX bundle injection for CS 1.6 on Android: " +
                        "Pawn compiler, addons manager and crash logs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray40,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("github.com/berkchy/nexora", style = MaterialTheme.typography.bodySmall, color = Gray40)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun appVersion(context: android.content.Context): String {
    val pm = context.packageManager
    return try {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0).versionName
        }
    } catch (_: Throwable) {
        "unknown"
    }
}

@Composable
private fun UpdateDialog(vm: PatcherViewModel, state: PatcherViewModel.AppUpdate) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {
            if (state !is PatcherViewModel.AppUpdate.Downloading) vm.dismissUpdate()
        },
        title = {
            Text(
                when (state) {
                    is PatcherViewModel.AppUpdate.Available -> "New update available ${state.tag}"
                    is PatcherViewModel.AppUpdate.Downloading -> "Downloading ${state.tag}…"
                    is PatcherViewModel.AppUpdate.UpToDate -> "Up to date"
                    is PatcherViewModel.AppUpdate.Failed -> "Update failed"
                    else -> "Update"
                },
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (state is PatcherViewModel.AppUpdate.Available) {
                    if (state.commits.isNotEmpty() || state.notes.isNotBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                        ) {
                            if (state.commits.isNotEmpty()) {
                                Text(
                                    "Changes:",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = White,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                state.commits.forEach { msg ->
                                    Text(
                                        "• $msg",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Gray40,
                                    )
                                }
                            }
                            if (state.notes.isNotBlank()) {
                                if (state.commits.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    HorizontalDivider(color = Gray60)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "Release Notes:",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = White,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                Text(
                                    state.notes.trim().take(1200),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Gray40,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        if (state.size > 0) "Size: ${formatBytes(state.size)}"
                        else "Size: varies with build",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
                if (state is PatcherViewModel.AppUpdate.Downloading) {
                    val frac = if (state.total > 0) {
                        (state.downloaded.toDouble() / state.total).toFloat().coerceIn(0f, 1f)
                    } else 0f
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = frac,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${formatBytes(state.downloaded)} / ${formatBytes(state.total)}  ·  ${formatSpeed(state.bytesPerSec)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Install starts automatically when the download finishes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
                if (state is PatcherViewModel.AppUpdate.UpToDate) {
                    Text(
                        "You have the latest version (${state.tag}).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
                if (state is PatcherViewModel.AppUpdate.Failed) {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
            }
        },
        confirmButton = {
            when (state) {
                is PatcherViewModel.AppUpdate.Available -> {
                    androidx.compose.material3.TextButton(onClick = { vm.downloadAppUpdate() }) {
                        Text("Download")
                    }
                }
                is PatcherViewModel.AppUpdate.Failed -> {
                    androidx.compose.material3.TextButton(onClick = { vm.checkAppUpdate() }) {
                        Text("Retry")
                    }
                }
                else -> {}
            }
        },
        dismissButton = {
            if (state !is PatcherViewModel.AppUpdate.Downloading) {
                androidx.compose.material3.TextButton(onClick = { vm.dismissUpdate() }) {
                    Text("Later")
                }
            }
        },
    )
}
