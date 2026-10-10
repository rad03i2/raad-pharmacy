package com.radwan.raadpharmacy.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.data.DebtAnomalyWarning
import com.radwan.raadpharmacy.data.DebtCreateResult
import com.radwan.raadpharmacy.notifications.FinancialOperationFeedback
import com.radwan.raadpharmacy.notifications.FinancialOperationKind
import com.radwan.raadpharmacy.notifications.FinancialOperationReceipt
import com.radwan.raadpharmacy.speech.ArabicDebtAmountParser
import com.radwan.raadpharmacy.speech.DebtSpeechError
import com.radwan.raadpharmacy.speech.DebtSpeechRecognizer
import com.radwan.raadpharmacy.speech.SpeechAmountParseResult
import com.radwan.raadpharmacy.ui.components.CustomerAvatar
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.ui.components.SoftDivider
import com.radwan.raadpharmacy.ui.theme.DebtRed
import com.radwan.raadpharmacy.ui.theme.PaidGreen
import com.radwan.raadpharmacy.util.formatMoney
import com.radwan.raadpharmacy.util.accumulatedAmount
import com.radwan.raadpharmacy.ui.components.CompactFinanceHeader
import com.radwan.raadpharmacy.ui.components.FinancialQuickAmounts
import com.radwan.raadpharmacy.ui.components.CompactBalanceSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MICROPHONE_PERMISSION_V29 = "android.permission." + "RECORD_AUDIO"

private data class DebtDraftV29(
    val amount: Long,
    val details: String,
    val balanceBefore: Long
)

@Composable
fun AddDebtScreenV12(
    vm: PharmacyLedgerViewModel,
    customerId: String,
    onBack: () -> Unit
) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val customer = customers.firstOrNull { it.id == customerId }
    if (customer == null) {
        V12MissingCustomer(onBack)
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val financialFeedback = rememberFinancialFeedbackHandler(vm)
    val speechRecognizer = remember(context) { DebtSpeechRecognizer(context) }
    val previousBalance = remember(customer, entries) { com.radwan.raadpharmacy.data.customerBalance(customer, entries) }

    var amountText by rememberSaveable { mutableStateOf("") }
    var detailsText by rememberSaveable { mutableStateOf("") }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }
    var isSaving by rememberSaveable { mutableStateOf(false) }
    var savedAmount by rememberSaveable { mutableStateOf<Long?>(null) }

    var pendingAnomaly by remember { mutableStateOf<DebtAnomalyWarning?>(null) }
    var pendingAnomalyDraft by remember { mutableStateOf<DebtDraftV29?>(null) }
    var pendingDuplicate by remember { mutableStateOf<DebtCreateResult.DuplicateDetected?>(null) }
    var pendingDuplicateDraft by remember { mutableStateOf<DebtDraftV29?>(null) }

    var voiceListening by remember { mutableStateOf(false) }
    var voicePreparing by remember { mutableStateOf(false) }
    var voiceProcessing by remember { mutableStateOf(false) }
    var voiceTranscript by remember { mutableStateOf("") }
    var voiceFeedback by remember { mutableStateOf<String?>(null) }
    var voiceError by remember { mutableStateOf<String?>(null) }

    val amount = amountText.toLongOrNull() ?: 0L
    val canSubmit = amount > 0L && !isSaving && !voiceListening

    fun applyVoiceResult(parsed: SpeechAmountParseResult, heard: String?) {
        heard?.takeIf { it.isNotBlank() }?.let { voiceTranscript = it }
        when (parsed) {
            is SpeechAmountParseResult.Success -> {
                if (parsed.amount > 999_999_999_999L) {
                    voiceFeedback = null
                    voiceError = "المبلغ الذي تم سماعه أكبر من الحد المسموح."
                } else {
                    amountText = parsed.amount.toString()
                    errorText = null
                    voiceError = null
                    voiceFeedback = "تم التعرف على المبلغ: " + formatMoney(parsed.amount)
                }
            }
            SpeechAmountParseResult.Ambiguous -> {
                voiceFeedback = null
                voiceError = "سمعت أكثر من مبلغ محتمل. أعد نطق مبلغ واحد فقط."
            }
            SpeechAmountParseResult.NotFound -> {
                voiceFeedback = null
                voiceError = "لم أتمكن من تحديد المبلغ. أعد المحاولة أو أدخل المبلغ يدويًا."
            }
        }
        voiceListening = false
        voicePreparing = false
        voiceProcessing = false
        speechRecognizer.cancel()
    }

    fun beginVoiceCapture() {
        if (voiceListening || isSaving) return
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        voiceTranscript = ""
        voiceFeedback = null
        voiceError = null
        voiceListening = true
        voicePreparing = true
        voiceProcessing = false

        val started = speechRecognizer.start(
            DebtSpeechRecognizer.Callbacks(
                onPreparing = {
                    voicePreparing = true
                    voiceProcessing = false
                },
                onReady = { voicePreparing = false },
                onEndOfSpeech = { voiceProcessing = true },
                onPartial = { voiceTranscript = it },
                onFinal = { alternatives ->
                    applyVoiceResult(
                        ArabicDebtAmountParser.parseAlternatives(alternatives),
                        alternatives.firstOrNull()
                    )
                },
                onError = { speechError ->
                    voiceListening = false
                    voicePreparing = false
                    voiceProcessing = false
                    voiceFeedback = null
                    voiceError = when (speechError) {
                        DebtSpeechError.PERMISSION -> "يحتاج التسجيل الصوتي إلى إذن استخدام الميكروفون."
                        DebtSpeechError.BUSY -> "الميكروفون مشغول حاليًا. حاول مرة أخرى."
                        DebtSpeechError.UNAVAILABLE -> "خدمة التعرف على الكلام غير متاحة. فعّل خدمة الإدخال الصوتي على الهاتف ثم أعد المحاولة."
                        DebtSpeechError.START_FAILED -> "لم يبدأ الميكروفون بالاستماع. تحقق من خدمة الإدخال الصوتي وإذن الميكروفون ثم أعد المحاولة."
                        DebtSpeechError.LANGUAGE -> "خدمة التعرف على الكلام لا تدعم العربية حاليًا. فعّل العربية في إعدادات الإدخال الصوتي."
                        DebtSpeechError.NETWORK -> "تعذر الاتصال بخدمة التعرف على الكلام. تحقق من الإنترنت أو توفر العربية دون اتصال."
                        DebtSpeechError.AUDIO -> "تعذر التقاط الصوت من الميكروفون. أغلق أي تطبيق يستخدمه ثم أعد المحاولة."
                        DebtSpeechError.SERVER -> "خدمة التعرف على الكلام لم تستجب. أعد المحاولة."
                        DebtSpeechError.TIMEOUT -> "انتهت مهلة الاستماع دون نتيجة. أعد المحاولة وانطق مبلغًا واحدًا بوضوح."
                        DebtSpeechError.NO_MATCH -> "لم أتمكن من تحديد المبلغ. أعد المحاولة أو أدخل المبلغ يدويًا."
                        DebtSpeechError.OTHER -> "تعذر تشغيل التعرف على الكلام. أعد المحاولة أو أدخل المبلغ يدويًا."
                    }
                }
            )
        )
        if (!started) voiceListening = false
    }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginVoiceCapture()
        else {
            voiceListening = false
            voiceError = "يحتاج التسجيل الصوتي إلى إذن استخدام الميكروفون."
        }
    }

    fun onMicrophoneClick() {
        if (voiceListening) {
            speechRecognizer.cancel()
            voiceListening = false
            voicePreparing = false
            voiceProcessing = false
            voiceError = null
            return
        }
        if (ContextCompat.checkSelfPermission(context, MICROPHONE_PERMISSION_V29) == PackageManager.PERMISSION_GRANTED) {
            beginVoiceCapture()
        } else {
            microphonePermissionLauncher.launch(MICROPHONE_PERMISSION_V29)
        }
    }

    DisposableEffect(speechRecognizer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                speechRecognizer.cancel()
                voiceListening = false
                voicePreparing = false
                voiceProcessing = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            speechRecognizer.destroy()
        }
    }

    suspend fun persistDraft(draft: DebtDraftV29, allowRecentDuplicate: Boolean) {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                vm.addDebt(
                    customerId = customerId,
                    amount = draft.amount,
                    details = draft.details,
                    allowRecentDuplicate = allowRecentDuplicate
                )
            }
        }
        isSaving = false
        result.onSuccess { outcome ->
            when (outcome) {
                is DebtCreateResult.Created -> {
                    savedAmount = draft.amount
                    pendingDuplicate = null
                    pendingDuplicateDraft = null
                    financialFeedback.onSaved(
                        FinancialOperationReceipt(
                            kind = FinancialOperationKind.DEBT,
                            customerId = customer.id,
                            customerName = customer.name,
                            amount = draft.amount,
                            balanceAfter = draft.balanceBefore + draft.amount
                        )
                    )
                }
                is DebtCreateResult.DuplicateDetected -> {
                    pendingDuplicate = outcome
                    pendingDuplicateDraft = draft
                }
            }
        }.onFailure {
            errorText = com.radwan.raadpharmacy.data.ledgerSaveError(it, "تعذر حفظ الدين. حاول مرة أخرى.")
        }
    }

    fun submit() {
        if (isSaving) return
        if (amount <= 0L) {
            errorText = "أدخل مبلغًا صحيحًا."
            return
        }
        val draft = DebtDraftV29(
            amount = amount,
            details = detailsText.trim(),
            balanceBefore = previousBalance
        )
        focusManager.clearFocus()
        isSaving = true
        errorText = null
        scope.launch {
            val anomaly = runCatching {
                withContext(Dispatchers.IO) {
                    vm.debtAnomalyWarning(customerId, draft.amount)
                }
            }.getOrNull()
            if (anomaly != null) {
                isSaving = false
                pendingAnomaly = anomaly
                pendingAnomalyDraft = draft
                return@launch
            }
            persistDraft(draft, allowRecentDuplicate = false)
        }
    }

    pendingAnomaly?.let { warning ->
        V29DebtAnomalyDialog(
            customerName = customer.name,
            warning = warning,
            onReview = {
                pendingAnomaly = null
                pendingAnomalyDraft = null
            },
            onConfirm = {
                val draft = pendingAnomalyDraft
                pendingAnomaly = null
                pendingAnomalyDraft = null
                if (draft != null && !isSaving) {
                    isSaving = true
                    scope.launch { persistDraft(draft, allowRecentDuplicate = false) }
                }
            }
        )
    }

    pendingDuplicate?.let { duplicate ->
        V29DuplicateDebtDialog(
            customerName = customer.name,
            amount = pendingDuplicateDraft?.amount ?: duplicate.previousEntry.amount,
            secondsAgo = duplicate.secondsAgo,
            onCancel = {
                pendingDuplicate = null
                pendingDuplicateDraft = null
            },
            onForceSave = {
                val draft = pendingDuplicateDraft
                pendingDuplicate = null
                pendingDuplicateDraft = null
                if (draft != null && !isSaving) {
                    isSaving = true
                    scope.launch { persistDraft(draft, allowRecentDuplicate = true) }
                }
            }
        )
    }

    savedAmount?.let { saved ->
        V12SuccessDialog(
            title = "تم تسجيل الدين",
            message = "أضيف " + formatMoney(saved) + " إلى حساب " + customer.name,
            onDone = {
                financialFeedback.onConfirmed()
                onBack()
            }
        )
    }

    Scaffold(
        topBar = {
            ScreenTopBar(if (isSaving) "جاري الحفظ..." else "إضافة دين", if (isSaving) null else onBack)
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                Button(
                    onClick = ::submit,
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth().imePadding().padding(12.dp, 10.dp, 12.dp, 12.dp).height(58.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Text("جاري تسجيل الدين...", modifier = Modifier.padding(horizontal = 8.dp))
                    } else {
                        Text("تسجيل الدين", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    ) { padding ->
        val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).padding(14.dp, 6.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (maxHeight < 430.dp) 6.dp else 10.dp)) {
                if (!keyboardVisible) CompactFinanceHeader(customer.id, customer.name, previousBalance)
                V12AmountInput(value = amountText, onValueChange = {
                    amountText = it.filter(Char::isDigit).take(12)
                    errorText = null
                    voiceFeedback = null
                }, label = "المبلغ", helper = errorText ?: "أدخل المبلغ أو استخدم الميكروفون.",
                    isError = errorText != null, enabled = !isSaving && !voiceListening,
                    onDone = ::submit, trailingIcon = {
                        V29DebtMicrophoneButton(voiceListening, !isSaving, ::onMicrophoneClick)
                    })
                if (voiceListening || voiceFeedback != null || voiceError != null) {
                    Text(voiceError ?: voiceFeedback ?: if (voicePreparing) "جارٍ تجهيز الميكروفون…"
                        else if (voiceProcessing) "جارٍ تحليل المبلغ…" else "أستمع الآن… " + voiceTranscript,
                        style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        color = if (voiceError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                if (!keyboardVisible) FinancialQuickAmounts(!isSaving && !voiceListening) { addition ->
                    val updated = accumulatedAmount(amountText, addition)
                    if (updated != null) { amountText = updated; errorText = null; voiceFeedback = null }
                    else errorText = "المبلغ أكبر من الحد المسموح."
                }
                OutlinedTextField(value = detailsText, onValueChange = { detailsText = it.take(160) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("المشتريات / الأدوية (اختياري)") },
                    placeholder = { Text("مثال: دواء ضغط + فيتامينات") }, enabled = !isSaving,
                    singleLine = true, shape = MaterialTheme.shapes.large,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }))
                if (!keyboardVisible) CompactBalanceSummary(previousBalance, amount, previousBalance + amount, false)
            }
        }
    }
}

@Composable
fun AddPaymentScreenV12(
    vm: PharmacyLedgerViewModel,
    customerId: String,
    onBack: () -> Unit
) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val customer = customers.firstOrNull { it.id == customerId }
    if (customer == null) {
        V12MissingCustomer(onBack)
        return
    }

    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val financialFeedback = rememberFinancialFeedbackHandler(vm)
    val currentBalance = remember(customer, entries) { com.radwan.raadpharmacy.data.customerBalance(customer, entries) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var isSaving by rememberSaveable { mutableStateOf(false) }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }
    var savedAmount by rememberSaveable { mutableStateOf<Long?>(null) }
    var savedWasFull by rememberSaveable { mutableStateOf(false) }
    var pendingFull by remember { mutableStateOf<Pair<Long, Boolean>?>(null) }

    val amount = amountText.toLongOrNull() ?: 0L
    val tooHigh = amount > currentBalance && amount > 0
    val remaining = (currentBalance - amount).coerceAtLeast(0L)
    val canSubmit = amount > 0L && !tooHigh && !isSaving && currentBalance > 0L

    fun submit() {
        if (!canSubmit) {
            if (tooHigh) errorText = "المبلغ أكبر من الدين الحالي."
            return
        }

        focusManager.clearFocus()
        isSaving = true
        errorText = null

        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    vm.addPayment(customerId, amount)
                }
            }

            isSaving = false
            if (result.getOrDefault(false)) {
                savedWasFull = amount == currentBalance
                savedAmount = amount
                financialFeedback.onSaved(
                    FinancialOperationReceipt(
                        kind = if (amount == currentBalance) {
                            FinancialOperationKind.FULL_SETTLEMENT
                        } else {
                            FinancialOperationKind.PAYMENT
                        },
                        customerId = customer.id,
                        customerName = customer.name,
                        amount = amount,
                        balanceAfter = remaining
                    )
                )
            } else {
                val failure = result.exceptionOrNull()
                errorText = if (failure != null) com.radwan.raadpharmacy.data.ledgerSaveError(failure,
                    "تعذر تسجيل التحصيل. تحقق من الرصيد وحاول مرة أخرى.")
                else "تعذر تسجيل التحصيل. تحقق من الرصيد وحاول مرة أخرى."
            }
        }
    }

    fun requestSubmit() {
        if (canSubmit && amount == currentBalance) pendingFull = currentBalance to true
        else submit()
    }

    pendingFull?.let { (balanceAtRequest, saveNow) ->
        AlertDialog(onDismissRequest = { pendingFull = null },
            title = { Text(if (saveNow) "تأكيد تسديد الحساب بالكامل" else "اختيار تسديد المبلغ كاملًا") },
            text = { Text("سيتم تسديد " + formatMoney(balanceAtRequest) + " لحساب " + customer.name +
                if (saveNow) " وإغلاق الدين الحالي. هل تؤكد هذه العملية؟" else " عند تأكيد الحفظ لاحقًا. هل تريد تعبئة هذا المبلغ؟") },
            confirmButton = { TextButton(onClick = {
                pendingFull = null
                if (vm.balance(customer) != balanceAtRequest || (saveNow && amount != balanceAtRequest)) {
                    errorText = "تغير المبلغ أو الرصيد. راجع الحساب ثم أكد من جديد."
                } else if (saveNow) submit()
                else { amountText = balanceAtRequest.toString(); errorText = null }
            }) { Text(if (saveNow) "تأكيد التسديد" else "تعبئة المبلغ") } },
            dismissButton = { TextButton(onClick = { pendingFull = null }) { Text("إلغاء") } })
    }

    savedAmount?.let { saved ->
        V12SuccessDialog(
            title = if (savedWasFull) "تم تسديد الحساب" else "تم تسجيل التحصيل",
            message = if (savedWasFull) {
                "أصبح رصيد " + customer.name + " صفرًا."
            } else {
                "تم تحصيل " + formatMoney(saved) + " من " + customer.name
            },
            onDone = {
                financialFeedback.onConfirmed()
                onBack()
            }
        )
    }

    Scaffold(
        topBar = {
            ScreenTopBar(
                if (isSaving) "جاري الحفظ..." else "تسجيل تحصيل",
                if (isSaving) null else onBack
            )
        },
        bottomBar = {
            if (currentBalance > 0L) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Button(
                        onClick = ::requestSubmit,
                        enabled = canSubmit,
                        modifier = Modifier.fillMaxWidth().imePadding().padding(12.dp, 10.dp, 12.dp, 12.dp).height(58.dp),
                        shape = MaterialTheme.shapes.large
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Text("جاري تسجيل التحصيل...", modifier = Modifier.padding(horizontal = 8.dp))
                        } else {
                            Text(
                                if (remaining == 0L && amount > 0L) "تحصيل وتسديد كامل"
                                else "تأكيد التحصيل",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).padding(14.dp, 6.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (maxHeight < 430.dp) 6.dp else 10.dp)) {
                if (!keyboardVisible) CompactFinanceHeader(customer.id, customer.name, currentBalance)
                if (currentBalance == 0L) {
                    Text("الحساب مسدد بالكامل. لا يوجد مبلغ مطلوب من هذا الزبون.",
                        style = MaterialTheme.typography.titleMedium, color = PaidGreen)
                } else {
                    V12AmountInput(value = amountText, onValueChange = {
                        amountText = it.filter(Char::isDigit).take(12); errorText = null
                    }, label = "المبلغ المستلم", helper = when {
                        tooHigh -> "المبلغ يتجاوز الدين الحالي: " + formatMoney(currentBalance)
                        errorText != null -> errorText.orEmpty()
                        else -> "أدخل المبلغ المستلم من الزبون."
                    }, isError = tooHigh || errorText != null, enabled = !isSaving, onDone = ::requestSubmit)
                    if (!keyboardVisible) FinancialQuickAmounts(!isSaving) { addition ->
                        val updated = accumulatedAmount(amountText, addition)
                        if (updated != null) { amountText = updated; errorText = null }
                        else errorText = "المبلغ أكبر من الحد المسموح."
                    }
                    OutlinedButton(onClick = { focusManager.clearFocus(); pendingFull = currentBalance to false },
                        enabled = !isSaving, modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = MaterialTheme.shapes.large) {
                        Icon(Icons.Rounded.Payments, null, Modifier.size(18.dp))
                        Text("تسديد كامل المبلغ", Modifier.padding(horizontal = 7.dp))
                    }
                    if (!keyboardVisible) CompactBalanceSummary(currentBalance, amount, remaining, true)
                }
            }
        }
    }
}

@Composable
private fun V12FinanceHero(
    customerId: String,
    customerName: String,
    label: String,
    amount: Long,
    positive: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (positive) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.errorContainer
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                CustomerAvatar(
                    customerId = customerId,
                    size = 48.dp
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        customerName,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                formatMoney(amount),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (positive) PaidGreen else DebtRed
            )
        }
    }
}

@Composable
private fun V12AmountInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    helper: String,
    isError: Boolean,
    enabled: Boolean,
    onDone: () -> Unit,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        suffix = { Text("د.ع") },
        trailingIcon = trailingIcon,
        enabled = enabled,
        singleLine = true,
        isError = isError,
        supportingText = {
            Text(
                helper,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Start),
        shape = MaterialTheme.shapes.large
    )
}

@Composable
private fun V12QuickAmounts(
    values: List<Long>,
    selected: Long?,
    fullAmount: Long? = null,
    enabled: Boolean,
    onSelect: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            "اختيار سريع",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            values.forEach { value ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    enabled = enabled,
                    modifier = Modifier.height(48.dp),
                    label = {
                        Text(
                            if (fullAmount != null && value == fullAmount) {
                                "كامل • " + formatMoney(value)
                            } else {
                                formatMoney(value)
                            }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun V12Equation(
    firstLabel: String,
    first: Long,
    operator: String,
    secondLabel: String,
    second: Long,
    resultLabel: String,
    result: Long,
    positive: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            V12EquationLine(firstLabel, formatMoney(first))
            V12EquationLine(operator + " " + secondLabel, formatMoney(second))
            SoftDivider()
            V12EquationLine(
                resultLabel,
                formatMoney(result),
                true,
                if (positive) PaidGreen else DebtRed
            )
        }
    }
}

@Composable
private fun V12EquationLine(
    label: String,
    value: String,
    bold: Boolean = false,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            color = color,
            textAlign = TextAlign.End
        )
    }
}

@Composable
internal fun V12SuccessDialog(
    title: String,
    message: String,
    onDone: () -> Unit
) {
    var confirmed by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = {}) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                V28AnimatedSuccessMark()

                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Text(
                    "تم حفظ العملية بنجاح",
                    style = MaterialTheme.typography.labelLarge,
                    color = PaidGreen,
                    textAlign = TextAlign.Center
                )

                Button(
                    onClick = {
                        if (!confirmed) {
                            confirmed = true
                            onDone()
                        }
                    },
                    enabled = !confirmed,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        "موافق",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun V28AnimatedSuccessMark() {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1_000)
        )
    }

    Box(
        modifier = Modifier.size(96.dp),
        contentAlignment = Alignment.Center
    ) {
        val green = PaidGreen
        val track = MaterialTheme.colorScheme.primaryContainer

        Canvas(modifier = Modifier.size(88.dp)) {
            drawCircle(
                color = track,
                radius = size.minDimension / 2f
            )
            drawArc(
                color = green,
                startAngle = -90f,
                sweepAngle = 360f * progress.value,
                useCenter = false,
                style = Stroke(
                    width = 7.dp.toPx(),
                    cap = StrokeCap.Round
                )
            )
        }

        if (progress.value >= 0.98f) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = PaidGreen,
                modifier = Modifier.size(46.dp)
            )
        }
    }
}

private data class FinancialFeedbackController(
    val onSaved: (FinancialOperationReceipt) -> Unit,
    val onConfirmed: () -> Unit
)

@Composable
private fun rememberFinancialFeedbackHandler(
    vm: PharmacyLedgerViewModel
): FinancialFeedbackController {
    val context = LocalContext.current
    var pendingNotification by remember {
        mutableStateOf<FinancialOperationReceipt?>(null)
    }
    var notificationPermissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = granted
    }

    return FinancialFeedbackController(
        onSaved = { receipt ->
            pendingNotification = receipt
            vm.playFinancialSuccessSound()

            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !notificationPermissionGranted
            ) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onConfirmed = {
            val receipt = pendingNotification
            pendingNotification = null
            if (
                receipt != null &&
                notificationPermissionGranted &&
                FinancialOperationFeedback.canPostNotifications(context)
            ) {
                vm.scheduleFinancialOperationNotification(receipt)
            }
        }
    )
}

@Composable
private fun V12MissingCustomer(onBack: () -> Unit) {
    Scaffold(topBar = { ScreenTopBar("الزبون", onBack) }) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Text("تعذر العثور على الزبون.")
        }
    }
}
