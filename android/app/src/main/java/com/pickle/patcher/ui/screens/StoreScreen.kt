package com.pickle.patcher.ui.screens

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
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
    var showComments by remember { mutableStateOf(false) }
    var comments by remember { mutableStateOf<JSONArray?>(null) }
    var commentText by remember { mutableStateOf("") }

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
        val links = obj.optJSONArray("links")
        AlertDialog(
            onDismissRequest = { detailItem = null },
            title = { Text(obj.optString("title", "Untitled")) },
            text = {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
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

                    // Project links
                    if (links != null && links.length() > 0) {
                        Text("Links", style = MaterialTheme.typography.titleSmall, color = Accent)
                        Spacer(Modifier.height(4.dp))
                        (0 until links.length()).map { links.getJSONObject(it) }.forEach { lk ->
                            val url = lk.optString("url")
                            TextButton(onClick = {
                                try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) {}
                            }, contentPadding = PaddingValues(0.dp)) {
                                                                Text(lk.optString("label", url), color = Accent, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    // Libs with download count
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
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("${l.optInt("size", 0)} B", style = MaterialTheme.typography.labelSmall, color = Gray40)
                                        Spacer(Modifier.width(8.dp))
                                        Icon(androidx.compose.material.icons.Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(12.dp), tint = Gray40)
                                        Spacer(Modifier.width(2.dp))
                                        Text("${l.optInt("downloads", 0)}", style = MaterialTheme.typography.labelSmall, color = Gray40)
                                    }
                                }
                                TextButton(onClick = {
                                    if (com.pickle.patcher.data.StoreSession.emailVerified(context)) {
                                        downloadLib(context, l.optString("url"), l.optString("name"))
                                        scope.launch {
                                            api.incrementLibDownload(obj.optString("id"), l.optString("id")).onSuccess { count ->
                                                l.put("downloads", count)
                                                detailItem = detailItem?.let { JSONObject(it.toString()) }
                                            }
                                        }
                                    } else {
                                        android.widget.Toast.makeText(context, "Verify your email to download", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(14.dp), tint = Accent)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Download", color = Accent)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    // Like + comments row
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val liked = obj.optBoolean("liked", false)
                        val likes = obj.optInt("likesCount", 0)
                        IconButton(onClick = {
                            val token = com.pickle.patcher.data.StoreSession.token(context) ?: api.token
                            if (token == null) {
                                android.widget.Toast.makeText(context, "Log in to like", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                scope.launch {
                                    api.toggleLike(token, obj.optString("id")).onSuccess { (newLiked, newCount) ->
                                        obj.put("liked", newLiked)
                                        obj.put("likesCount", newCount)
                                        detailItem = detailItem?.let { JSONObject(it.toString()) }
                                    }
                                }
                            }
                        }) {
                            Text(if (liked) "♥" else "♡", color = if (liked) Accent else Gray40)
                        }
                        Text("$likes", style = MaterialTheme.typography.labelSmall, color = Gray40)
                        Spacer(Modifier.width(16.dp))
                        TextButton(onClick = {
                            showComments = true
                            scope.launch {
                                api.getComments(obj.optString("id")).onSuccess { res ->
                                    comments = res.optJSONArray("data")
                                }
                            }
                        }) {
                            Text("💬 ")
                            Text("Comments (${obj.optInt("commentsCount", 0)})", color = Accent)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { detailItem = null }) { Text("Close", color = Gray40) }
            },
        )
    }

    // Comments popup
    if (showComments && detailItem != null) {
        val obj = detailItem!!
        AlertDialog(
            onDismissRequest = { showComments = false },
            title = { Text("Comments") },
            text = {
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    val list = comments
                    if (list == null) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else if (list.length() == 0) {
                        Text("No comments yet", color = Gray40)
                    } else {
                        (0 until list.length()).map { list.getJSONObject(it) }.forEach { c ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                                val u = c.optJSONObject("user")
                                val uname = u?.optString("username") ?: "?"
                                val avatar = u?.optString("avatarUrl")
                                if (!avatar.isNullOrBlank()) {
                                    AsyncImage(model = avatar, contentDescription = null, modifier = Modifier.size(24.dp).clip(CircleShape))
                                } else {
                                    Surface(shape = CircleShape, color = Accent.copy(alpha = 0.2f), modifier = Modifier.size(24.dp)) {
                                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                            Text(uname.take(1).uppercase(), style = MaterialTheme.typography.labelSmall, color = Accent)
                                        }
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(uname, style = MaterialTheme.typography.labelSmall, color = Accent)
                                    Text(c.optString("content"), style = MaterialTheme.typography.bodySmall, color = White)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = commentText,
                        onValueChange = { commentText = it },
                        label = { Text("Write a comment") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val token = com.pickle.patcher.data.StoreSession.token(context) ?: api.token
                    if (token == null) {
                        android.widget.Toast.makeText(context, "Log in to comment", android.widget.Toast.LENGTH_SHORT).show()
                    } else if (!com.pickle.patcher.data.StoreSession.emailVerified(context)) {
                        android.widget.Toast.makeText(context, "Verify your email to comment", android.widget.Toast.LENGTH_SHORT).show()
                    } else if (commentText.isNotBlank()) {
                        scope.launch {
                            api.postComment(token, obj.optString("id"), commentText.trim()).onSuccess {
                                commentText = ""
                                api.getComments(obj.optString("id")).onSuccess { res ->
                                    comments = res.optJSONArray("data")
                                }
                                obj.put("commentsCount", obj.optInt("commentsCount", 0) + 1)
                                detailItem = detailItem?.let { JSONObject(it.toString()) }
                            }
                        }
                    }
                }) { Text("Post", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { showComments = false }) { Text("Close", color = Gray40) }
            },
        )
    }
}

@Composable
private fun StoreItemCard(obj: JSONObject, onUserClick: (String) -> Unit, onOpenDetail: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 4.dp),
        modifier = Modifier.fillMaxWidth().clickable { onOpenDetail() },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                modifier = Modifier.size(64.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    val logoUrl = obj.optString("logoUrl", "").takeIf { it.isNotBlank() }
                    if (logoUrl != null) {
                        AsyncImage(
                            model = logoUrl,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(obj.optString("title", "?").take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = Accent)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(obj.optString("title", "Untitled"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val desc = obj.optString("description", "")
                if (desc.isNotBlank()) {
                    MarkdownText(desc, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val user = obj.optJSONObject("user")
                    val username = user?.optString("username") ?: "?"
                    val avatarUrl = user?.optString("avatarUrl")
                    if (!avatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp).clip(CircleShape),
                        )
                    } else {
                        Surface(shape = MaterialTheme.shapes.extraSmall, color = Accent.copy(alpha = 0.2f), modifier = Modifier.size(18.dp)) {
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
                    AssistChip(onClick = {}, label = { Text("v${obj.optString("version", "1.0.0")}", style = MaterialTheme.typography.labelSmall) }, enabled = false)
                    val st = obj.optString("status", "approved")
                    if (st != "approved") {
                        Spacer(Modifier.width(6.dp))
                        AssistChip(onClick = {}, label = { Text(st, style = MaterialTheme.typography.labelSmall) }, colors = AssistChipDefaults.assistChipColors(labelColor = WarnYellow))
                    }
                }
            }
        }
    }
}

private fun downloadLib(context: Context, url: String, name: String) {
    if (url.isBlank()) {
        Toast.makeText(context, "No download URL", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName)
        dm.enqueue(req)
        Toast.makeText(context, "Downloading $name", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
