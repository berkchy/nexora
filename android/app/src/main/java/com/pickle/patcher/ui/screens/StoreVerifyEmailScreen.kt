package com.pickle.patcher.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
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
    email: String = "",
    onBack: () -> Unit,
    onVerified: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var emailState by remember { mutableStateOf(email) }
    var code by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    var codeSent by remember { mutableStateOf(false) }
    var cooldown by remember { mutableStateOf(120) }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1000)
            cooldown--
        }
    }

    LaunchedEffect(Unit) {
        // If email not provided, fetch from session via me()
        if (email.isBlank()) {
            api.me().getOrNull()?.optJSONObject("user")?.optString("email")?.let { em ->
                if (em.isNotBlank()) {
                    emailState = em
                    sending = true
                    api.resendVerification(em).fold(
                        onSuccess = { codeSent = true; cooldown = 120 },
                        onFailure = { t -> error = t.message ?: "Failed to send code" }
                    )
                    sending = false
                }
            }
        }
    }

    LaunchedEffect(email) {
        if (email.isNotBlank() && !codeSent) {
            sending = true
            api.resendVerification(email).fold(
                onSuccess = { codeSent = true; cooldown = 120 },
                onFailure = { t -> error = t.message ?: "Failed to send code" }
            )
            sending = false
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
            if (codeSent) "Enter the 6-digit code sent to ${emailState.ifBlank { "your email" }}" else "Enter your email to receive a verification code",
            color = Gray40,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        if (email.isBlank()) {
            OutlinedTextField(
                value = emailState,
                onValueChange = { if (!codeSent) emailState = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !codeSent && !sending,
            )
            Spacer(Modifier.height(12.dp))
        } else {
            Spacer(Modifier.height(4.dp))
        }

        if (codeSent) {
            OutlinedTextField(
                value = code,
                onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) code = it },
                label = { Text("Verification Code") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center, letterSpacing = TextUnit(8f, TextUnitType.Sp)),
            )
        }
        Spacer(Modifier.height(8.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
        if (success) Text("Email verified!", color = Accent, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))

        if (!codeSent && email.isBlank()) {
            Button(
                onClick = {
                    if (emailState.isBlank()) { error = "Enter email"; return@Button }
                    sending = true; error = null
                    scope.launch {
                        api.resendVerification(emailState).fold(
                            onSuccess = {
                                codeSent = true
                                cooldown = 120
                                sending = false
                            },
                            onFailure = { t ->
                                error = t.message ?: "Failed to send code"
                                sending = false
                            },
                        )
                    }
                },
                enabled = !sending && emailState.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Send verification code")
            }
        } else if (!codeSent && email.isNotBlank()) {
            LaunchedEffect(Unit) {
                if (!sending) {
                    sending = true
                    api.resendVerification(email).fold(
                        onSuccess = { codeSent = true; cooldown = 120 },
                        onFailure = { t -> error = t.message ?: "Failed to send code" }
                    )
                    sending = false
                }
            }
        } else {
            Button(
                onClick = {
                    loading = true; error = null
                    scope.launch {
                         api.verifyEmail(emailState, code).fold(
                                onSuccess = { json ->
                                    val t = json.optString("token")
                                    if (t.isNotBlank()) {
                                        api.token = t
                                        val uname = json.optString("username").ifBlank { StoreSession.username(context) ?: "" }
                                        val role = json.optString("role", "user")
                                        val ev = json.optBoolean("emailVerified", true)
                                        StoreSession.save(context, t, uname, role, ev)
                                    } else {
                                        StoreSession.setEmailVerified(context, true)
                                    }
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
                enabled = !loading && code.length == 6,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Verify")
            }
            Spacer(Modifier.height(12.dp))
            if (cooldown > 0) {
                Text(String.format(Locale.US, "Didn't receive code? Resend in %02d:%02d", cooldown / 60, cooldown % 60), color = Gray40, style = MaterialTheme.typography.bodySmall)
            } else {
                TextButton(
                    onClick = {
                        sending = true; error = null
                        scope.launch {
                            api.resendVerification(emailState).fold(
                                onSuccess = { cooldown = 120 },
                                onFailure = { t -> error = t.message ?: "Resend failed" },
                            )
                            sending = false
                        }
                    },
                    enabled = !sending,
                ) {
                    if (sending) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Didn't receive code? Resend", color = Accent)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack) { Text("Back", color = Gray40) }
    }
}
