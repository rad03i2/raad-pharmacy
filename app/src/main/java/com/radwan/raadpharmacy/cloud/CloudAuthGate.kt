package com.radwan.raadpharmacy.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.ui.theme.PharmacyLedgerTheme
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun CloudAuthGate(content: @Composable () -> Unit) {
    val auth = SupabaseProvider.client.auth
    val status by auth.sessionStatus.collectAsState()

    PharmacyLedgerTheme {
        when (status) {
            is SessionStatus.Authenticated -> content()
            SessionStatus.Initializing -> LoadingCloudSession()
            is SessionStatus.RefreshFailure,
            is SessionStatus.NotAuthenticated -> CloudLoginScreen()
        }
    }
}

@Composable
private fun LoadingCloudSession() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("جاري فتح حساب صيدلية رعد…")
        }
    }
}

@Composable
private fun CloudLoginScreen() {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "دفتر صيدلية رعد",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "سجّل الدخول بحسابك للوصول إلى الدفتر المشترك.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { username = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("اسم المستخدم") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("كلمة المرور") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !working && username.isNotBlank() && password.isNotBlank(),
                onClick = {
                    val normalized = username.trim().lowercase(Locale.US)
                    if (!normalized.matches(Regex("[a-z0-9._-]{2,32}"))) {
                        error = "اسم المستخدم غير صحيح."
                        return@Button
                    }

                    working = true
                    error = null
                    scope.launch {
                        runCatching {
                            SupabaseProvider.client.auth.signInWith(Email) {
                                email = "$normalized@raad-pharmacy.local"
                                this.password = password
                            }
                        }.onFailure {
                            error = "تعذر تسجيل الدخول. تحقق من اسم المستخدم وكلمة المرور والإنترنت."
                        }
                        working = false
                    }
                }
            ) {
                if (working) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("دخول")
                }
            }
        }
    }
}
