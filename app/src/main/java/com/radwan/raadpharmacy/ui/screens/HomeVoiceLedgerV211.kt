package com.radwan.raadpharmacy.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.data.DebtAnomalyWarning
import com.radwan.raadpharmacy.data.DebtCreateResult
import com.radwan.raadpharmacy.notifications.FinancialOperationFeedback
import com.radwan.raadpharmacy.notifications.FinancialOperationKind
import com.radwan.raadpharmacy.notifications.FinancialOperationReceipt
import com.radwan.raadpharmacy.speech.DebtSpeechError
import com.radwan.raadpharmacy.speech.DebtSpeechRecognizer
import com.radwan.raadpharmacy.speech.VoiceLedgerCommandParser
import com.radwan.raadpharmacy.speech.VoiceLedgerDraft
import com.radwan.raadpharmacy.speech.VoiceLedgerOperation
import com.radwan.raadpharmacy.speech.VoiceLedgerParseResult
import com.radwan.raadpharmacy.ui.components.CustomerAvatar
import com.radwan.raadpharmacy.ui.theme.PaidGreen
import com.radwan.raadpharmacy.util.formatMoney
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class HomeVoicePendingSave(
    val customerId: String,
    val amount: Long,
    val operation: VoiceLedgerOperation,
    val balanceBefore: Long
)

@Composable
internal fun HomeVoiceLedgerActionV211(
    vm: PharmacyLedgerViewModel
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val customers by vm.customers.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val recognizer = remember(context) { DebtSpeechRecognizer(context) }

    var listening by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var voiceError by remember { mutableStateOf<String?>(null) }

    var parsedDraft by remember { mutableStateOf<VoiceLedgerDraft?>(null) }
    var selectedCustomerId by remember { mutableStateOf<String?>(null) }
    var amountText by remember { mutableStateOf("") }
    var customerMenuOpen by remember { mutableStateOf(false) }
    var confirmationError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    var pendingSave by remember { mutableStateOf<HomeVoicePendingSave?>(null) }
    var anomalyWarning by remember { mutableStateOf<DebtAnomalyWarning?>(null) }
    var duplicateWarning by remember {
        mutableStateOf<DebtCreateResult.DuplicateDetected?>(null)
    }

    var successReceipt by remember { mutableStateOf<FinancialOperationReceipt?>(null) }
    var successTitle by remember { mutableStateOf("") }
    var successMessage by remember { mutableStateOf("") }
    var notificationAfterPermission by remember {
        mutableStateOf<FinancialOperationReceipt?>(null)
    }

    fun resetListening() {
        listening = false
        preparing = false
        processing = false
    }

    fun applyVoiceResult(alternatives: List<String>) {
        resetListening()
        recognizer.cancel()
        when (val parsed = VoiceLedgerCommandParser.parse(alternatives, customers)) {
            is VoiceLedgerParseResult.Success -> {
                parsedDraft = parsed.draft
                selectedCustomerId = parsed.draft.customerId
                amountText = parsed.draft.amount.toString()
                transcript = parsed.draft.heardText
                confirmationError = if (parsed.draft.customerId == null) {
                    "لم أحدد الزبون بثقة. اختر الزبون قبل التأكيد."
                } else {
                    null
                }
                voiceError = null
            }
            is VoiceLedgerParseResult.Error -> {
                parsedDraft = null
                voiceError = parsed.message
            }
        }
    }

    fun startListening() {
        if (listening || saving) return
        transcript = ""
        voiceError = null
        listening = true
        preparing = true
        processing = false

        val started = recognizer.start(
            DebtSpeechRecognizer.Callbacks(
                onPreparing = {
                    preparing = true
                    processing = false
                },
                onReady = { preparing = false },
                onPartial = { transcript = it },
                onEndOfSpeech = { processing = true },
                onFinal = ::applyVoiceResult,
                onError = { error ->
                    resetListening()
                    voiceError = when (error) {
                        DebtSpeechError.PERMISSION ->
                            "يحتاج الأمر الصوتي إلى إذن استخدام الميكروفون."
                        DebtSpeechError.BUSY ->
                            "الميكروفون مشغول. أعد المحاولة."
                        DebtSpeechError.LANGUAGE ->
                            "التعرف الصوتي العربي غير متاح حاليًا."
                        DebtSpeechError.NETWORK ->
                            "تعذر الوصول إلى خدمة التعرف الصوتي."
                        DebtSpeechError.UNAVAILABLE,
                        DebtSpeechError.START_FAILED ->
                            "خدمة التعرف الصوتي غير متاحة على الهاتف."
                        else ->
                            "لم أتمكن من فهم الأمر. أعد المحاولة بصيغة: سجل دين 5000 للزبون أحمد."
                    }
                }
            )
        )
        if (!started) resetListening()
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening()
        else voiceError = "يحتاج الأمر الصوتي إلى إذن استخدام الميكروفون."
    }

    fun onMicClick() {
        if (listening) {
            recognizer.cancel()
            resetListening()
            return
        }
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(recognizer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                recognizer.cancel()
                resetListening()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            recognizer.destroy()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val receipt = notificationAfterPermission
        notificationAfterPermission = null
        if (granted && receipt != null) {
            vm.scheduleFinancialOperationNotification(receipt)
        }
    }

    fun notifyAfterSuccessConfirmation(receipt: FinancialOperationReceipt) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationAfterPermission = receipt
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (FinancialOperationFeedback.canPostNotifications(context)) {
            vm.scheduleFinancialOperationNotification(receipt)
        }
    }

    fun markSuccess(
        pending: HomeVoicePendingSave,
        customerName: String,
        balanceAfter: Long
    ) {
        val kind = when {
            pending.operation == VoiceLedgerOperation.DEBT ->
                FinancialOperationKind.DEBT
            balanceAfter == 0L ->
                FinancialOperationKind.FULL_SETTLEMENT
            else ->
                FinancialOperationKind.PAYMENT
        }
        successReceipt = FinancialOperationReceipt(
            kind = kind,
            customerId = pending.customerId,
            customerName = customerName,
            amount = pending.amount,
            balanceAfter = balanceAfter
        )
        successTitle = when (kind) {
            FinancialOperationKind.DEBT -> "تم تسجيل الدين"
            FinancialOperationKind.PAYMENT -> "تم تسجيل التحصيل"
            FinancialOperationKind.FULL_SETTLEMENT -> "تم تسديد الحساب"
        }
        successMessage = when (kind) {
            FinancialOperationKind.DEBT ->
                "أضيف " + formatMoney(pending.amount) + " إلى حساب " + customerName
            FinancialOperationKind.PAYMENT ->
                "تم تحصيل " + formatMoney(pending.amount) + " من " + customerName
            FinancialOperationKind.FULL_SETTLEMENT ->
                "أصبح رصيد " + customerName + " صفرًا."
        }
        parsedDraft = null
        pendingSave = null
        vm.playFinancialSuccessSound()
    }

    suspend fun persistDebt(
        pending: HomeVoicePendingSave,
        allowDuplicate: Boolean
    ) {
        val customer = vm.customer(pending.customerId) ?: run {
            saving = false
            confirmationError = "تعذر العثور على الزبون."
            return
        }

        val result = withContext(Dispatchers.IO) {
            vm.addDebt(
                customerId = pending.customerId,
                amount = pending.amount,
                bottles = null,
                bottlePrice = null,
                allowRecentDuplicate = allowDuplicate
            )
        }
        saving = false

        when (result) {
            is DebtCreateResult.Created -> {
                duplicateWarning = null
                markSuccess(
                    pending = pending,
                    customerName = customer.name,
                    balanceAfter = pending.balanceBefore + pending.amount
                )
            }
            is DebtCreateResult.DuplicateDetected -> {
                pendingSave = pending
                duplicateWarning = result
            }
        }
    }

    fun confirmCommand() {
        if (saving) return
        val draft = parsedDraft ?: return
        val customerId = selectedCustomerId
        val customer = customerId?.let(vm::customer)
        val amount = amountText.toLongOrNull() ?: 0L

        if (customer == null) {
            confirmationError = "اختر الزبون أولًا."
            return
        }
        if (amount <= 0L) {
            confirmationError = "أدخل مبلغًا صحيحًا."
            return
        }

        val balance = vm.balance(customer)
        val pending = HomeVoicePendingSave(
            customerId = customer.id,
            amount = amount,
            operation = draft.operation,
            balanceBefore = balance
        )
        pendingSave = pending
        confirmationError = null
        saving = true

        scope.launch {
            when (draft.operation) {
                VoiceLedgerOperation.DEBT -> {
                    val warning = runCatching {
                        withContext(Dispatchers.IO) {
                            vm.debtAnomalyWarning(customer.id, amount)
                        }
                    }.getOrNull()
                    if (warning != null) {
                        saving = false
                        anomalyWarning = warning
                    } else {
                        persistDebt(pending, allowDuplicate = false)
                    }
                }

                VoiceLedgerOperation.PAYMENT -> {
                    if (balance <= 0L || amount > balance) {
                        saving = false
                        confirmationError = if (balance <= 0L) {
                            "حساب هذا الزبون مسدد ولا يوجد مبلغ للتحصيل."
                        } else {
                            "مبلغ التحصيل أكبر من الدين الحالي " + formatMoney(balance)
                        }
                        return@launch
                    }
                    val success = withContext(Dispatchers.IO) {
                        vm.addPayment(customer.id, amount)
                    }
                    saving = false
                    if (success) {
                        markSuccess(
                            pending = pending,
                            customerName = customer.name,
                            balanceAfter = (balance - amount).coerceAtLeast(0L)
                        )
                    } else {
                        confirmationError = "تعذر تسجيل التحصيل. أعد المحاولة."
                    }
                }
            }
        }
    }

    HomeVoiceMicButtonV211(
        listening = listening,
        onClick = ::onMicClick
    )

    if (listening || preparing || processing) {
        HomeVoiceListeningDialogV211(
            preparing = preparing,
            processing = processing,
            transcript = transcript,
            onCancel = {
                recognizer.cancel()
                resetListening()
            }
        )
    }

    voiceError?.let { message ->
        HomeVoiceMessageDialogV211(
            message = message,
            onDismiss = { voiceError = null }
        )
    }

    parsedDraft?.let { draft ->
        val selectedCustomer = selectedCustomerId?.let(vm::customer)
        HomeVoiceConfirmationDialogV211(
            operation = draft.operation,
            customers = customers,
            selectedCustomerName = selectedCustomer?.name,
            amountText = amountText,
            transcript = draft.heardText,
            saving = saving,
            error = confirmationError,
            menuOpen = customerMenuOpen,
            onMenuOpenChange = { customerMenuOpen = it },
            onCustomerSelected = {
                selectedCustomerId = it
                customerMenuOpen = false
                confirmationError = null
            },
            onAmountChange = {
                amountText = it.filter(Char::isDigit).take(12)
                confirmationError = null
            },
            onCancel = {
                if (!saving) {
                    parsedDraft = null
                    pendingSave = null
                }
            },
            onConfirm = ::confirmCommand
        )
    }

    anomalyWarning?.let { warning ->
        val pending = pendingSave
        val customer = pending?.customerId?.let(vm::customer)
        if (pending != null && customer != null) {
            V29DebtAnomalyDialog(
                customerName = customer.name,
                warning = warning,
                onReview = {
                    anomalyWarning = null
                    saving = false
                },
                onConfirm = {
                    anomalyWarning = null
                    saving = true
                    scope.launch { persistDebt(pending, allowDuplicate = false) }
                }
            )
        }
    }

    duplicateWarning?.let { warning ->
        val pending = pendingSave
        val customer = pending?.customerId?.let(vm::customer)
        if (pending != null && customer != null) {
            V29DuplicateDebtDialog(
                customerName = customer.name,
                amount = pending.amount,
                secondsAgo = warning.secondsAgo,
                onCancel = {
                    duplicateWarning = null
                    saving = false
                },
                onForceSave = {
                    duplicateWarning = null
                    saving = true
                    scope.launch { persistDebt(pending, allowDuplicate = true) }
                }
            )
        }
    }

    successReceipt?.let { receipt ->
        V12SuccessDialog(
            title = successTitle,
            message = successMessage,
            onDone = {
                notifyAfterSuccessConfirmation(receipt)
                successReceipt = null
            }
        )
    }
}

@Composable
private fun HomeVoiceMicButtonV211(
    listening: Boolean,
    onClick: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "home-voice-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "home-voice-pulse-value"
    )

    Box(
        modifier = Modifier.size(48.dp),
        contentAlignment = Alignment.Center
    ) {
        if (listening) {
            val primary = MaterialTheme.colorScheme.primary
            Canvas(modifier = Modifier.size(48.dp)) {
                drawCircle(
                    color = primary.copy(alpha = 0.16f),
                    radius = size.minDimension * 0.5f * pulse
                )
                drawCircle(
                    color = primary.copy(alpha = 0.45f),
                    radius = size.minDimension / 2f - 2.dp.toPx(),
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = if (listening) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    contentDescription = if (listening) "إيقاف الأمر الصوتي" else "تسجيل دين أو تحصيل بالصوت",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun HomeVoiceListeningDialogV211(
    preparing: Boolean,
    processing: Boolean,
    transcript: String,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HomeVoiceMicButtonV211(listening = true, onClick = onCancel)
                Text(
                    when {
                        preparing -> "جاري تجهيز الميكروفون..."
                        processing -> "جاري فهم الأمر..."
                        else -> "جارٍ الاستماع..."
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (transcript.isBlank()) {
                        "مثال: سجل دين خمسة آلاف للزبون أحمد"
                    } else {
                        "سمعت: " + transcript
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("إلغاء")
                }
            }
        }
    }
}

@Composable
private fun HomeVoiceConfirmationDialogV211(
    operation: VoiceLedgerOperation,
    customers: List<com.radwan.raadpharmacy.data.Customer>,
    selectedCustomerName: String?,
    amountText: String,
    transcript: String,
    saving: Boolean,
    error: String?,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onCustomerSelected: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = { if (!saving) onCancel() }) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "تأكيد الأمر الصوتي",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        if (operation == VoiceLedgerOperation.DEBT) "إضافة دين" else "تسجيل تحصيل",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box {
                    OutlinedButton(
                        onClick = { onMenuOpenChange(true) },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text(selectedCustomerName ?: "اختر الزبون")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { onMenuOpenChange(false) }
                    ) {
                        customers.sortedBy { it.name }.forEach { customer ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                                    ) {
                                        CustomerAvatar(
                                            customerId = customer.id,
                                            size = 32.dp
                                        )
                                        Text(customer.name)
                                    }
                                },
                                onClick = { onCustomerSelected(customer.id) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = onAmountChange,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("المبلغ") },
                    suffix = { Text("د.ع") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Text(
                    "سمعت: " + transcript,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onCancel,
                        enabled = !saving,
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) {
                        Text("إلغاء")
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = !saving,
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) {
                        Text(if (saving) "جاري الحفظ..." else "تأكيد")
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeVoiceMessageDialogV211(
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
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "الأمر الصوتي",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("حسنًا")
                }
            }
        }
    }
}
