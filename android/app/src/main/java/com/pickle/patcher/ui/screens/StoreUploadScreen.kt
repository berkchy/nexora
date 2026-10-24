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
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.White
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

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
    var libUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cooldown by remember { mutableStateOf<String?>(null) }

    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) logoUri = uri
    }
    val libPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) libUris = libUris + uris
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
            TextButton(onClick = onBack) { Text("← Back", color = Accent) }
            Spacer(Modifier.weight(1f))
            Text("Upload Lib", style = MaterialTheme.typography.titleMedium, color = White)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(60.dp))
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
            Text("${libUris.size} lib(s) selected", color = Gray40, style = MaterialTheme.typography.bodySmall)
        }
        if (libUris.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            libUris.forEach { uri ->
                Text("  • ${uri.lastPathSegment}", color = Gray40, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        cooldown?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                loading = true; error = null; cooldown = null
                scope.launch {
                    val logoFile = logoUri?.let { uriToFile(it, "logo") }
                    val libFiles = libUris.mapNotNull { uriToFile(it, "lib") }
                    api.createItem(api.token ?: "", title, description, version, logoFile, libFiles).fold(
                        onSuccess = { loading = false; onUploadSuccess() },
                        onFailure = { t ->
                            val msg = t.message ?: "Upload failed"
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
