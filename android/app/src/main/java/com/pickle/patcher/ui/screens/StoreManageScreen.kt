package com.pickle.patcher.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pickle.patcher.data.StoreApi
import com.pickle.patcher.data.StoreSession
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.AlertRed
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.White
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun StoreManageScreen(
    api: StoreApi,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<JSONArray?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var editTarget by remember { mutableStateOf<JSONObject?>(null) }

    fun reload() {
        loading = true
        scope.launch {
            val username = StoreSession.username(context)
            if (username == null) { loading = false; return@launch }
            // Get user profile to find their items
            api.getUserProfile(username).fold(
                onSuccess = { json ->
                    val profile = json.optJSONObject("profile")
                    items = profile?.optJSONArray("items")
                    loading = false
                },
                onFailure = { t ->
                    error = t.message ?: "Failed to load"
                    loading = false
                },
            )
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Edit, contentDescription = null, tint = Accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("Manage Libs", style = MaterialTheme.typography.titleLarge, color = White)
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error!!, color = Gray40) }
            else -> {
                val list = items
                if (list == null || list.length() == 0) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No items yet", color = Gray40) }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        items((0 until list.length()).map { list.getJSONObject(it) }) { obj ->
                            val id = obj.optString("id")
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(obj.optString("title"), style = MaterialTheme.typography.bodyMedium, color = White)
                                    Text("v${obj.optString("version")} · ${obj.optJSONArray("libs")?.length() ?: 0} lib(s)", style = MaterialTheme.typography.labelSmall, color = Gray40)
                                    Spacer(Modifier.height(8.dp))
                                    Row {
                                        TextButton(onClick = { editTarget = obj }) { Text("Edit", color = Accent) }
                                        TextButton(onClick = { deleteTarget = id }) { Text("Delete", color = AlertRed) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation
    deleteTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete item?", color = AlertRed) },
            text = { Text("This cannot be undone.", color = Gray40) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        api.deleteItem(api.token ?: "", id).fold(
                            onSuccess = { deleteTarget = null; reload() },
                            onFailure = { t -> error = t.message; deleteTarget = null },
                        )
                    }
                }) { Text("Delete", color = AlertRed) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = Gray40) }
            },
        )
    }

    // Edit dialog
    editTarget?.let { obj ->
        var title by remember { mutableStateOf(obj.optString("title")) }
        var description by remember { mutableStateOf(obj.optString("description")) }
        var version by remember { mutableStateOf(obj.optString("version")) }
        var saving by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("Edit item", color = White) },
            text = {
                Column {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = version, onValueChange = { version = it }, label = { Text("Version") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        saving = true
                        scope.launch {
                            api.updateItem(api.token ?: "", obj.optString("id"), title, description, version).fold(
                                onSuccess = { saving = false; editTarget = null; reload() },
                                onFailure = { t -> error = t.message; saving = false },
                            )
                        }
                    },
                    enabled = !saving,
                ) { Text("Save", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) { Text("Cancel", color = Gray40) }
            },
        )
    }
}
