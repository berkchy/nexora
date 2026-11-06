package com.pickle.patcher.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.pickle.patcher.ui.theme.Gray30
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
        StudioHero(
            icon = Icons.Filled.Edit,
            title = "Package Studio",
            subtitle = "Manage your uploaded package posts.",
            status = "PACKAGES",
            statusTone = StudioTone.Secondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
            else -> {
                val list = items
                if (list == null || list.length() == 0) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No items yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items((0 until list.length()).map { list.getJSONObject(it) }) { obj ->
                            val id = obj.optString("id")
                            val status = obj.optString("status", "approved")
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            obj.optString("title"),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        StudioStatusPill(
                                            status.uppercase(),
                                            tone = if (status == "approved") StudioTone.Primary else StudioTone.Tertiary,
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "v${obj.optString("version")} · ${obj.optJSONArray("libs")?.length() ?: 0} file(s)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = { editTarget = obj }) {
                                            Text("Edit", color = MaterialTheme.colorScheme.primary)
                                        }
                                        TextButton(onClick = { deleteTarget = id }) {
                                            Text("Delete", color = MaterialTheme.colorScheme.error)
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

    // Delete confirmation
    deleteTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete item?", color = MaterialTheme.colorScheme.error) },
            text = {
                Text(
                    "This cannot be undone.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        api.deleteItem(api.token ?: "", id).fold(
                            onSuccess = { deleteTarget = null; reload() },
                            onFailure = { t -> error = t.message; deleteTarget = null },
                        )
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
            var links by remember {
                mutableStateOf(
                    (0 until (obj.optJSONArray("links")?.length() ?: 0)).map { i ->
                        val o = obj.optJSONArray("links")!!.getJSONObject(i)
                        Pair(o.optString("label").takeIf { it.isNotBlank() }, o.optString("url"))
                    }
                )
            }
            var linkInput by remember { mutableStateOf("") }
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
                title = { Text("Edit item", color = MaterialTheme.colorScheme.onSurface) },
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (logoUri != null || existingLogo != null) {
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { logoUri = null; /* keep existing? */ }) {
                                    Text("Clear", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { libPicker.launch(arrayOf("*/*")) }) { Text("Add Files") }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${initialLibs.count { it.optString("url") !in removedLibs } + newLibs.size} file(s)",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        initialLibs.forEach { l ->
                            val url = l.optString("url")
                            if (url in removedLibs) return@forEach
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "  • ${l.optString("name")}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { removedLibs = removedLibs + url }, modifier = Modifier.size(20.dp)) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Remove",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                        newLibs.forEach { (uri, _, name) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "  • $name (new)",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { newLibs = newLibs.filter { it.first != uri } }, modifier = Modifier.size(20.dp)) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Remove",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                        // Project links
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = linkInput, onValueChange = { linkInput = it }, label = { Text("Project link URL") }, modifier = Modifier.weight(1f), singleLine = true)
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                val u = linkInput.trim()
                                if (u.isNotBlank()) { links = links + Pair(null, u); linkInput = "" }
                            }) { Text("Add") }
                        }
                        links.forEachIndexed { idx, (_, url) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "  • $url",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { links = links.filterIndexed { i, _ -> i != idx } }, modifier = Modifier.size(20.dp)) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Remove",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }

                        if (progressLabel.isNotBlank()) {
                            Text(
                                progressLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
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
                                // Upload only new files.
                                val uploadedNew = mutableListOf<com.pickle.patcher.data.StoreApi.LibUpload>()
                                val total = newLibs.size
                                newLibs.forEachIndexed { idx, (uri, _, name) ->
                                    val f = uriToFile(context, uri, "file", name)
                                    if (f != null) {
                                        progressLabel = "Uploading file ${idx + 1}/$total: ${f.name}"
                                        val r = api.uploadFile(f) { _, _ -> }
                                        if (r.isFailure) { error = r.exceptionOrNull()?.message ?: "File upload failed"; saving = false; return@launch }
                                        val u = r.getOrNull()!!
                                        uploadedNew.add(com.pickle.patcher.data.StoreApi.LibUpload(f.name, u.url, u.bytes))
                                    }
                                }
                                // Final file list: unchanged existing + uploaded new.
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
                                    links,
                                ).fold(
                                    onSuccess = { saving = false; editTarget = null; reload() },
                                    onFailure = { t -> error = t.message; saving = false },
                                )
                                progressLabel = ""
                            }
                        },
                        enabled = !saving,
                    ) { Text("Save", color = MaterialTheme.colorScheme.primary) }
                },
                dismissButton = {
                    TextButton(onClick = { editTarget = null }) {
                        Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
