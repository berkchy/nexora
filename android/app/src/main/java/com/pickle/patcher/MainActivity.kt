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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RocketLaunch
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
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.input.pointer.PointerEventPass
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
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
import com.pickle.patcher.ui.DrawerWidthController
import com.pickle.patcher.ui.screens.SettingsScreen
import com.pickle.patcher.ui.screens.StoreScreen
import com.pickle.patcher.ui.screens.StoreLoginScreen
import com.pickle.patcher.ui.screens.StoreRegisterScreen
import com.pickle.patcher.ui.screens.StoreUploadScreen
import com.pickle.patcher.ui.screens.StoreProfileScreen
import com.pickle.patcher.ui.screens.StoreManageScreen
import com.pickle.patcher.ui.screens.AdminScreen
import com.pickle.patcher.data.StoreApi
import com.pickle.patcher.ui.screens.toDisplayVersion
import com.pickle.patcher.ui.theme.NexoraTheme
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.Gray60
import com.pickle.patcher.ui.theme.White
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pickle.patcher.jobs.JobProgress
import com.pickle.patcher.ui.JobStrip
import com.pickle.patcher.ui.theme.ThemeController
import com.pickle.patcher.ui.theme.ThemePickerDialog

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(applicationContext)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeController: ThemeController = viewModel()
            val themeChoice by themeController.choice.collectAsState()
            NexoraTheme(themeChoice, themeController) {
                val vm: PatcherViewModel = viewModel()
                val drawerWidthController: DrawerWidthController = viewModel()
                LaunchedEffect(Unit) {
                    vm.autoInstallAddons()
                    vm.scanAddonsStatus()
                }
                Box(Modifier.fillMaxSize()) {
                    PatcherApp(vm, drawerWidthController)
                }
            }
        }
    }
}

private enum class Dest(val route: String) {
    Patch("patch"),
    Compiler("compiler"),
    Addons("addons"),
    Store("store"),
    StoreLogin("store/login"),
    StoreRegister("store/register"),
    StoreUpload("store/upload"),
    StoreProfile("store/profile/{username}"),
    StoreManage("store/manage"),
    StoreAdmin("store/admin"),
    // Settings and Plugins sit in the drawer next to the three tasks and are
    // peers of them, so they navigate as tabs too. As plain destinations they
    // stacked on the back stack and the app bar grew a back arrow, which made
    // them read as a sub-page of whichever tab opened them.
    Settings("settings"),
    Plugins("plugins"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatcherApp(vm: PatcherViewModel, drawerWidthController: DrawerWidthController) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route
    val drawerWidthFraction by drawerWidthController.fraction.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var showAbout by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showTheme by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
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
    val drawerOpenScope = androidx.compose.runtime.rememberCoroutineScope()

    NavDrawer(
        vm = vm,
        nav = nav,
        drawerState = drawerState,
        currentRoute = currentRoute,
        drawerWidthFraction = drawerWidthFraction,
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
        onTheme = { showTheme = true },
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
                    // Every destination is a top level tab now, so the menu
                    // button is always the way back out - no screen stacks on
                    // another one any more and no back arrow is needed.
                    IconButton(
                        // DrawerState.open() is suspend; the scope lives in
                        // the drawer, this launches on the app's own.
                        onClick = { drawerOpenScope.launch { drawerState.open() } },
                    ) {
                        Icon(
                            androidx.compose.material.icons.Icons.Filled.Menu,
                            contentDescription = "Open menu",
                        )
                    }
                },
            )
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
            composable(Dest.Compiler.route) {
                CompilerScreen(vm, onOpenSettings = { navToTab(nav, Dest.Settings.route, currentRoute) })
            }
            composable(Dest.Addons.route) { AddonsScreen(vm) }
            composable(Dest.Store.route) {
                val api = StoreApi("https://nexora-store-tawny.vercel.app", context)
                StoreScreen(api, onBack = { nav.popBackStack() }, onUserClick = { u -> nav.navigate("store/profile/$u") })
            }
            composable(Dest.StoreLogin.route) {
                val api = StoreApi("https://nexora-store-tawny.vercel.app", context)
                StoreLoginScreen(api, onBack = { nav.popBackStack() }, onLoginSuccess = { nav.popBackStack() }, onRegister = { nav.navigate(Dest.StoreRegister.route) })
            }
            composable(Dest.StoreRegister.route) {
                val api = StoreApi("https://nexora-store-tawny.vercel.app", context)
                StoreRegisterScreen(api, onBack = { nav.popBackStack() }, onRegisterSuccess = { nav.popBackStack() })
            }
            composable(Dest.StoreUpload.route) {
                val api = StoreApi("https://nexora-store-tawny.vercel.app", context)
                StoreUploadScreen(api, onBack = { nav.popBackStack() }, onUploadSuccess = { nav.popBackStack() })
            }
            composable(Dest.StoreManage.route) {
                val api = StoreApi("https://nexora-store-tawny.vercel.app", context)
                StoreManageScreen(api, onBack = { nav.popBackStack() })
            }
            composable(Dest.StoreAdmin.route) {
                val api = StoreApi("https://nexora-store-tawny.vercel.app", context)
                AdminScreen(api, onBack = { nav.popBackStack() })
            }
            composable(
                route = Dest.StoreProfile.route,
                arguments = listOf(navArgument("username") { type = NavType.StringType }),
            ) { backStack ->
                val username = backStack.arguments?.getString("username") ?: ""
                val api = StoreApi("https://nexora-store-tawny.vercel.app", context)
                StoreProfileScreen(api, username = username, onBack = { nav.popBackStack() }, onEditProfile = { })
            }
            composable(Dest.Settings.route) { SettingsScreen(vm, drawerWidthController) }
            composable(Dest.Plugins.route) { PluginsScreen(vm) }
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

    // The picker repaints everything behind it as the choice changes, which is
    // the point of it being a dialog over the app rather than a screen.
    val themeController = com.pickle.patcher.ui.theme.LocalThemeController.current
    val themeChoice by themeController.choice.collectAsState()
    if (showTheme) {
        ThemePickerDialog(
            choice = themeChoice,
            onPick = themeController::select,
            onMode = themeController::setMode,
            onCustom = themeController::selectCustom,
            onDismiss = { showTheme = false },
        )
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
    drawerWidthFraction: Float,
    onShareLogs: () -> Unit,
    onRedownloadBundle: () -> Unit,
    onAbout: () -> Unit,
    onTheme: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // DrawerState.close() is suspend, and the item callbacks are not. Closing
    // first and navigating after keeps the sheet from animating out on top of
    // the screen it just navigated to.
    fun closeThen(block: () -> Unit) {
        scope.launch {
            drawerState.close()
            block()
        }
    }

    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            androidx.compose.material3.ModalDrawerSheet(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxWidth(drawerWidthFraction)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                DrawerHeader()

                DrawerSection("PATCHER")
                DrawerItem("Patch", Icons.Filled.RocketLaunch, currentRoute == Dest.Patch.route) {
                    closeThen { navToTab(nav, Dest.Patch.route, currentRoute) }
                }
                DrawerItem("Compile", Icons.Filled.Code, currentRoute == Dest.Compiler.route) {
                    closeThen { navToTab(nav, Dest.Compiler.route, currentRoute) }
                }
                DrawerItem("Addons", Icons.Filled.Extension, currentRoute == Dest.Addons.route) {
                    closeThen { navToTab(nav, Dest.Addons.route, currentRoute) }
                }

                DrawerSection("STORE")
                DrawerItem("Store", Icons.Filled.ShoppingCart, currentRoute == Dest.Store.route) {
                    closeThen { navToTab(nav, Dest.Store.route, currentRoute) }
                }
                // Only show account actions when there is a session. Upload and
                // Manage are useless (and confusing) to an anonymous user.
                val loggedIn = com.pickle.patcher.data.StoreSession.token(context) != null
                // Resolve role: use cached value, fall back to a me() fetch so
                // users who logged in before role was persisted still get admin.
                var role by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.pickle.patcher.data.StoreSession.role(context)) }
                androidx.compose.runtime.LaunchedEffect(loggedIn) {
                    if (loggedIn && role == null) {
                        val api = com.pickle.patcher.data.StoreApi("https://nexora-store-tawny.vercel.app", context)
                        api.me().getOrNull()?.optJSONObject("user")?.optString("role")?.let { r ->
                            role = r
                            com.pickle.patcher.data.StoreSession.save(context, com.pickle.patcher.data.StoreSession.token(context)!!, com.pickle.patcher.data.StoreSession.username(context) ?: "", r)
                        }
                    }
                }
                val isAdmin = role == "admin"
                if (!loggedIn) {
                    DrawerItem("Store Login", Icons.Filled.Login, currentRoute == Dest.StoreLogin.route) {
                        closeThen { nav.navigate(Dest.StoreLogin.route) }
                    }
                } else {
                    val uname = com.pickle.patcher.data.StoreSession.username(context)
                    if (uname != null) {
                        DrawerItem("My Profile", Icons.Filled.Person, currentRoute == Dest.StoreProfile.route) {
                            closeThen { nav.navigate("store/profile/$uname") }
                        }
                    }
                    DrawerItem("Store Upload", Icons.Filled.Upload, currentRoute == Dest.StoreUpload.route) {
                        closeThen { nav.navigate(Dest.StoreUpload.route) }
                    }
                    DrawerItem("Store Manage", Icons.Filled.Edit, currentRoute == Dest.StoreManage.route) {
                        closeThen { nav.navigate(Dest.StoreManage.route) }
                    }
                    if (isAdmin) {
                        DrawerItem("Admin", Icons.Filled.AdminPanelSettings, currentRoute == Dest.StoreAdmin.route) {
                            closeThen { nav.navigate(Dest.StoreAdmin.route) }
                        }
                    }
                }

                DrawerSection("SETTINGS")
                DrawerItem("Settings", Icons.Filled.Tune, currentRoute == Dest.Settings.route) {
                    closeThen { navToTab(nav, Dest.Settings.route, currentRoute) }
                }
                DrawerItem("Plugins", Icons.Filled.Extension, currentRoute == Dest.Plugins.route) {
                    closeThen { navToTab(nav, Dest.Plugins.route, currentRoute) }
                }

                DrawerSection("TOOLS")
                DrawerItem("Theme", Icons.Filled.Palette, false, onTheme)
                DrawerItem("Update check", Icons.Filled.SystemUpdate) {
                    closeThen {
                        vm.checkAppUpdate()
                        vm.refreshLibStatus()
                    }
                }
                DrawerItem("Redownload bundle", Icons.Filled.Download) {
                    closeThen { onRedownloadBundle() }
                }
                DrawerItem("Share logs", Icons.Filled.Share) {
                    closeThen { onShareLogs() }
                }
                DrawerItem("About", Icons.Filled.Info) {
                    closeThen { onAbout() }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            content()

            // Invisible strip on the left edge that opens the menu. Laid over
            // the content rather than replacing its gesture, so everything to
            // the right of it keeps working untouched.
            Box(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.CenterStart)
                    .width(DRAWER_EDGE_DP.dp)
                    .fillMaxHeight()
                    .draggable(
                        orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                        state = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
                            // Any real drag opens; there is no partial drag
                            // state to preserve, the sheet animates on its own.
                            if (delta != 0f) scope.launch { drawerState.open() }
                        },
                    ),
            )
        }
    }
}

/**
 * How wide the left edge strip that pulls the menu open is. Wide enough to find
 * with a thumb, narrow enough that it never steals a swipe meant for a list.
 */
private const val DRAWER_EDGE_DP = 28f

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
private fun DrawerSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 4.dp),
    )
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
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                .versionName ?: "unknown"
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
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
