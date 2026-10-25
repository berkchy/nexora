package com.pickle.patcher.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pickle.patcher.data.StoreApi
import com.pickle.patcher.data.StoreSession
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.White
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
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

    fun load() {
        scope.launch {
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
    }

    LaunchedEffect(username) { load() }

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
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(64.dp)) {
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
EOF
echo done