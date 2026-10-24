package com.pickle.patcher.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
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
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun StoreScreen(
    api: StoreApi,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<JSONArray?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(1) }

    LaunchedEffect(page) {
        loading = true
        error = null
        api.listItems(page).fold(
            onSuccess = { json ->
                items = json.optJSONArray("data")
                loading = false
            },
            onFailure = { t ->
                error = t.message ?: "Failed to load store"
                loading = false
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        // Title bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.ShoppingCart, contentDescription = null, tint = Accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("Store", style = MaterialTheme.typography.titleLarge, color = White)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error!!, color = Gray40) }
            else -> {
                val list = items
                if (list == null || list.length() == 0) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No items yet", color = Gray40) }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items((0 until list.length()).map { list.getJSONObject(it) }) { obj ->
                            StoreItemCard(obj)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreItemCard(obj: JSONObject) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Logo placeholder
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(obj.optString("title", "?").take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = Accent)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(obj.optString("title", "Untitled"), style = MaterialTheme.typography.titleSmall, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val desc = obj.optString("description", "")
                if (desc.isNotBlank()) {
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = Gray40, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    Text("by ${obj.optJSONObject("user")?.optString("username") ?: "?"}", style = MaterialTheme.typography.labelSmall, color = Accent)
                    Spacer(Modifier.weight(1f))
                    Text("v${obj.optString("version", "1.0.0")}", style = MaterialTheme.typography.labelSmall, color = Gray40)
                }
            }
        }
    }
}
