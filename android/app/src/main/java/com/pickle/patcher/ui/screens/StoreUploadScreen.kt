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
import com.pickle.patcher.ui.theme.White
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
    // libUris paired with their content hash for dedup
    var libEntries by remember { mutableStateOf<List<Pair<Uri, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
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
                val added = mutableListOf<Pair<Uri, String>>()
                var skipped = 0
                for (u in uris) {
                    val h = hashUri(context, u)
                    if (h == null || h in existing) { skipped++; continue }
                    existing.add(h)
                    added.add(u to h)
                }
                if (added.isNotEmpty()) libEntries = libEntries + added
                dupMsg = if (skipped > 0) "$skipped duplicate lib(s) skipped" else null
            }
        }
    }

    fun uriToFile(uri: Uri, prefix: String): File? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "$prefix.bin"
            val file = File(context.cacheDir, name)
            input.use { ins -> file.outputStream().use { outs -> ins.copyTo(outs) } }
            file
        } catch (_: Exception) { null }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Upload, contentDescription = null, tint = Accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("Upload Lib", style = MaterialTheme.typography.titleLarge, color = White)
        }
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = version, onValueChange = { version = it }, label = { Text("Version") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { logoPicker.launch(arrayOf("image/*")) }) { Text("Pick Logo") }
            Spacer(Modifier.width(8.dp))
            Text(logoUri?.lastPathSegment ?: "No logo", color = Gray40, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { libPicker.launch(arrayOf("*/*")) }) { Text("Add Libs") }
            Spacer(Modifier.width(8.dp))
            Text("${libEntries.size} lib(s) selected", color = Gray40, style = MaterialTheme.typography.bodySmall)
        }
        if (libEntries.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            libEntries.forEach { (uri, _) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("  • ${uri.lastPathSegment}", color = Gray40, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { libEntries = libEntries.filter { it.first != uri } }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Gray40, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        dupMsg?.let { Text(it, color = Accent, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(8.dp))
        cooldown?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                val token = StoreSession.token(context) ?: api.token
                if (token == null) { error = "Not logged in"; return@Button }
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
                    // Upload files directly to Cloudinary (bypasses server body limit).
                    var logoUrl: String? = null
                    if (logoUri != null) {
                        val f = uriToFile(logoUri!!, "logo")
                        if (f != null) {
                            val r = api.uploadFile(f)
                            if (r.isFailure) { error = r.exceptionOrNull()?.message ?: "Logo upload failed"; loading = false; return@launch }
                            logoUrl = r.getOrNull()?.url
                        }
                    }
                    val libUploads = mutableListOf<StoreApi.LibUpload>()
                    for ((uri, _) in libEntries) {
                        val f = uriToFile(uri, "lib")
                        if (f != null) {
                            val r = api.uploadFile(f)
                            if (r.isFailure) { error = r.exceptionOrNull()?.message ?: "Lib upload failed"; loading = false; return@launch }
                            val u = r.getOrNull()!!
                            libUploads.add(StoreApi.LibUpload(f.name, u.url, u.bytes))
                        }
                    }
                    api.createItem(token, title, description, version, logoUrl, libUploads).fold(
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
