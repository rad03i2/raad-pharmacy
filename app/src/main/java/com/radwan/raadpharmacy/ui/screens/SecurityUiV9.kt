package com.radwan.raadpharmacy.ui.screens

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel

private enum class PinDialogMode {
    CREATE,
    CHANGE,
    DISABLE
}

@Composable
fun AppLockScreenV9(vm: PharmacyLedgerViewModel) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val security by vm.securityState.collectAsStateWithLifecycle()

    var pin by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var prompted by rememberSaveable { mutableStateOf(false) }

    val biometricAvailable = remember(context) {
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    val prompt = remember(activity) {
        activity?.let { host ->
            BiometricPrompt(
                host,
                ContextCompat.getMainExecutor(host),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        vm.unlockWithBiometric()
                        pin = ""
                        message = null
                    }

                    override fun onAuthenticationFailed() {
                        message = "لم يتم التحقق من البصمة أو الوجه."
                    }
                }
            )
        }
    }

    fun showBiometric() {
        if (!security.biometricEnabled || !biometricAvailable || prompt == null) return
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("فتح دفتر صيدلية رعد")
            .setSubtitle("استخدم البصمة أو الوجه")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setNegativeButtonText("استخدام PIN")
            .build()
        prompt.authenticate(info)
    }

    LaunchedEffect(security.biometricEnabled, biometricAvailable) {
        if (!prompted && security.biometricEnabled && biometricAvailable) {
            prompted = true
            showBiometric()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                modifier = Modifier.size(74.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("دفتر الصيدلية مقفل", style = MaterialTheme.typography.headlineSmall)
            Text(
                "أدخل PIN لفتح حسابات الزبائن والبيانات المالية.",
                modifier = Modifier.padding(top = 7.dp, bottom = 20.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("PIN") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true
            )

            message?.let {
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = {
                    val result = vm.unlockWithPin(pin)
                    message = result.message.takeUnless { result.success }
                    if (result.success) pin = ""
                },
                enabled = pin.length in 4..6,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(52.dp),
                shape = MaterialTheme.shapes.large
            ) {
                Icon(Icons.Rounded.LockOpen, null, modifier = Modifier.size(19.dp))
                Text("فتح التطبيق", modifier = Modifier.padding(horizontal = 7.dp))
            }

            if (security.biometricEnabled && biometricAvailable) {
                OutlinedButton(
                    onClick = ::showBiometric,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(50.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(Icons.Rounded.Fingerprint, null, modifier = Modifier.size(21.dp))
                    Text("استخدام البصمة / الوجه", modifier = Modifier.padding(horizontal = 7.dp))
                }
            }
        }
    }
}

@Composable
fun SecuritySettingsCardV9(vm: PharmacyLedgerViewModel) {
    val context = LocalContext.current
    val security by vm.securityState.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf<PinDialogMode?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val biometricAvailable = remember(context) {
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    mode?.let { currentMode ->
        PinManagementDialogV9(
            mode = currentMode,
            onDismiss = { mode = null },
            onSubmit = { currentPin, newPin ->
                val result = when (currentMode) {
                    PinDialogMode.CREATE -> vm.setPin(newPin)
                    PinDialogMode.CHANGE -> vm.changePin(currentPin, newPin)
                    PinDialogMode.DISABLE -> vm.disablePin(currentPin)
                }
                if (result.success) mode = null
                message = result.message
            }
        )
    }

    message?.let { value ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("الأمان") },
            text = { Text(value) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text("حسنًا") }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Lock,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(23.dp)
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text("قفل التطبيق", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (security.pinEnabled) "PIN مفعّل ومحمي بالتجزئة" else "غير مفعّل",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!security.pinEnabled) {
                    Button(onClick = { mode = PinDialogMode.CREATE }) {
                        Text("تفعيل")
                    }
                }
            }

            if (security.pinEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { mode = PinDialogMode.CHANGE },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("تغيير PIN")
                    }
                    OutlinedButton(
                        onClick = { mode = PinDialogMode.DISABLE },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("إلغاء القفل")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Fingerprint, null, modifier = Modifier.size(21.dp))
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text("البصمة / الوجه", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (biometricAvailable) "فتح سريع بعد تفعيل PIN" else "غير متوفر على هذا الهاتف",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = security.biometricEnabled,
                        onCheckedChange = {
                            if (biometricAvailable) vm.setBiometricEnabled(it)
                            else message = "لا توجد بصمة أو وسيلة حيوية متوافقة مفعّلة على الهاتف."
                        },
                        enabled = biometricAvailable
                    )
                }

                Text("القفل التلقائي بعد مغادرة التطبيق", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    listOf(
                        0 to "فورًا",
                        30 to "30ث",
                        60 to "1د",
                        300 to "5د"
                    ).forEach { (seconds, label) ->
                        FilterChip(
                            selected = security.lockTimeoutSeconds == seconds,
                            onClick = { vm.setLockTimeoutSeconds(seconds) },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                OutlinedButton(
                    onClick = vm::lockNow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Lock, null, modifier = Modifier.size(18.dp))
                    Text("قفل الآن", modifier = Modifier.padding(horizontal = 6.dp))
                }
            }

            PrivacySwitchV9(
                icon = Icons.Rounded.VisibilityOff,
                title = "إخفاء المبالغ",
                subtitle = "يخفي الأرقام المالية في الرئيسية وقائمة الزبائن وملف الزبون.",
                checked = security.hideAmounts,
                onCheckedChange = vm::setHideAmounts
            )

            PrivacySwitchV9(
                icon = Icons.Rounded.Lock,
                title = "منع لقطات الشاشة",
                subtitle = "يمنع Screenshot ويخفي محتوى التطبيق من شاشة التطبيقات الأخيرة.",
                checked = security.secureScreen,
                onCheckedChange = vm::setSecureScreen
            )
        }
    }
}

@Composable
private fun PrivacySwitchV9(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, modifier = Modifier.size(21.dp))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun PinManagementDialogV9(
    mode: PinDialogMode,
    onDismiss: () -> Unit,
    onSubmit: (currentPin: String, newPin: String) -> Unit
) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var validation by remember { mutableStateOf<String?>(null) }

    val needsCurrent = mode != PinDialogMode.CREATE
    val needsNew = mode != PinDialogMode.DISABLE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (mode) {
                    PinDialogMode.CREATE -> "إنشاء PIN"
                    PinDialogMode.CHANGE -> "تغيير PIN"
                    PinDialogMode.DISABLE -> "إلغاء قفل التطبيق"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (needsCurrent) {
                    PinFieldV9(
                        value = currentPin,
                        onValueChange = { currentPin = it },
                        label = "PIN الحالي"
                    )
                }
                if (needsNew) {
                    PinFieldV9(
                        value = newPin,
                        onValueChange = { newPin = it },
                        label = "PIN جديد"
                    )
                    PinFieldV9(
                        value = confirmPin,
                        onValueChange = { confirmPin = it },
                        label = "تأكيد PIN"
                    )
                    Text(
                        "استخدم 4 إلى 6 أرقام إنجليزية. لا يُخزن PIN كنص صريح.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                validation?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                validation = when {
                    needsCurrent && currentPin.length !in 4..6 -> "أدخل PIN الحالي."
                    needsNew && newPin.length !in 4..6 -> "PIN الجديد يجب أن يكون 4 إلى 6 أرقام."
                    needsNew && newPin != confirmPin -> "تأكيد PIN غير مطابق."
                    else -> null
                }
                if (validation == null) onSubmit(currentPin, newPin)
            }) {
                Text(
                    when (mode) {
                        PinDialogMode.CREATE -> "تفعيل"
                        PinDialogMode.CHANGE -> "حفظ"
                        PinDialogMode.DISABLE -> "إلغاء القفل"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("رجوع") }
        }
    )
}

@Composable
private fun PinFieldV9(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(6)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        singleLine = true
    )
}
