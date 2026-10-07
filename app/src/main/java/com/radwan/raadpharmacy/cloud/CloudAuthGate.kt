package com.radwan.raadpharmacy.cloud

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.radwan.raadpharmacy.ui.theme.PharmacyLedgerTheme
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun CloudAuthGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val auth = SupabaseProvider.client.auth
    val status by auth.sessionStatus.collectAsState()
    val prefs = remember {
        context.applicationContext.getSharedPreferences(
            CLOUD_AUTH_PREFS,
            Context.MODE_PRIVATE
        )
    }
    var hasOfflineSession by remember {
        mutableStateOf(prefs.getBoolean(KEY_HAS_OFFLINE_SESSION, false))
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(status) {
        when (val current = status) {
            is SessionStatus.Authenticated -> {
                hasOfflineSession = true
                prefs.edit().putBoolean(KEY_HAS_OFFLINE_SESSION, true).apply()

                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED &&
                    !prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_PROMPTED, false)
                ) {
                    prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_PROMPTED, true).apply()
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            is SessionStatus.NotAuthenticated -> {
                if (current.isSignOut) {
                    hasOfflineSession = false
                    prefs.edit().remove(KEY_HAS_OFFLINE_SESSION).apply()
                }
            }
            else -> Unit
        }
    }

    PharmacyLedgerTheme {
        when (status) {
            is SessionStatus.Authenticated -> content()
            SessionStatus.Initializing -> LoadingCloudSession()
            is SessionStatus.RefreshFailure -> {
                if (hasOfflineSession) {
                    content()
                } else {
                    CloudLoginScreen()
                }
            }
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
                text = "سجّل الدخول باسم المستخدم وكلمة المرور للوصول إلى الدفتر المشترك.",
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
                    val canonical = canonicalUsername(username)
                    if (canonical == null) {
                        error = "اسم المستخدم غير صحيح."
                        return@Button
                    }

                    working = true
                    error = null
                    scope.launch {
                        runCatching {
                            SupabaseProvider.client.auth.signInWith(Email) {
                                email = "$canonical@raad-pharmacy.local"
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
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("دخول")
                }
            }
        }
    }
}

private fun canonicalUsername(raw: String): String? {
    val normalized = raw.trim().lowercase(Locale.US)
    return when (normalized) {
        "raad", "رعد" -> "raad"
        "ahmed", "أحمد", "احمد" -> "ahmed"
        "fouad", "fuad", "فؤاد", "فواد" -> "fouad"
        "radwan", "رضوان" -> "radwan"
        else -> normalized.takeIf { it.matches(Regex("[a-z0-9._-]{2,32}")) }
    }
}

private const val CLOUD_AUTH_PREFS = "raad_cloud_auth"
private const val KEY_HAS_OFFLINE_SESSION = "has_offline_session"
