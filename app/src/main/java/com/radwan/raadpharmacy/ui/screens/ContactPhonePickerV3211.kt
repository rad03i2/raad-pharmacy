package com.radwan.raadpharmacy.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ContactPhoneOption(
    val name: String,
    val original: String,
    val phone: String
)

internal fun normalizePickedPhone(raw: String): String {
    val digits = raw.filter(Char::isDigit)
    return when {
        digits.startsWith("00964") -> ("0" + digits.removePrefix("00964")).take(11)
        digits.startsWith("964") -> ("0" + digits.removePrefix("964")).take(11)
        digits.length == 10 && digits.startsWith("7") -> ("0" + digits).take(11)
        else -> digits.take(11)
    }
}

/**
 * In-app picker. Never launches an external contact activity, so the customer
 * editing form remains mounted and keeps its unsaved fields.
 */
@Composable
internal fun ContactPhonePickerV3211(
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var options by remember { mutableStateOf<List<ContactPhoneOption>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var authorized by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
    ) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        authorized = granted
        if (!granted) {
            failure = "لم تمنح صلاحية قراءة جهات الاتصال. يمكنك كتابة الرقم يدويًا."
            loading = false
        }
    }
    LaunchedEffect(Unit) {
        if (!authorized) permission.launch(Manifest.permission.READ_CONTACTS)
    }
    LaunchedEffect(authorized) {
        if (authorized) {
            loading = true
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val rows = mutableListOf<ContactPhoneOption>()
                    context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                        ),
                        "${ContactsContract.CommonDataKinds.Phone.NUMBER} IS NOT NULL AND " +
                            "${ContactsContract.CommonDataKinds.Phone.NUMBER} != ''",
                        null,
                        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC"
                    )?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        while (cursor.moveToNext()) {
                            val raw = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else ""
                            val phone = normalizePickedPhone(raw)
                            if (phone.isNotBlank()) {
                                rows += ContactPhoneOption(
                                    if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else "",
                                    raw,
                                    phone
                                )
                            }
                        }
                    }
                    rows.distinctBy { it.name + "|" + it.phone }
                }
            }
            result.onSuccess { options = it }
                .onFailure { failure = "تعذر قراءة جهات الاتصال. يمكنك كتابة الرقم يدويًا." }
            loading = false
        }
    }
    val visible = remember(options, search) {
        val query = search.trim().lowercase()
        if (query.isBlank()) options else options.filter {
            it.name.lowercase().contains(query) || it.original.contains(query) || it.phone.contains(query)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("جلب رقم الهاتف من جهات الاتصال") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("بحث بالاسم أو الرقم") },
                    singleLine = true
                )
                when {
                    loading -> CircularProgressIndicator()
                    failure != null -> Text(failure.orEmpty(), color = MaterialTheme.colorScheme.error)
                    visible.isEmpty() -> Text("لا توجد أرقام هاتف مطابقة.")
                    else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                        items(visible, key = { it.name + "|" + it.phone }) { option ->
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { onSelected(option.phone) }
                                    .padding(vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(option.name.ifBlank { "جهة اتصال" },
                                    style = MaterialTheme.typography.bodyLarge)
                                Text(option.original, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    )
}
