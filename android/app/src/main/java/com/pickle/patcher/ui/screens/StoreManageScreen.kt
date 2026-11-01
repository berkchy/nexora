package com.pickle.patcher.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
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
import com.pickle.patcher.ui.theme.WarnYellow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
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

    fun reload(showLoading: Boolean = items == null) {
        if (showLoading) loading = true
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
                    if (items == null) error = t.message ?: "Failed to load"
                    loading = false
                },
            )
        }
    }

    LaunchedEffect(Unit) {
        reload(showLoading = true)
        while (true) {
            kotlinx.coroutines.delay(4000)
            reload(showLoading = false)
        }
    }

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
                                    Row {
                                        Text("v${obj.optString("version")} · ${obj.optJSONArray("libs")?.length() ?: 0} lib(s)", style = MaterialTheme.typography.labelSmall, color = Gray40)
                                        if (obj.optString("status", "approved") != "approved") {
                                            Spacer(Modifier.width(6.dp))
                                            Text("• ${obj.optString("status")}", style = MaterialTheme.typography.labelSmall, color = WarnYellow)
                                        }
                                    }
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
        key(obj.optString("id")) {
            var title by remember { mutableStateOf(obj.optString("title")) }
            var description by remember { mutableStateOf(obj.optString("description")) }
            var version by remember { mutableStateOf(obj.optString("version")) }
            var logoUri by remember { mutableStateOf<android.net.Uri?>(null) }
            val existingLogo = obj.optString("logoUrl", "").takeIf { it.isNotBlank() }
            var removedLibs by remember { mutableStateOf(setOf<String>()) }
            var newLibs by remember { mutableStateOf<List<Triple<android.net.Uri, String, String>>>(emptyList()) }
            var saving by remember { mutableStateOf(false) }
            var progressLabel by remember { mutableStateOf("") }

            val logoPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) logoUri = uri
            }
            val libPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                if (uris.isNotEmpty()) {
                    scope.launch {
                        val existing = newLibs.map { it.second }.toMutableSet()
                        val added = mutableListOf<Triple<android.net.Uri, String, String>>()
                        for (u in uris) {
                            val h = hashUri(context, u) ?: continue
                            if (h in existing) continue
                            existing.add(h)
                            added.add(Triple(u, h, displayName(context, u)))
                        }
                        if (added.isNotEmpty()) newLibs = newLibs + added
                    }
                }
            }

            val initialLibs = remember {
                val arr = obj.optJSONArray("libs")
                (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }
            }

            AlertDialog(
                onDismissRequest = { editTarget = null },
                title = { Text("Edit item", color = White) },
                text = {
                    Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = version, onValueChange = { version = it }, label = { Text("Version") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(Modifier.height(12.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { logoPicker.launch(arrayOf("image/*")) }) { Text("Pick Logo") }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when {
                                    logoUri != null -> logoUri!!.lastPathSegment ?: "new logo"
                                    existingLogo != null -> "current logo"
                                    else -> "No logo"
                                },
                                color = Gray40, style = MaterialTheme.typography.bodySmall,
                            )
                            if (logoUri != null || existingLogo != null) {
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { logoUri = null; /* keep existing? */ }) { Text("Clear", color = Gray40) }
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { libPicker.launch(arrayOf("*/*")) }) { Text("Add Libs") }
                            Spacer(Modifier.width(8.dp))
                            Text("${initialLibs.count { it.optString("url") !in removedLibs } + newLibs.size} lib(s)", color = Gray40, style = MaterialTheme.typography.bodySmall)
                        }
                        initialLibs.forEach { l ->
                            val url = l.optString("url")
                            if (url in removedLibs) return@forEach
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("  • ${l.optString("name")}", color = Gray40, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = { removedLibs = removedLibs + url }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Gray40, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                        newLibs.forEach { (uri, _, name) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("  • $name (new)", color = Accent, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = { newLibs = newLibs.filter { it.first != uri } }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Gray40, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                        if (progressLabel.isNotBlank()) Text(progressLabel, color = Gray40, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            saving = true
                            scope.launch {
                                val token = api.token ?: ""
                                // Upload logo only if newly picked.
                                var logoUrl: String? = existingLogo
                                var hasLogo = existingLogo != null
                                if (logoUri != null) {
                                    val f = uriToFile(context, logoUri!!, "logo")
                                    if (f != null) {
                                        progressLabel = "Uploading logo..."
                                        val r = api.uploadFile(f) { _, _ -> }
                                        if (r.isFailure) { error = r.exceptionOrNull()?.message ?: "Logo upload failed"; saving = false; return@launch }
                                        logoUrl = r.getOrNull()?.url
                                        hasLogo = true
                                    }
                                }
                                // Upload only new libs.
                                val uploadedNew = mutableListOf<com.pickle.patcher.data.StoreApi.LibUpload>()
                                val total = newLibs.size
                                newLibs.forEachIndexed { idx, (uri, _, name) ->
                                    val f = uriToFile(context, uri, "lib", name)
                                    if (f != null) {
                                        progressLabel = "Uploading lib ${idx + 1}/$total: ${f.name}"
                                        val r = api.uploadFile(f) { _, _ -> }
                                        if (r.isFailure) { error = r.exceptionOrNull()?.message ?: "Lib upload failed"; saving = false; return@launch }
                                        val u = r.getOrNull()!!
                                        uploadedNew.add(com.pickle.patcher.data.StoreApi.LibUpload(f.name, u.url, u.bytes))
                                    }
                                }
                                // Final libs list: unchanged existing + uploaded new.
                                val finalLibs = mutableListOf<com.pickle.patcher.data.StoreApi.LibUpload>()
                                initialLibs.forEach { l ->
                                    val url = l.optString("url")
                                    if (url !in removedLibs) {
                                        finalLibs.add(com.pickle.patcher.data.StoreApi.LibUpload(l.optString("name"), url, l.optLong("size", 0)))
                                    }
                                }
                                finalLibs.addAll(uploadedNew)

                                api.updateItem(
                                    token,
                                    obj.optString("id"),
                                    title,
                                    description,
                                    version,
                                    logoUrl,
                                    hasLogo,
                                    finalLibs,
                                ).fold(
                                    onSuccess = { saving = false; editTarget = null; reload() },
                                    onFailure = { t -> error = t.message; saving = false },
                                )
                                progressLabel = ""
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
}

private fun uriToFile(context: android.content.Context, uri: android.net.Uri, prefix: String, nameOverride: String? = null): java.io.File? {
    return try {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val name = nameOverride ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "$prefix.bin")
        val file = java.io.File(context.cacheDir, name)
        input.use { ins -> file.outputStream().use { outs -> ins.copyTo(outs) } }
        file
    } catch (_: Exception) { null }
}

private fun displayName(context: android.content.Context, uri: android.net.Uri): String {
    val fromColumn = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    } catch (_: Exception) { null }
    val fromPath = uri.lastPathSegment?.substringAfterLast('/')
    val candidates = listOfNotNull(fromColumn, fromPath)
    val withExt = candidates.firstOrNull { it.contains('.') }
    return (withExt ?: candidates.firstOrNull() ?: "file.bin")
}

private suspend fun hashUri(context: android.content.Context, uri: android.net.Uri): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
        val md = java.security.MessageDigest.getInstance("SHA-256")
        input.use { ins ->
            val buf = ByteArray(8192)
            var n: Int
            while (ins.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }
}
