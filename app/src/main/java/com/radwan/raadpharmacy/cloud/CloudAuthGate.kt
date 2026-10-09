package com.radwan.raadpharmacy.cloud

import android.Manifest
import com.radwan.raadpharmacy.notifications.FollowupNotificationScheduler
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
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withStarted
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.radwan.raadpharmacy.ui.theme.PharmacyLedgerTheme
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun CloudAuthGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val auth = SupabaseProvider.client.auth
    val status by auth.sessionStatus.collectAsState()
    val prefs = remember {
        context.applicationContext.getSharedPreferences(
            CLOUD_AUTH_PREFS,
            Context.MODE_PRIVATE
        )
    }
    var readyForLiveLedger by remember { mutableStateOf(false) }
    var hasOfflineSession by remember {
        mutableStateOf(prefs.getBoolean(KEY_HAS_OFFLINE_SESSION, false))
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(status) {
        when (val current = status) {
            is SessionStatus.Authenticated -> {
                val email = auth.currentSessionOrNull()?.user?.email
                if (!CloudHandover.isLiveAccount(email)) {
                    // Old demonstration accounts cannot open the real pharmacy ledger.
                    readyForLiveLedger = false
                    hasOfflineSession = false
                    prefs.edit().remove(KEY_HAS_OFFLINE_SESSION).commit()
                    CloudContinuousListening.stop(context)
                    CloudSyncScheduler.disable(context)
                    runCatching { auth.signOut() }
                } else {
                    try {
                        withContext(Dispatchers.IO) { CloudHandoverLocalReset.prepare(context) }
                        readyForLiveLedger = true
                        hasOfflineSession = true
                        prefs.edit().putBoolean(KEY_HAS_OFFLINE_SESSION, true).apply()
                        lifecycle.withStarted {
                            CloudSyncScheduler.enable(context)
                            CloudContinuousListening.startFromVisibleApp(context)
                            FollowupNotificationScheduler.ensure(context)
                        }

                        if (
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED &&
                            !prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_PROMPTED, false)
                        ) {
                            prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_PROMPTED, true).apply()
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    } catch (_: Exception) {
                        // Never allow CloudSyncEngine.bootstrap to import old trial data.
                        readyForLiveLedger = false
                        CloudSyncScheduler.disable(context)
                    }
                }
            }
            is SessionStatus.NotAuthenticated -> {
                readyForLiveLedger = false
                if (current.isSignOut) {
                    hasOfflineSession = false
                    prefs.edit().remove(KEY_HAS_OFFLINE_SESSION).apply()
                    FollowupNotificationScheduler.cancel(context)
                }
            }
            else -> Unit
        }
    }

    PharmacyLedgerTheme {
        when (status) {
            is SessionStatus.Authenticated -> if (readyForLiveLedger) content() else LoadingCloudSession()
            SessionStatus.Initializing -> LoadingCloudSession()
            is SessionStatus.RefreshFailure -> {
                if (hasOfflineSession && readyForLiveLedger &&
                    CloudHandover.isLiveAccount(auth.currentSessionOrNull()?.user?.email)) {
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
    var registration by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf("") }
    var setupCode by remember { mutableStateOf("") }
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
                text = if (registration) "التسجيل الأول للتشغيل الحقيقي — بدون بيانات التجارب السابقة." else
                    "سجّل الدخول إلى دفتر الصيدلية الحقيقي.",
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
            if (registration) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = displayName, onValueChange = { displayName = it; error = null },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("الاسم الحقيقي للمستخدم") }
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = setupCode, onValueChange = { setupCode = it.trim(); error = null },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("رمز تهيئة الصيدلية (مرة واحدة)") }
                )
            }
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
                enabled = !working && username.isNotBlank() && password.isNotBlank() &&
                    (!registration || (displayName.isNotBlank() && setupCode.isNotBlank())),
                onClick = {
                    val canonical = canonicalUsername(username)
                    if (canonical == null) {
                        error = "اسم المستخدم غير صحيح."
                        return@Button
                    }

                    working = true
                    error = null
                    scope.launch {
                        try {
                            if (registration) {
                                val created = CloudHandoverRegistration.register(
                                    canonical, displayName.trim(), password, setupCode.lowercase()
                                )
                                check(created) { "REGISTRATION_FAILED" }
                            }
                            SupabaseProvider.client.auth.signInWith(Email) {
                                email = "$canonical" + CloudHandover.REAL_EMAIL_DOMAIN
                                this.password = password
                            }
                            setupCode = ""
                        } catch (_: Exception) {
                            error = if (registration)
                                "تعذر التسجيل. تحقق من رمز التهيئة والبيانات واتصال الإنترنت."
                            else "تعذر تسجيل الدخول. تحقق من الحساب الحقيقي وكلمة المرور."
                        } finally { working = false }
                    }
                }
            ) {
                if (working) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(if (registration) "إنشاء الحساب الأول" else "دخول")
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                registration = !registration
                error = null
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (registration) "العودة إلى تسجيل الدخول" else "إنشاء الحساب الأول للتسليم")
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

private const val KEY_NOTIFICATION_PERMISSION_PROMPTED = "notification_permission_prompted"
