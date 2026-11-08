package com.pickle.patcher.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pickle.patcher.data.StoreApi
import com.pickle.patcher.data.StoreSession
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.WarnYellow
import com.pickle.patcher.ui.theme.Gray30
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.pickle.patcher.ui.MarkdownText
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(api: StoreApi, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val token = StoreSession.token(context) ?: ""
    var tab by remember { mutableStateOf(0) } // 0 posts, 1 users

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Admin",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    label = { Text("Posts") },
                )
                FilterChip(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    label = { Text("Users") },
                )
            }
            Box(Modifier.fillMaxSize()) {
                if (tab == 0) AdminPosts(api, token) else AdminUsers(api, token)
            }
        }
    }
}

@Composable
private fun AdminPosts(api: StoreApi, token: String) {
    val scope = rememberCoroutineScope()
    var statusFilter by remember { mutableStateOf<String?>(null) } // null = all
    var items by remember { mutableStateOf<JSONArray?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            loading = true; error = null
            api.adminListItems(token, statusFilter).fold(
                onSuccess = { json ->
                    items = json.optJSONArray("data")
                    loading = false
                },
                onFailure = { t ->
                    error = t.message ?: "Failed"
                    loading = false
                },
            )
        }
    }

    LaunchedEffect(statusFilter) { load() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            icon = Icons.Filled.Article,
            title = "Posts",
            subtitle = "Review, approve, and moderate store posts.",
            status = "Moderation",
            statusTone = Tone.Secondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf<Pair<String?, String>>(null to "All", "pending" to "Pending", "approved" to "Approved", "rejected" to "Rejected").forEach { (v, label) ->
                FilterChip(
                    selected = statusFilter == v,
                    onClick = { statusFilter = v },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
            else -> {
                val list = items
                if (list == null || list.length() == 0) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No items", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items((0 until list.length()).map { list.getJSONObject(it) }) { obj ->
                            AdminPostCard(obj, api, token, onAction = { load() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminPostCard(obj: JSONObject, api: StoreApi, token: String, onAction: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val id = obj.optString("id")
    val status = obj.optString("status", "pending")
    var expanded by remember { mutableStateOf(false) }
    var previewFile by remember { mutableStateOf<JSONObject?>(null) }
    val description = obj.optString("description", "").trim()
    val libs = obj.optJSONArray("libs")
    val libCount = libs?.length() ?: 0
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val logoUrl = obj.optString("logoUrl", "").takeIf { it.isNotBlank() }
                if (logoUrl != null) {
                    AsyncImage(
                        model = logoUrl,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                } else {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Article,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        obj.optString("title", "Untitled"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "by ${obj.optJSONObject("user")?.optString("username") ?: "?"}  •  v${obj.optString("version", "1.0.0")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    status.replaceFirstChar { it.titlecase() },
                    tone = when (status) {
                        "approved" -> Tone.Primary
                        "rejected" -> Tone.Error
                        else -> Tone.Tertiary
                    },
                    icon = when (status) {
                        "approved" -> Icons.Filled.CheckCircle
                        "rejected" -> Icons.Filled.Cancel
                        else -> Icons.Filled.Schedule
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
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
                        if (libCount > 0) "$libCount file(s) in this post" else "No files attached",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Hide content" else "Show content",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                if (description.isNotBlank()) {
                    Text(
                        "Description",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    MarkdownText(
                        description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (libCount > 0) {
                    Text(
                        "Package files",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    val fileList = libs!!
                    (0 until fileList.length()).map { fileList.getJSONObject(it) }.forEach { file ->
                        val name = file.optString("name", "Untitled")
                        val url = file.optString("url", "")
                        val size = formatFileSize(file.optLong("size", 0L))
                        val extension = name.substringAfterLast('.', "").lowercase()
                        val previewable = extension in setOf("sma", "inc", "cfg", "ini", "txt", "md", "lua", "json", "log", "cs", "zip")
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = url.isNotBlank()) {
                                    if (previewable) {
                                        previewFile = file
                                    } else {
                                        runCatching {
                                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                                        }
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (previewable) Icons.Filled.Description else Icons.Filled.AttachFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(19.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    size,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                if (previewable) Icons.Filled.Visibility else Icons.Filled.OpenInNew,
                                contentDescription = if (previewable) "Preview $name" else "Open $name",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (status != "approved") {
                    PrimaryButton(
                        text = "Approve",
                        onClick = {
                            scope.launch { api.adminPatchItem(token, id, "approved"); onAction() }
                        },
                        modifier = Modifier.weight(1f),
                        icon = { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
                if (status != "rejected") {
                    SecondaryButton(
                        text = "Reject",
                        onClick = {
                            scope.launch { api.adminPatchItem(token, id, "rejected"); onAction() }
                        },
                        modifier = Modifier.weight(1f),
                        icon = { Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
                TextButton(
                    onClick = {
                        scope.launch { api.adminDeleteItem(token, id); onAction() }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete post", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete")
                }
            }

            previewFile?.let { file ->
                val name = file.optString("name", "Untitled")
                val url = file.optString("url", "")
                val extension = name.substringAfterLast('.', "").lowercase()
                var content by remember(url) { mutableStateOf("Loading $name…") }
                var loadFailed by remember(url) { mutableStateOf(false) }
                LaunchedEffect(url) {
                    withContext(Dispatchers.IO) {
                        if (extension == "zip") {
                            content = listZipEntries(url).joinToString("\n")
                            loadFailed = false
                        } else {
                            runCatching {
                                java.net.URL(url).openStream().bufferedReader().use { reader ->
                                    reader.readText().take(120_000)
                                }
                            }.onSuccess {
                                content = it
                                loadFailed = false
                            }.onFailure {
                                content = it.message ?: "Could not read $name"
                                loadFailed = true
                            }
                        }
                    }
                }
                AlertDialog(
                    onDismissRequest = { previewFile = null },
                    title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    text = {
                        Box(
                            Modifier
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            Text(
                                content,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = if (loadFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                softWrap = false,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { previewFile = null }) { Text("Close") }
                    },
                )
            }
        }
    }
}

private fun listZipEntries(url: String): List<String> {
    val entries = mutableListOf<String>()
    return runCatching {
        java.util.zip.ZipInputStream(java.net.URL(url).openStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val size = if (entry.size >= 0) formatFileSize(entry.size) else "unknown size"
                    entries += "$size  ${entry.name}"
                }
                if (entries.size >= 200) {
                    entries += "… (truncated)"
                    break
                }
                zip.closeEntry()
            }
        }
        if (entries.isEmpty()) listOf("Zip archive is empty") else entries
    }.getOrElse { listOf(it.message ?: "Could not inspect zip archive") }
}

@Composable
private fun AdminUsers(api: StoreApi, token: String) {
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<JSONArray?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            loading = true; error = null
            api.adminListUsers(token).fold(
                onSuccess = { json ->
                    users = json.optJSONArray("data")
                    loading = false
                },
                onFailure = { t ->
                    error = t.message ?: "Failed"
                    loading = false
                },
            )
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            icon = Icons.Filled.People,
            title = "Users",
            subtitle = "Manage roles, permissions, and accounts.",
            status = "Team",
            statusTone = Tone.Secondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
            else -> {
                val list = users
                if (list == null || list.length() == 0) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No users", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items((0 until list.length()).map { list.getJSONObject(it) }) { obj ->
                            AdminUserCard(obj, api, token, onAction = { load() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminUserCard(obj: JSONObject, api: StoreApi, token: String, onAction: () -> Unit) {
    val scope = rememberCoroutineScope()
    val id = obj.optString("id")
    val role = obj.optString("role", "user")
    val username = obj.optString("username")
    val email = obj.optString("email")
    val displayName = obj.optString("displayName", "").takeIf { it.isNotBlank() }
    val itemCount = obj.optJSONObject("_count")?.optInt("items") ?: 0
    val isOwner = role == "owner"
    val permsArr = obj.optJSONArray("permissions")
    val initialPerms = remember { mutableSetOf<String>().apply {
        if (isOwner) addAll(listOf("post_uploader","post_approver","post_manager","user_manager"))
        else if (permsArr != null) for (i in 0 until permsArr.length()) add(permsArr.optString(i))
    } }
    var showPerms by remember { mutableStateOf(false) }
    var perms by remember { mutableStateOf(initialPerms) }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val avatarUrl = obj.optString("avatarUrl", "").takeIf { it.isNotBlank() }
                if (avatarUrl != null) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        displayName ?: username,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "$email  •  $itemCount posts",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StatusPill(
                        role.replaceFirstChar { it.titlecase() },
                        tone = if (role == "admin") Tone.Tertiary else Tone.Neutral,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (!isOwner) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = { showPerms = true }) {
                        Text("Permissions", color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = {
                        scope.launch {
                            val newRole = if (role == "admin") "user" else "admin"
                            api.adminPatchUser(token, id, role = newRole)
                            onAction()
                        }
                    }) {
                        Text(
                            if (role == "admin") "Demote" else "Promote",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    TextButton(onClick = {
                        scope.launch { api.adminDeleteUser(token, id); onAction() }
                    }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (showPerms) {
        AlertDialog(
            onDismissRequest = { showPerms = false },
            title = { Text("Permissions for $username") },
            text = {
                Column {
                    listOf("post_uploader","post_approver","post_manager","user_manager").forEach { p ->
                        val on = perms.contains(p)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Checkbox(checked = on, onCheckedChange = { checked ->
                                val next = perms.toMutableSet()
                                if (checked) next.add(p) else next.remove(p)
                                perms = next
                            })
                            Spacer(Modifier.width(8.dp))
                            Text(p, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        api.adminPatchUser(token, id, permissions = perms.toList())
                        showPerms = false
                        onAction()
                    }
                }) { Text("Save", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { showPerms = false }) { Text("Cancel") }
            },
        )
    }
}
