package com.pickle.patcher.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Login
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pickle.patcher.data.StoreApi
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.White
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun StoreLoginScreen(
    api: StoreApi,
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit,
    onRegister: () -> Unit,
    onNeedsVerification: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Login, contentDescription = null, tint = Accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("Login", style = MaterialTheme.typography.headlineMedium, color = White)
        }
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                loading = true; error = null
                scope.launch {
                    api.login(email, password).fold(
                        onSuccess = { json ->
                            val t = json.optString("token")
                            api.token = t
                            val uname = json.optString("username")
                            val role = json.optString("role", "user")
                            val ev = json.optBoolean("emailVerified", false)
                            com.pickle.patcher.data.StoreSession.save(context, t, uname, role, ev)
                            loading = false
                            if (ev) onLoginSuccess() else onNeedsVerification()
                        },
                        onFailure = { t ->
                            error = t.message ?: "Login failed"
                            loading = false
                        },
                    )
                }
            },
            enabled = !loading && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Login")
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRegister) { Text("No account? Register", color = Accent) }
    }
}
