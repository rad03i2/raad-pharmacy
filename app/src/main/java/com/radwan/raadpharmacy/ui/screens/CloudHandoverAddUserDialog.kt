package com.radwan.raadpharmacy.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.cloud.CloudHandoverRegistration
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun CloudHandoverAddUserDialog(onClose: () -> Unit, onCreated: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("تسجيل مستخدم حقيقي") },
        text = {
            Column {
                Text("أنشئ حساباً جديداً للصيدلية. لن تظهر فيه حسابات التجربة.")
                OutlinedTextField(username, { username = it; error = false },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("اسم المستخدم بالإنجليزية") })
                OutlinedTextField(fullName, { fullName = it; error = false },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("الاسم الكامل") })
                OutlinedTextField(password, { password = it; error = false },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("كلمة مرور قوية (10 رموز فأكثر)") })
                if (error) Text("تعذر التسجيل. تأكد من البيانات وعدم استخدام الاسم مسبقاً.")
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && username.isNotBlank() && fullName.isNotBlank() &&
                password.length >= 10, onClick = {
                busy = true
                scope.launch {
                    try {
                        val ok = CloudHandoverRegistration.register(
                            username.trim().lowercase(Locale.US), fullName.trim(), password, null
                        )
                        if (ok) onCreated() else error = true
                    } catch (_: Exception) { error = true }
                    finally { busy = false }
                }
            }) { Text(if (busy) "جارٍ التسجيل…" else "إنشاء الحساب") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("إلغاء") } }
    )
}
