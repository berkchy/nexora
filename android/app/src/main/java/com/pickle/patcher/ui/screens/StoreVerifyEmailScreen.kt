package com.pickle.patcher.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pickle.patcher.data.StoreApi
import com.pickle.patcher.data.StoreSession
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.White
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun StoreVerifyEmailScreen(
    api: StoreApi,
    onBack: () -> Unit,
    onVerified: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    var resendCooldown by remember { mutableStateOf(0) }
    var resending by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        api.me().getOrNull()?.optJSONObject("user")?.optString("email")?.let { email = it }
    }

    LaunchedEffect(resendCooldown) {
        if (resendCooldown > 0) {
            delay(1000)
            resendCooldown--
        }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Verify Email", style = MaterialTheme.typography.headlineMedium, color = White)
        Spacer(Modifier.height(8.dp))
        Text(
            "Enter the 6-digit code sent to ${if (email.isNotBlank()) email else "your email"}",
            color = Gray40,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = code,
            onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) code = it },
            label = { Text("Verification Code") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center, letterSpacing = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp)),
        )
        Spacer(Modifier.height(8.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        success?.let { if (it) Text("Email verified!", color = Accent, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                loading = true; error = null
                scope.launch {
                    api.verifyEmail(email, code).fold(
                        onSuccess = {
                            StoreSession.setEmailVerified(context, true)
                            loading = false
                            success = true
                            delay(800)
                            onVerified()
                        },
                        onFailure = { t ->
                            error = t.message ?: "Verification failed"
                            loading = false
                        },
                    )
                }
            },
            enabled = !loading && code.length == 6 && email.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Verify")
        }
        Spacer(Modifier.height(16.dp))

        if (resendCooldown > 0) {
            Text("Didn't receive code? Resend in ${String.format(Locale.US, "%02d:%02d", resendCooldown / 60, resendCooldown % 60)}", color = Gray40, style = MaterialTheme.typography.bodySmall)
        } else {
            TextButton(
                onClick = {
                    resending = true
                    scope.launch {
                        api.resendVerification().fold(
                            onSuccess = { resendCooldown = 30 },
                            onFailure = { t -> error = t.message ?: "Resend failed" },
                        )
                        resending = false
                    }
                },
                enabled = !resending,
            ) {
                if (resending) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Didn't receive code? Resend", color = Accent)
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack) { Text("Back", color = Gray40) }
    }
}
