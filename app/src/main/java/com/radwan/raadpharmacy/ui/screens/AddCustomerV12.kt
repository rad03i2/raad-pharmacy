package com.radwan.raadpharmacy.ui.screens

import com.radwan.raadpharmacy.ui.components.rememberLedgerFlingBehavior

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.ContactPhone
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.data.CustomerDuplicateMatches
import com.radwan.raadpharmacy.data.CustomerFormDraft
import com.radwan.raadpharmacy.data.FormDraftStore
import com.radwan.raadpharmacy.data.findCustomerDuplicates
import com.radwan.raadpharmacy.customer.CustomerPhotoStore
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.util.formatMoney
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private data class CustomerPhoneContact(
    val name: String,
    val displayNumber: String,
    val normalizedNumber: String
)

private fun normalizeCustomerPhone(raw: String): String {
    val digits = raw.filter(Char::isDigit)
    return when {
        digits.startsWith("00964") -> ("0" + digits.removePrefix("00964")).take(11)
        digits.startsWith("964") -> ("0" + digits.removePrefix("964")).take(11)
        digits.length == 10 && digits.startsWith("7") -> ("0" + digits).take(11)
        else -> digits.take(11)
    }
}

@Composable
fun AddCustomerScreenV12(
    vm: PharmacyLedgerViewModel,
    onBack: () -> Unit,
    onSaved: (String) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val photoStore = remember(context) { CustomerPhotoStore(context) }
    val customers by vm.customers.collectAsStateWithLifecycle()
    val restoredDraft = remember(context) { FormDraftStore.loadCustomerDraft(context) }

    var customerOperationId by rememberSaveable { mutableStateOf(restoredDraft?.operationId ?: UUID.randomUUID().toString()) }
    var name by rememberSaveable { mutableStateOf(restoredDraft?.name.orEmpty()) }
    var phone by rememberSaveable { mutableStateOf(restoredDraft?.phone.orEmpty()) }
    var area by rememberSaveable { mutableStateOf(restoredDraft?.area.orEmpty()) }
    var openingDebt by rememberSaveable { mutableStateOf(restoredDraft?.openingDebt.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(restoredDraft?.notes.orEmpty()) }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }
    // Saving state is deliberately transient; a process restart must never leave the form stuck.
    var isSaving by remember { mutableStateOf(false) }

    var selectedPhotoText by rememberSaveable { mutableStateOf(restoredDraft?.selectedPhotoUri) }
    val selectedPhoto = selectedPhotoText?.let(Uri::parse)
    var cameraUriText by rememberSaveable { mutableStateOf(restoredDraft?.cameraUri) }
    var showPhotoSources by remember { mutableStateOf(false) }
    var showContactPicker by rememberSaveable { mutableStateOf(false) }
    var contactSearch by rememberSaveable { mutableStateOf("") }
    var contactEntries by remember { mutableStateOf<List<CustomerPhoneContact>>(emptyList()) }
    var loadingContacts by remember { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }
    var duplicateWarning by remember { mutableStateOf<CustomerDuplicateMatches?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        FormDraftStore.markActiveRoute(context, "add_customer")
    }
    LaunchedEffect(customerOperationId, name, phone, area, openingDebt, notes, selectedPhotoText, cameraUriText) {
        FormDraftStore.saveCustomerDraft(
            context,
            CustomerFormDraft(
                operationId = customerOperationId,
                name = name,
                phone = phone,
                area = area,
                openingDebt = openingDebt,
                notes = notes,
                selectedPhotoUri = selectedPhotoText,
                cameraUri = cameraUriText
            )
        )
    }
    val filteredContacts = remember(contactEntries, contactSearch) {
        val query = contactSearch.trim().lowercase()
        if (query.isBlank()) contactEntries
        else contactEntries.filter {
            it.name.lowercase().contains(query) ||
                it.displayNumber.contains(query) ||
                it.normalizedNumber.contains(query)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching { photoStore.persistDraftUri(uri) }
                .onSuccess { persistedUri ->
                    photoStore.deleteTemporaryDraftUri(selectedPhoto)
                    selectedPhotoText = persistedUri.toString()
                    errorText = null
                }
                .onFailure {
                    infoMessage = "تعذر حفظ الصورة المختارة مؤقتًا. حاول اختيارها مرة أخرى."
                }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val cameraUri = cameraUriText?.let(Uri::parse)
        if (success && cameraUri != null) {
            photoStore.deleteTemporaryDraftUri(selectedPhoto)
            selectedPhotoText = cameraUri.toString()
            errorText = null
        } else {
            photoStore.deleteTemporaryDraftUri(cameraUri)
        }
        cameraUriText = null
    }

    fun openCamera() {
        runCatching {
            val uri = photoStore.createTemporaryCameraUri()
            cameraUriText = uri.toString()
            cameraLauncher.launch(uri)
        }.onFailure {
            cameraUriText = null
            infoMessage = "تعذر فتح الكاميرا على هذا الهاتف."
        }
    }

    fun loadContacts() {
        if (loadingContacts) return
        showContactPicker = true
        contactSearch = ""
        loadingContacts = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val entries = mutableListOf<CustomerPhoneContact>()
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
                        val nameIndex = cursor.getColumnIndex(
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                        )
                        val numberIndex = cursor.getColumnIndex(
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                        )
                        while (cursor.moveToNext()) {
                            val rawNumber = if (numberIndex >= 0) {
                                cursor.getString(numberIndex).orEmpty()
                            } else {
                                ""
                            }
                            val normalized = normalizeCustomerPhone(rawNumber)
                            if (normalized.isNotBlank()) {
                                entries += CustomerPhoneContact(
                                    name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else "",
                                    displayNumber = rawNumber,
                                    normalizedNumber = normalized
                                )
                            }
                        }
                    }
                    entries.distinctBy { it.name + "|" + it.normalizedNumber }
                }
            }
            result.onSuccess { loaded ->
                contactEntries = loaded
                if (loaded.isEmpty()) {
                    showContactPicker = false
                    infoMessage = "لم يتم العثور على أرقام هاتف في جهات الاتصال."
                }
            }.onFailure {
                showContactPicker = false
                infoMessage = "تعذر قراءة جهات الاتصال. تحقق من صلاحية الوصول ثم حاول مجددًا."
            }
            loadingContacts = false
        }
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadContacts()
        else infoMessage = "اسمح للتطبيق بالوصول إلى جهات الاتصال لاختيار رقم الهاتف."
    }

    fun pickContact() {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            loadContacts()
        } else {
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    fun requestSave() {
        if (isSaving) return
        if (name.isBlank()) {
            errorText = "اسم الزبون مطلوب."
            return
        }
        focusManager.clearFocus()
        errorText = null
        val duplicates = findCustomerDuplicates(name, phone, customers, excludeCustomerId = customerOperationId)
        if (duplicates.hasMatches) {
            duplicateWarning = duplicates
            return
        }
        showConfirmation = true
    }

    fun confirmSave() {
        if (isSaving) return
        isSaving = true
        showConfirmation = false

        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val customer = vm.addCustomer(
                        name = name.trim(),
                        phone = phone.takeIf { it.isNotBlank() },
                        area = area.trim(),
                        address = "",
                        openingDebt = openingDebt.toLongOrNull() ?: 0L,
                        notes = notes.trim(),
                        operationId = customerOperationId
                    )
                    selectedPhoto?.let { uri ->
                        runCatching {
                            photoStore.save(customer.id, uri)
                            // Customer creation must finish offline without waiting for photo upload.
                            com.radwan.raadpharmacy.cloud.CloudSyncRuntime.requestSync(context)
                        }
                    }
                    customer
                }
            }

            isSaving = false
            result.onSuccess { customer ->
                FormDraftStore.clearCustomerDraft(context)
                vm.playFinancialSuccessSound()
                onSaved(customer.id)
            }.onFailure {
                errorText = com.radwan.raadpharmacy.data.ledgerSaveError(it, "تعذر حفظ الزبون. حاول مرة أخرى.")
            }
        }
    }

    duplicateWarning?.let { warning ->
        AlertDialog(
            onDismissRequest = { duplicateWarning = null },
            title = { Text("تنبيه: احتمال تكرار الزبون") },
            text = {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    if (warning.sameName.isNotEmpty()) {
                        Text("يوجد اسم مطابق أو متشابه مع زبون محفوظ:")
                        warning.sameName.take(4).forEach { existing ->
                            Text("• " + existing.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (warning.samePhone.isNotEmpty()) {
                        Text("رقم الهاتف مستخدم مسبقًا لدى:")
                        warning.samePhone.take(4).forEach { existing ->
                            Text("• " + existing.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text("راجع الاسم والرقم لتجنب تسجيل الزبون مرتين.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    duplicateWarning = null
                    showConfirmation = true
                }) { Text("متابعة إلى الحفظ") }
            },
            dismissButton = {
                TextButton(onClick = { duplicateWarning = null }) { Text("مراجعة البيانات") }
            }
        )
    }

    if (showContactPicker) {
        Dialog(onDismissRequest = { showContactPicker = false }) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("اختيار رقم الهاتف", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = contactSearch,
                        onValueChange = { contactSearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("بحث بالاسم أو الرقم") }
                    )
                    when {
                        loadingContacts -> Box(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                        filteredContacts.isEmpty() -> Text(
                            if (contactEntries.isEmpty()) "لا توجد أرقام هاتف لعرضها."
                            else "لا توجد نتائج مطابقة للبحث.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                        ) {
                            items(
                                items = filteredContacts,
                                key = { "${it.name}|${it.normalizedNumber}" }
                            ) { entry ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            phone = entry.normalizedNumber
                                            if (name.isBlank() && entry.name.isNotBlank()) {
                                                name = entry.name
                                            }
                                            errorText = null
                                            showContactPicker = false
                                            focusManager.clearFocus()
                                        }
                                        .padding(vertical = 12.dp, horizontal = 4.dp)
                                ) {
                                    Text(
                                        entry.name.ifBlank { "جهة اتصال بدون اسم" },
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        entry.displayNumber,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    TextButton(
                        onClick = { showContactPicker = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("إلغاء")
                    }
                }
            }
        }
    }

    if (showPhotoSources) {
        CustomerPhotoSourceDialogV211(
            hasPhoto = selectedPhoto != null,
            onCamera = {
                showPhotoSources = false
                openCamera()
            },
            onGallery = {
                showPhotoSources = false
                galleryLauncher.launch("image/*")
            },
            onRemove = {
                photoStore.deleteTemporaryDraftUri(selectedPhoto)
                selectedPhotoText = null
                showPhotoSources = false
            },
            onDismiss = { showPhotoSources = false }
        )
    }

    if (showConfirmation) {
        AddCustomerConfirmationDialogV211(
            name = name.trim(),
            phone = phone,
            area = area.trim(),
            openingDebt = openingDebt.toLongOrNull() ?: 0L,
            notes = notes.trim(),
            photo = selectedPhoto,
            saving = isSaving,
            onEdit = { showConfirmation = false },
            onConfirm = ::confirmSave
        )
    }

    infoMessage?.let { message ->
        SimpleCustomerInfoDialogV211(
            message = message,
            onDismiss = { infoMessage = null }
        )
    }

    Scaffold(
        topBar = {
            ScreenTopBar(
                if (isSaving) "جاري الحفظ..." else "زبون جديد",
                if (isSaving) null else {
                    {
                        photoStore.deleteTemporaryDraftUri(selectedPhoto)
                        FormDraftStore.clearCustomerDraft(context)
                        onBack()
                    }
                }
            )
        },
        bottomBar = {
            Button(
                onClick = ::requestSave,
                enabled = !isSaving && name.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(12.dp)
                    .height(58.dp),
                shape = MaterialTheme.shapes.large
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        "جاري حفظ الزبون...",
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                } else {
                    Text(
                        "حفظ الزبون",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState(), flingBehavior = rememberLedgerFlingBehavior())
                .padding(PaddingValues(14.dp, 8.dp, 14.dp, 20.dp)),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    CustomerPhotoPreviewV211(
                        uri = selectedPhoto,
                        size = 112.dp
                    )
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.primary,
                        shadowElevation = 4.dp
                    ) {
                        IconButton(
                            onClick = { showPhotoSources = true },
                            enabled = !isSaving,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(
                                Icons.Rounded.CameraAlt,
                                contentDescription = "إضافة صورة للزبون",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }

                Text(
                    if (selectedPhoto == null) "إضافة صورة للزبون" else "تغيير صورة الزبون",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(enabled = !isSaving) {
                        showPhotoSources = true
                    }
                )
            }

            Text(
                "أدخل البيانات الأساسية، ثم راجعها في بطاقة التأكيد قبل الحفظ.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    Text(
                        "بيانات الزبون",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            errorText = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("اسم الزبون *") },
                        singleLine = true,
                        enabled = !isSaving,
                        isError = errorText != null && name.isBlank(),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = {
                                focusManager.moveFocus(FocusDirection.Down)
                            }
                        ),
                        shape = MaterialTheme.shapes.large
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        OutlinedTextField(
                            value = phone,
                            onValueChange = {
                                phone = it.filter(Char::isDigit).take(11)
                                errorText = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("رقم الهاتف") },
                            placeholder = { Text("07XXXXXXXXX") },
                            singleLine = true,
                            enabled = !isSaving,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Phone,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = {
                                    focusManager.moveFocus(FocusDirection.Down)
                                }
                            ),
                            shape = MaterialTheme.shapes.large
                        )

                        TextButton(
                            onClick = ::pickContact,
                            enabled = !isSaving
                        ) {
                            Icon(
                                Icons.Rounded.ContactPhone,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "جلب من جهات الاتصال",
                                modifier = Modifier.padding(horizontal = 6.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = area,
                        onValueChange = { area = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("المنطقة / الحي") },
                        singleLine = true,
                        enabled = !isSaving,
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = {
                                focusManager.moveFocus(FocusDirection.Down)
                            }
                        ),
                        shape = MaterialTheme.shapes.large
                    )
                }
            }

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    Text(
                        "الحساب",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = openingDebt,
                        onValueChange = {
                            openingDebt = it.filter(Char::isDigit).take(12)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("الدين السابق") },
                        suffix = { Text("د.ع") },
                        singleLine = true,
                        enabled = !isSaving,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = {
                                focusManager.moveFocus(FocusDirection.Down)
                            }
                        ),
                        shape = MaterialTheme.shapes.large
                    )

                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("ملاحظات") },
                        minLines = 2,
                        enabled = !isSaving,
                        shape = MaterialTheme.shapes.large
                    )
                }
            }

            errorText?.let {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomerPhotoSourceDialogV211(
    hasPhoto: Boolean,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "تنبيه",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "اختر مصدر الصورة",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                CustomerPhotoOptionV211(
                    icon = Icons.Rounded.CameraAlt,
                    title = "التقاط صورة بالكاميرا",
                    onClick = onCamera
                )
                CustomerPhotoOptionV211(
                    icon = Icons.Rounded.PhotoLibrary,
                    title = "اختيار من المعرض",
                    onClick = onGallery
                )
                if (hasPhoto) {
                    TextButton(
                        onClick = onRemove,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            "إزالة الصورة",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                    }
                }

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("إغلاق")
                }
            }
        }
    }
}

@Composable
private fun CustomerPhotoOptionV211(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    supporting: String? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                supporting?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AddCustomerConfirmationDialogV211(
    name: String,
    phone: String,
    area: String,
    openingDebt: Long,
    notes: String,
    photo: Uri?,
    saving: Boolean,
    onEdit: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = { if (!saving) onEdit() }) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CustomerPhotoPreviewV211(photo, 86.dp)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "راجع بيانات الزبون",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        CustomerConfirmLineV211("الاسم", name)
                        CustomerConfirmLineV211(
                            "الهاتف",
                            phone.ifBlank { "غير مضاف" }
                        )
                        CustomerConfirmLineV211(
                            "المنطقة",
                            area.ifBlank { "غير محددة" }
                        )
                        CustomerConfirmLineV211(
                            "الدين السابق",
                            formatMoney(openingDebt)
                        )
                        if (notes.isNotBlank()) {
                            CustomerConfirmLineV211("ملاحظات", notes)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onEdit,
                        enabled = !saving,
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) {
                        Text("تعديل")
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = !saving,
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("تأكيد الحفظ")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomerConfirmLineV211(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 14.dp)
        )
    }
}

@Composable
private fun SimpleCustomerInfoDialogV211(
    message: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "صورة الزبون",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("حسنًا")
                }
            }
        }
    }
}
