package com.pickle.patcher.ui.screens

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pickle.patcher.data.StoreApi
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.White
import com.pickle.patcher.ui.theme.WarnYellow
import com.pickle.patcher.ui.MarkdownText
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen(
    api: StoreApi,
    onBack: () -> Unit,
    onUserClick: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<JSONArray?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(1) }
    var detailItem by remember { mutableStateOf<JSONObject?>(null) }

    fun load(isRefresh: Boolean = false) {
        scope.launch {
            if (isRefresh) refreshing = true else loading = true
            error = null
            api.listItems(page).fold(
                onSuccess = { json ->
                    items = json.optJSONArray("data")
                    loading = false
                    refreshing = false
                },
                onFailure = { t ->
                    error = t.message ?: "Failed to load store"
                    loading = false
                    refreshing = false
                },
            )
        }
    }

    LaunchedEffect(page) { load() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.ShoppingCart, contentDescription = null, tint = Accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("Store", style = MaterialTheme.typography.titleLarge, color = White)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { load(true) },
            modifier = Modifier.fillMaxSize(),
        ) {
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
                                StoreItemCard(
                                    obj = obj,
                                    onUserClick = onUserClick,
                                    onOpenDetail = { detailItem = obj },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Detail dialog with download buttons
    detailItem?.let { obj ->
        val libs = obj.optJSONArray("libs")
        val libCount = libs?.length() ?: 0
        AlertDialog(
            onDismissRequest = { detailItem = null },
            title = { Text(obj.optString("title", "Untitled")) },
            text = {
                Column {
                    val logoUrl = obj.optString("logoUrl", "").takeIf { it.isNotBlank() }
                    if (logoUrl != null) {
                        AsyncImage(model = logoUrl, contentDescription = null, modifier = Modifier.fillMaxWidth().height(120.dp))
                        Spacer(Modifier.height(8.dp))
                    }
                    val desc = obj.optString("description", "")
                    if (desc.isNotBlank()) {
                        MarkdownText(desc, color = Gray40, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("v${obj.optString("version", "1.0.0")}", style = MaterialTheme.typography.labelSmall, color = Gray40)
                    Spacer(Modifier.height(8.dp))
                    if (libCount > 0) {
                        Text("Libs", style = MaterialTheme.typography.titleSmall, color = Accent)
                        Spacer(Modifier.height(4.dp))
                        (0 until libCount).map { libs!!.getJSONObject(it) }.forEach { l ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(l.optString("name"), style = MaterialTheme.typography.bodySmall, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${l.optInt("size", 0)} B", style = MaterialTheme.typography.labelSmall, color = Gray40)
                                }
                                TextButton(onClick = { downloadLib(context, l.optString("url"), l.optString("name")) }) {
                                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(14.dp), tint = Accent)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Download", color = Accent)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { detailItem = null }) { Text("Close", color = Gray40) }
            },
        )
    }
}

@Composable
private fun StoreItemCard(obj: JSONObject, onUserClick: (String) -> Unit, onOpenDetail: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable { onOpenDetail() },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    val logoUrl = obj.optString("logoUrl", "").takeIf { it.isNotBlank() }
                    if (logoUrl != null) {
                        AsyncImage(model = logoUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                    } else {
                        Text(obj.optString("title", "?").take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = Accent)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(obj.optString("title", "Untitled"), style = MaterialTheme.typography.titleSmall, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val desc = obj.optString("description", "")
                if (desc.isNotBlank()) {
                    MarkdownText(desc, color = Gray40, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val user = obj.optJSONObject("user")
                    val username = user?.optString("username") ?: "?"
                    val avatarUrl = user?.optString("avatarUrl")
                    if (!avatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp).clip(CircleShape),
                        )
                    } else {
                        Surface(shape = MaterialTheme.shapes.extraSmall, color = Accent.copy(alpha = 0.2f), modifier = Modifier.size(16.dp)) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Text(username.take(1).uppercase(), style = MaterialTheme.typography.labelSmall, color = Accent)
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    TextButton(onClick = { onUserClick(username) }, contentPadding = PaddingValues(0.dp)) {
                        Text("by $username", style = MaterialTheme.typography.labelSmall, color = Accent)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("v${obj.optString("version", "1.0.0")}", style = MaterialTheme.typography.labelSmall, color = Gray40)
                    val st = obj.optString("status", "approved")
                    if (st != "approved") {
                        Spacer(Modifier.width(6.dp))
                        Text("• $st", style = MaterialTheme.typography.labelSmall, color = WarnYellow)
                    }
                }

            }
        }
    }
}

private fun downloadLib(context: Context, url: String, name: String) {
    try {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        dm.enqueue(req)
        Toast.makeText(context, "Downloading $name", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
