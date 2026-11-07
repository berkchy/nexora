package com.pickle.patcher.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pickle.patcher.data.StoreApi
import com.pickle.patcher.data.StoreSession
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.Gray30
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Close
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

@Composable
fun StoreUploadScreen(
    api: StoreApi,
    onBack: () -> Unit,
    onUploadSuccess: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("1.0.0") }
    var logoUri by remember { mutableStateOf<Uri?>(null) }
    // Selected file URIs paired with their content hash for dedup
    var libEntries by remember { mutableStateOf<List<Triple<Uri, String, String>>>(emptyList()) }
    var links by remember { mutableStateOf<List<Pair<String?, String>>>(emptyList()) }
    var linkInput by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var progressLabel by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var cooldown by remember { mutableStateOf<String?>(null) }
    var dupMsg by remember { mutableStateOf<String?>(null) }

    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) logoUri = uri
    }
    val libPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val existing = libEntries.map { it.second }.toMutableSet()
                val added = mutableListOf<Triple<Uri, String, String>>()
                var skipped = 0
                for (u in uris) {
                    val h = hashUri(context, u)
                    if (h == null || h in existing) { skipped++; continue }
                    existing.add(h)
                    val name = cleanStoreFileName(displayName(context, u))
                    added.add(Triple(u, h, name))
                }
                if (added.isNotEmpty()) libEntries = libEntries + added
                dupMsg = if (skipped > 0) "$skipped duplicate file(s) skipped" else null
            }
        }
    }

    fun uriToFile(uri: Uri, prefix: String, nameOverride: String? = null): File? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val name = nameOverride ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "$prefix.bin")
            val file = File(context.cacheDir, name)
            input.use { ins -> file.outputStream().use { outs -> ins.copyTo(outs) } }
            file
        } catch (_: Exception) { null }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp).verticalScroll(rememberScrollState()),
    ) {
        StudioHero(
            icon = Icons.Filled.Upload,
            title = "Upload Studio",
            subtitle = "Publish a new package post.",
            status = "UPLOAD",
            statusTone = StudioTone.Secondary,
            stats = listOf(StudioStat("FILES", "${libEntries.size}", StudioTone.Primary)),
        )
        Spacer(Modifier.height(20.dp))
        StudioStepHeader("01", "DETAILS", "Describe the post")

        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = version, onValueChange = { version = it }, label = { Text("Version") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(20.dp))
        StudioStepHeader("02", "ASSETS", "Logo and package files")

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { logoPicker.launch(arrayOf("image/*")) }) { Text("Pick Logo") }
            Spacer(Modifier.width(8.dp))
            Text(
                logoUri?.lastPathSegment ?: "No logo",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { libPicker.launch(arrayOf("*/*")) }) { Text("Add Files") }
            Spacer(Modifier.width(8.dp))
            Text(
                "${libEntries.size} file(s) selected",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(20.dp))
        StudioStepHeader("03", "LINKS", "Point to the project source")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = linkInput, onValueChange = { linkInput = it }, label = { Text("Project link URL") }, modifier = Modifier.weight(1f), singleLine = true)
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val u = linkInput.trim()
                if (u.isNotBlank()) { links = links + Pair(null, u); linkInput = "" }
            }) { Text("Add") }
        }
        if (links.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            links.forEachIndexed { idx, (label, url) ->
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
        }
        Spacer(Modifier.height(8.dp))

        if (libEntries.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            libEntries.forEach { (uri, _) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "  • ${libEntries.find { it.first == uri }?.third ?: uri.lastPathSegment}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { libEntries = libEntries.filter { it.first != uri } }, modifier = Modifier.size(20.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
        dupMsg?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        cooldown?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (loading) {
            Spacer(Modifier.height(8.dp))
            if (progressLabel.isNotBlank()) {
                Text(
                    progressLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(
                "${(progress * 100).toInt()}%",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(20.dp))
        StudioStepHeader("04", "PUBLISH", "Submit for review")

        Button(
            onClick = {
                val token = StoreSession.token(context) ?: api.token
                if (token == null) { error = "Not logged in"; return@Button }
                if (!StoreSession.emailVerified(context)) { error = "Verify your email to upload"; return@Button }
                loading = true; error = null; cooldown = null
                scope.launch {
                    // Preflight cooldown check to avoid uploading large files first.
                    val me = api.me()
                    val upload = me.getOrNull()?.optJSONObject("upload")
                    val allowed = upload?.optBoolean("allowed", true) ?: true
                    if (!allowed) {
                        val next = upload?.optString("nextAllowedAt")
                        cooldown = "Upload cooldown active" + (if (!next.isNullOrBlank()) " (next: $next)" else "")
                        loading = false
                        return@launch
                    }
                    // Upload files directly to Cloudinary/Supabase (bypasses server body limit).
                    progress = 0f
                    var logoUrl: String? = null
                    if (logoUri != null) {
                        val f = uriToFile(logoUri!!, "logo")
                        if (f != null) {
                            progressLabel = "Uploading logo..."
                            val r = api.uploadFile(f) { w, t -> progress = if (t > 0) w.toFloat() / t else 0f }
                            if (r.isFailure) { error = r.exceptionOrNull()?.message ?: "Logo upload failed"; loading = false; return@launch }
                            logoUrl = r.getOrNull()?.url
                        }
                    }
                    val libUploads = mutableListOf<StoreApi.LibUpload>()
                    val totalLibs = libEntries.size
                    libEntries.forEachIndexed { idx, (uri, _, name) ->
                        val f = uriToFile(uri, "file", name)
                        if (f != null) {
                            progressLabel = "Uploading file ${idx + 1}/$totalLibs: ${f.name}"
                            progress = 0f
                            val r = api.uploadFile(f) { w, t -> progress = if (t > 0) w.toFloat() / t else 0f }
                            if (r.isFailure) { error = r.exceptionOrNull()?.message ?: "File upload failed"; loading = false; return@launch }
                            val u = r.getOrNull()!!
                            libUploads.add(StoreApi.LibUpload(cleanStoreFileName(f.name), u.url, u.bytes))
                        }
                    }
                    api.createItem(token, title, description, version, logoUrl, libUploads, links).fold(
                        onSuccess = { loading = false; android.widget.Toast.makeText(context, "Submitted for approval", android.widget.Toast.LENGTH_SHORT).show(); onUploadSuccess() },
                        onFailure = { t ->
                            android.util.Log.e("StoreUpload", "upload failed", t)
                            val msg = (t.message ?: t.toString()).let { if (it.isBlank()) "Upload failed" else it }
                            if (msg.contains("cooldown", ignoreCase = true)) cooldown = msg
                            else error = msg
                            loading = false
                        },
                    )
                }
            },
            enabled = !loading && title.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Upload")
        }
    }
}

private fun displayName(context: Context, uri: Uri): String {
    val fromColumn = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    } catch (_: Exception) { null }
    val fromPath = uri.lastPathSegment?.substringAfterLast('/')
    // Prefer a name that carries an extension; fall back to whatever is available.
    val candidates = listOfNotNull(fromColumn, fromPath)
    val withExt = candidates.firstOrNull { it.contains('.') }
    return (withExt ?: candidates.firstOrNull() ?: "file.bin")
}

private suspend fun hashUri(context: Context, uri: Uri): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
        val md = MessageDigest.getInstance("SHA-256")
        input.use { ins ->
            val buf = ByteArray(8192)
            var n: Int
            while (ins.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }
}
