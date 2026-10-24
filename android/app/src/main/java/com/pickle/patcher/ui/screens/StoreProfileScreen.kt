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
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.White
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun StoreProfileScreen(
    api: StoreApi,
    username: String,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(username) {
        loading = true
        api.getUserProfile(username).fold(
            onSuccess = { json ->
                profile = json.optJSONObject("profile")
                loading = false
            },
            onFailure = { t ->
                error = t.message ?: "Failed to load profile"
                loading = false
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = Accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("Profile", style = MaterialTheme.typography.titleLarge, color = White)
            Spacer(Modifier.weight(1f))
            val isOwn = profile?.optBoolean("isOwn", false) == true
            if (isOwn) {
                TextButton(onClick = onEditProfile) { Text("Edit", color = Accent) }
            } else {
                Spacer(Modifier.width(60.dp))
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error!!, color = Gray40) }
            profile != null -> {
                val p = profile!!
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(64.dp)) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Text(p.optString("username", "?").take(1).uppercase(), style = MaterialTheme.typography.headlineMedium, color = Accent)
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(p.optString("displayName", p.optString("username")), style = MaterialTheme.typography.titleMedium, color = White)
                            Text("@${p.optString("username")}", style = MaterialTheme.typography.bodySmall, color = Gray40)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Libs", style = MaterialTheme.typography.titleSmall, color = Accent)
                    Spacer(Modifier.height(8.dp))
                    val items = p.optJSONArray("items") ?: JSONArray()
                    if (items.length() == 0) {
                        Text("No items yet", color = Gray40, style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyColumn {
                            items((0 until items.length()).map { items.getJSONObject(it) }) { item ->
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(item.optString("title"), style = MaterialTheme.typography.bodyMedium, color = White)
                                        Text("v${item.optString("version")}", style = MaterialTheme.typography.labelSmall, color = Gray40)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
