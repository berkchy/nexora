package com.pickle.patcher.ui.screens

import android.net.Uri
import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
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
import com.pickle.patcher.data.StoreSession
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.White
import com.pickle.patcher.ui.theme.WarnYellow
import com.pickle.patcher.ui.MarkdownText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Download
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Composable
fun StoreProfileScreen(
    api: StoreApi,
    username: String,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showEdit by remember { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<JSONObject?>(null) }

    fun load(showLoading: Boolean = profile == null) {
        scope.launch {
            if (showLoading) loading = true
            api.getUserProfile(username).fold(
                onSuccess = { json ->
                    profile = json.optJSONObject("profile")
                    loading = false
                },
                onFailure = { t ->
                    if (profile == null) error = t.message ?: "Failed to load profile"
                    loading = false
                },
            )
        }
    }

    LaunchedEffect(username) {
        load(showLoading = true)
        while (true) {
            kotlinx.coroutines.delay(4000)
            load(showLoading = false)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = Accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("Profile", style = MaterialTheme.typography.titleLarge, color = White)
            Spacer(Modifier.weight(1f))
            val isOwn = profile?.optBoolean("isOwn", false) == true
            if (isOwn) {
                TextButton(onClick = { showEdit = true }) { Text("Edit", color = Accent) }
            } else {
                Spacer(Modifier.width(60.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error!!, color = Gray40) }
            profile != null -> {
                val p = profile!!
                val avatarUrl = p.optString("avatarUrl", "").takeIf { it.isNotBlank() }
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(64.dp).clip(CircleShape)) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                if (avatarUrl != null) {
                                    AsyncImage(model = avatarUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                                } else {
                                    Text(p.optString("username", "?").take(1).uppercase(), style = MaterialTheme.typography.headlineMedium, color = Accent)
                                }
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
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { detailItem = item },
                                ) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        val logoUrl = item.optString("logoUrl", "").takeIf { it.isNotBlank() }
                                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(32.dp)) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                                if (logoUrl != null) AsyncImage(model = logoUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                                                else Text(item.optString("title", "?").take(1).uppercase(), style = MaterialTheme.typography.labelMedium, color = Accent)
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(item.optString("title"), style = MaterialTheme.typography.bodyMedium, color = White)
                                            Row {
                                                Text("v${item.optString("version")}", style = MaterialTheme.typography.labelSmall, color = Gray40)
                                                if (item.optString("status", "approved") != "approved") {
                                                    Spacer(Modifier.width(6.dp))
                                                    Text("• pending approval", style = MaterialTheme.typography.labelSmall, color = WarnYellow)
                                                }
                                            }
                                        }
                                        Icon(Icons.Filled.Download, contentDescription = null, tint = Gray40, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    detailItem?.let { item ->
        val libs = item.optJSONArray("libs")
        val libCount = libs?.length() ?: 0
        AlertDialog(
            onDismissRequest = { detailItem = null },
            title = { Text(item.optString("title", "Untitled")) },
            text = {
                Column {
                    val logoUrl = item.optString("logoUrl", "").takeIf { it.isNotBlank() }
                    if (logoUrl != null) {
                        AsyncImage(model = logoUrl, contentDescription = null, modifier = Modifier.fillMaxWidth().height(120.dp))
                        Spacer(Modifier.height(8.dp))
                    }
                    val desc = item.optString("description", "")
                    if (desc.isNotBlank()) {
                        Text(desc, style = MaterialTheme.typography.bodySmall, color = Gray40)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("v${item.optString("version", "1.0.0")}", style = MaterialTheme.typography.labelSmall, color = Gray40)
                    if (libCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text("Libs", style = MaterialTheme.typography.titleSmall, color = Accent)
                        Spacer(Modifier.height(4.dp))
                        (0 until libCount).map { libs!!.getJSONObject(it) }.forEach { l ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(l.optString("name"), style = MaterialTheme.typography.bodySmall, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${l.optInt("size", 0)} B", style = MaterialTheme.typography.labelSmall, color = Gray40)
                                }
                                TextButton(onClick = {
                                    val ctx = context
                                    try {
                                        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                        val req = DownloadManager.Request(Uri.parse(l.optString("url")))
                                            .setTitle(l.optString("name"))
                                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, l.optString("name"))
                                        dm.enqueue(req)
                                        Toast.makeText(ctx, "Downloading ${l.optString("name")}", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(ctx, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(14.dp), tint = Accent)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Download", color = Accent)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detailItem = null }) { Text("Close", color = Gray40) } },
        )
    }

    if (showEdit) {
        EditProfileDialog(
            api = api,
            username = username,
            onDismiss = { showEdit = false },
            onSaved = { showEdit = false; load() },
        )
    }
}

@Composable
private fun EditProfileDialog(api: StoreApi, username: String, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var displayName by remember { mutableStateOf("") }
    var avatarUri by remember { mutableStateOf<Uri?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) avatarUri = uri
    }

    fun uriToFile(uri: Uri): File? = try {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "avatar.png"
        val file = File(context.cacheDir, name)
        input.use { ins -> file.outputStream().use { outs -> ins.copyTo(outs) } }
        file
    } catch (_: Exception) { null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Profile") },
        text = {
            Column {
                OutlinedTextField(value = displayName, onValueChange = { displayName = it }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { avatarPicker.launch(arrayOf("image/*")) }) { Text("Pick Avatar") }
                    Spacer(Modifier.width(8.dp))
                    Text(avatarUri?.lastPathSegment ?: "No avatar", color = Gray40, style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    loading = true; error = null
                    scope.launch {
                        val token = StoreSession.token(context) ?: api.token
                        if (token == null) { error = "Not logged in"; loading = false; return@launch }
                        if (displayName.isNotBlank()) {
                            api.updateProfile(token, displayName, null).fold(
                                onSuccess = {},
                                onFailure = { t -> error = t.message ?: "Update failed" },
                            )
                        }
                        if (avatarUri != null && error == null) {
                            val f = uriToFile(avatarUri!!)
                            if (f != null) {
                                api.uploadAvatar(token, f).fold(
                                    onSuccess = {},
                                    onFailure = { t -> error = t.message ?: "Avatar upload failed" },
                                )
                            }
                        }
                        loading = false
                        if (error == null) onSaved()
                    }
                },
                enabled = !loading,
            ) { Text(if (loading) "Saving…" else "Save", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Gray40) } },
    )
}
