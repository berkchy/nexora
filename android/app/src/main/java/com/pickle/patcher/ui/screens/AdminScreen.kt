package com.pickle.patcher.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.pickle.patcher.ui.theme.White
import kotlinx.coroutines.launch
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
                title = { Text("Admin", color = White) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null, tint = White) }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Article, contentDescription = null) },
                    label = { Text("Posts") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.People, contentDescription = null) },
                    label = { Text("Users") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (tab == 0) AdminPosts(api, token) else AdminUsers(api, token)
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
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf<Pair<String?, String>>(null to "All", "pending" to "Pending", "approved" to "Approved", "rejected" to "Rejected").forEach { (v, label) ->
                FilterChip(
                    selected = statusFilter == v,
                    onClick = { statusFilter = v },
                    label = { Text(label) },
                )
            }
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error!!, color = Gray40) }
            else -> {
                val list = items
                if (list == null || list.length() == 0) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No items", color = Gray40) }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    val id = obj.optString("id")
    val status = obj.optString("status", "pending")
    val statusColor = when (status) {
        "approved" -> Accent
        "rejected" -> WarnYellow
        else -> Gray40
    }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val logoUrl = obj.optString("logoUrl", "").takeIf { it.isNotBlank() }
                if (logoUrl != null) {
                    AsyncImage(model = logoUrl, contentDescription = null, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(obj.optString("title", "Untitled"), style = MaterialTheme.typography.titleSmall, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("by ${obj.optJSONObject("user")?.optString("username") ?: "?"}  •  v${obj.optString("version", "1.0.0")}", style = MaterialTheme.typography.labelSmall, color = Gray40)
                }
                Text(status, style = MaterialTheme.typography.labelSmall, color = statusColor)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status != "approved") {
                    TextButton(onClick = {
                        scope.launch { api.adminPatchItem(token, id, "approved"); onAction() }
                    }) { Text("Approve", color = Accent) }
                }
                if (status != "rejected") {
                    TextButton(onClick = {
                        scope.launch { api.adminPatchItem(token, id, "rejected"); onAction() }
                    }) { Text("Reject", color = WarnYellow) }
                }
                TextButton(onClick = {
                    scope.launch { api.adminDeleteItem(token, id); onAction() }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
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

    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error!!, color = Gray40) }
        else -> {
            val list = users
            if (list == null || list.length() == 0) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No users", color = Gray40) }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((0 until list.length()).map { list.getJSONObject(it) }) { obj ->
                        AdminUserCard(obj, api, token, onAction = { load() })
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
    val perms = remember { mutableStateOf<MutableSet<String>>(mutableSetOf<String>().apply {
        val arr = obj.optJSONArray("permissions")
        if (arr != null) for (i in 0 until arr.length()) add(arr.optString(i))
    }) }
    val isAdminOrOwner = role == "admin" || role == "owner"
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val avatarUrl = obj.optString("avatarUrl", "").takeIf { it.isNotBlank() }
            if (avatarUrl != null) {
                AsyncImage(model = avatarUrl, contentDescription = null, modifier = Modifier.size(40.dp).padding(4.dp))
            } else {
                Surface(shape = MaterialTheme.shapes.small, color = Accent.copy(alpha = 0.2f), modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(username.take(1).uppercase(), color = Accent)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(displayName ?: username, style = MaterialTheme.typography.titleSmall, color = White)
                Text("$email  •  $itemCount posts", style = MaterialTheme.typography.labelSmall, color = Gray40)
                Text("role: $role", style = MaterialTheme.typography.labelSmall, color = if (role == "admin") WarnYellow else Gray40)
            }
            if (!isAdminOrOwner) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("post_approver","post_manager","user_manager").forEach { p ->
                        val on = perms.value.contains(p)
                        TextButton(onClick = {
                            scope.launch {
                                val next = perms.value.toMutableSet()
                                if (on) next.remove(p) else next.add(p)
                                perms.value = next
                                api.adminPatchUser(token, id, permissions = next.toList())
                                onAction()
                            }
                        }) { Text(p.removePrefix("post_").removePrefix("user_"), color = if (on) WarnYellow else Gray40) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    scope.launch {
                        val newRole = if (role == "admin") "user" else "admin"
                        api.adminPatchUser(token, id, role = newRole)
                        onAction()
                    }
                }) { Text(if (role == "admin") "Demote" else "Promote", color = Accent) }
                TextButton(onClick = {
                    scope.launch { api.adminDeleteUser(token, id); onAction() }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
