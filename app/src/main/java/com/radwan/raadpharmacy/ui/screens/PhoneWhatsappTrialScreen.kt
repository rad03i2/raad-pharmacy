package com.radwan.raadpharmacy.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.util.formatDate
import com.radwan.raadpharmacy.util.formatTime
import com.radwan.raadpharmacy.util.validatedIraqiWhatsappPhone
import com.radwan.raadpharmacy.whatsapp.PhoneWhatsappClient
import com.radwan.raadpharmacy.whatsapp.PhoneWhatsappSetup
import com.radwan.raadpharmacy.whatsapp.PhoneWhatsappStatus
import com.radwan.raadpharmacy.whatsapp.PhoneWhatsappStore
import com.radwan.raadpharmacy.whatsapp.PhoneWhatsappTestImage
import com.radwan.raadpharmacy.whatsapp.PhoneWhatsappTrial
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
internal fun PhoneWhatsappTrialScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val store = remember { PhoneWhatsappStore(context) }
    val client = remember { PhoneWhatsappClient(store::token) }
    var bridge by remember { mutableStateOf(PhoneWhatsappStatus()) }
    var senderPhone by rememberSaveable { mutableStateOf("") }
    var receiverPhone by rememberSaveable { mutableStateOf("") }
    var trial by remember { mutableStateOf<PhoneWhatsappTrial?>(null) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var askSend by remember { mutableStateOf(false) }
    var askUnlink by remember { mutableStateOf(false) }
    var verifiedAt by remember { mutableStateOf(0L) }

    suspend fun refresh() {
        bridge = client.status()
        if (senderPhone.isBlank()) senderPhone = bridge.phone.orEmpty()
        verifiedAt = store.verifiedAt(bridge.phone)
        store.lastTrialId()?.let { id ->
            try { trial = client.trial(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the last known attempt; a missing response is not failure proof. */ }
        }
    }
    fun perform(action: suspend () -> Unit) {
        if (working) return
        working = true
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message?.takeIf { it.length < 220 } ?: "تعذر الاتصال بالمساعد. شغّله في Termux ثم افحص الاتصال." }
            finally { working = false }
        }
    }
    fun copy(label: String, text: String) {
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }
    fun openTermux() {
        val intent = context.packageManager.getLaunchIntentForPackage("com.termux")
        if (intent == null) message = "ثبّت Termux أولًا، ثم انسخ أمر الإعداد والصقه داخله."
        else runCatching { context.startActivity(intent) }.onFailure { message = "تعذر فتح Termux." }
    }

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try { refresh() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { bridge = PhoneWhatsappStatus(state = "unreachable"); verifiedAt = 0L }
                delay(3_000)
            }
        }
    }

    message?.let { text ->
        AlertDialog(onDismissRequest = { message = null }, title = { Text("مساعد واتساب") },
            text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text("حسنًا") } })
    }
    if (askUnlink) {
        AlertDialog(onDismissRequest = { askUnlink = false }, title = { Text("إلغاء ربط المساعد؟") },
            text = { Text("سيُفصل المساعد عن حساب واتساب المرتبط به.") },
            confirmButton = { TextButton(onClick = {
                askUnlink = false
                perform { bridge = client.unlink(); store.clearConfirmation(); trial = null; verifiedAt = 0L }
            }) { Text("إلغاء الربط") } }, dismissButton = { TextButton(onClick = { askUnlink = false }) { Text("رجوع") } })
    }
    if (askSend) {
        val phone = validatedIraqiWhatsappPhone(receiverPhone)
        AlertDialog(onDismissRequest = { askSend = false }, title = { Text("إرسال تجربة واتساب") },
            text = { Text("ستُرسل صورة ونص تجريبيان إلى الرقم:\n+${phone.orEmpty()}\nاختر رقمًا تملكه أو رقم شخص وافق على التجربة.") },
            confirmButton = { TextButton(enabled = phone != null && !working, onClick = {
                askSend = false
                perform {
                    val recipient = requireNotNull(phone)
                    val requestId = UUID.randomUUID().toString()
                    // Persist before sending; reopening this screen polls the same attempt and cannot resend it.
                    store.recordTrial(requestId)
                    verifiedAt = 0L
                    trial = PhoneWhatsappTrial(requestId, recipient, bridge.phone, "submitting", System.currentTimeMillis())
                    val image = withContext(Dispatchers.Default) { PhoneWhatsappTestImage.create(context) }
                    trial = client.sendTrial(requestId, recipient, image)
                }
            }) { Text("إرسال التجربة") } }, dismissButton = { TextButton(onClick = { askSend = false }) { Text("إلغاء") } })
    }

    Scaffold(topBar = { ScreenTopBar("مساعد واتساب • تجربة", onBack = onBack) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text("المرحلة الأولى: الربط وإرسال صورة ونص تجريبيين. جدولة تذكيرات الزبائن تأتي بعد نجاح الاختبار على الهاتف.",
                    style = MaterialTheme.typography.bodyMedium)
                Text("المساعد مجاني وغير رسمي. قد يتوقف في الخلفية أو يُقيّد حساب واتساب.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
            item {
                TrialPanel("إعداد المساعد على الهاتف") {
                    Text("ثبّت Termux، ثم انسخ أمر الإعداد وافتحه والصق الأمر داخله. انتظر ظهور: مساعد واتساب جاهز، ثم ارجع هنا.")
                    OutlinedButton(onClick = { runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")))
                    }.onFailure { message = "تعذر فتح رابط تثبيت Termux." } }, modifier = Modifier.fillMaxWidth()) { Text("تثبيت Termux مجانًا") }
                    Button(enabled = !working, onClick = { perform {
                        val command = withContext(Dispatchers.IO) { PhoneWhatsappSetup.command(context, store.token()) }
                        copy("إعداد مساعد واتساب", command)
                        message = "نُسخ أمر الإعداد. افتح Termux والصقه وشغّله. احتفظ بهذا الأمر على هاتفك لأنه يتضمن مفتاح الاتصال الخاص بالمساعد."
                    } }, modifier = Modifier.fillMaxWidth()) { Text("نسخ أمر الإعداد") }
                    OutlinedButton(onClick = ::openTermux, modifier = Modifier.fillMaxWidth()) { Text("فتح Termux") }
                    TextButton(onClick = {
                        copy("تشغيل مساعد واتساب", PhoneWhatsappSetup.RESUME_COMMAND)
                        message = "نُسخ أمر تشغيل المساعد. استخدمه في Termux بعد إعادة تشغيل الهاتف أو إيقاف المساعد."
                    }, modifier = Modifier.fillMaxWidth()) { Text("نسخ أمر التشغيل بعد الإعداد") }
                }
            }
            item {
                TrialPanel("ربط واتساب المرسل") {
                    Text(bridgeStateText(bridge.state), style = MaterialTheme.typography.titleSmall)
                    bridge.errorCode?.let { Text(phoneWhatsappFailureText(it), style = MaterialTheme.typography.bodyMedium) }
                    OutlinedTextField(senderPhone, { senderPhone = it.take(80) }, label = { Text("رقم واتساب الذي سيرسل") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true,
                        enabled = !working && !bridge.busy && bridge.state != "connected", modifier = Modifier.fillMaxWidth())
                    Button(enabled = !working && !bridge.busy && bridge.state !in listOf("connected", "connecting", "starting", "upgrade_required") && validatedIraqiWhatsappPhone(senderPhone) != null,
                        onClick = { perform { bridge = client.connect(validatedIraqiWhatsappPhone(senderPhone)!!) } },
                        modifier = Modifier.fillMaxWidth()) { Text(if (bridge.pairingCode == null) "طلب رمز الربط" else "طلب رمز جديد") }
                    bridge.pairingCode?.let { code ->
                        Text("رمز الربط: $code", style = MaterialTheme.typography.headlineSmall)
                        Text("افتح واتساب لهذا الرقم، ثم الأجهزة المرتبطة ← ربط جهاز ← الربط برقم الهاتف، وأدخل الرمز.")
                        OutlinedButton(onClick = { copy("رمز ربط واتساب", code) }, modifier = Modifier.fillMaxWidth()) { Text("نسخ رمز الربط") }
                    }
                    TextButton(enabled = !working, onClick = { perform { refresh() } }, modifier = Modifier.fillMaxWidth()) { Text("فحص الاتصال") }
                }
            }
            item {
                TrialPanel("تجربة وصول الصورة والنص") {
                    OutlinedTextField(receiverPhone, { receiverPhone = it.take(80) }, label = { Text("رقم واتساب المستلم للتجربة") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true,
                        enabled = !working && !bridge.busy, modifier = Modifier.fillMaxWidth())
                    PhoneWhatsappSendAction(bridge.state == "connected", working || bridge.busy,
                        validatedIraqiWhatsappPhone(receiverPhone) != null) { askSend = true }
                    trial?.let { attempt ->
                        Text("رقم التجربة: +${attempt.phone}")
                        PhoneWhatsappTrialResult(attempt.state)
                        if (attempt.senderPhone == bridge.phone && bridge.state == "connected" && !bridge.busy &&
                            attempt.state !in listOf("submitting", "not_registered")) {
                            OutlinedButton(enabled = !working, onClick = { perform {
                                store.confirmReceived(attempt.senderPhone!!)
                                verifiedAt = store.verifiedAt(bridge.phone)
                            } }, modifier = Modifier.fillMaxWidth()) { Text("وصلت الصورة والنص: تأكيد نجاح التجربة") }
                        }
                    }
                    if (verifiedAt > 0L) Text("تم تأكيد وصول التجربة • ${formatDate(verifiedAt)} • ${formatTime(verifiedAt)}",
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            item {
                TrialPanel("التشغيل في الخلفية") {
                    Text("بعد نجاح الإرسال، جرّب قفل الشاشة ثم ارجع وافحص الاتصال. يسمح ضبط بطارية Termux بالتشغيل دون تقييد، لكن استمرار الخدمة يحتاج اختبارًا على هاتفك.")
                    OutlinedButton(onClick = { runCatching {
                        context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:com.termux")))
                    }.onFailure { message = "افتح إعدادات الهاتف ثم تطبيق Termux ثم البطارية." } }, modifier = Modifier.fillMaxWidth()) { Text("فتح إعدادات Termux") }
                    TextButton(enabled = !working && !bridge.busy && bridge.state != "unavailable", onClick = { perform { bridge = client.stop() } },
                        modifier = Modifier.fillMaxWidth()) { Text("إيقاف المساعد") }
                    TextButton(enabled = !working && !bridge.busy && bridge.state != "unavailable", onClick = { askUnlink = true },
                        modifier = Modifier.fillMaxWidth()) { Text("إلغاء ربط المساعد") }
                }
            }
            if (working) item { CircularProgressIndicator() }
        }
    }
}

@Composable
private fun TrialPanel(title: String, content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun PhoneWhatsappSendAction(connected: Boolean, busy: Boolean, validPhone: Boolean, onSend: () -> Unit) {
    Button(enabled = connected && !busy && validPhone, onClick = onSend, modifier = Modifier.fillMaxWidth()) {
        Text("إرسال صورة ونص تجريبيين")
    }
}

@Composable
internal fun PhoneWhatsappTrialResult(state: String) {
    Text(when (state) {
        "sent" -> "تم تأكيد إرسال التجربة من واتساب"
        "delivered" -> "تم تسليم التجربة إلى المستلم"
        "read" -> "تمت قراءة التجربة"
        "submitted" -> "سُجّل طلب التجربة؛ بانتظار تأكيد واتساب"
        "not_registered" -> "رقم التجربة غير مسجل في واتساب"
        "uncertain" -> "نتيجة المحاولة غير مؤكدة. تحقق داخل واتساب قبل إرسال تجربة جديدة."
        else -> "التجربة قيد الإرسال"
    }, style = MaterialTheme.typography.bodyMedium)
}

internal fun bridgeStateText(state: String): String = when (state) {
    "connected" -> "المساعد متصل بواتساب"
    "connecting" -> "جاري الاتصال بواتساب…"
    "starting" -> "جاري تجهيز اتصال واتساب؛ انتظر قليلًا…"
    "upgrade_required" -> "حدّث مساعد Termux: انسخ أمر الإعداد من هذا التحديث وشغّله مرة واحدة"
    "unreachable" -> "تعذر الوصول إلى Termux. افتحه وتأكد أن المساعد يعمل، ثم افحص الاتصال"
    "pairing" -> "بانتظار إدخال رمز الربط في واتساب"
    "disconnected" -> "انقطع اتصال واتساب؛ افحص الإنترنت ثم اطلب الاتصال"
    "logged_out" -> "انتهى الربط. ألغِ ربط المساعد ثم اربطه مجددًا"
    "error" -> "تعذر الربط. افحص الإنترنت ثم اطلب رمزًا جديدًا"
    "idle" -> "المساعد يعمل؛ يمكن طلب رمز الربط"
    else -> "المساعد متوقف أو لم يُجهّز بعد"
}

internal fun phoneWhatsappFailureText(code: String): String = when (code) {
    "runtime_exit", "runtime_start_failed" -> "توقف محرك اتصال واتساب؛ المساعد المحلي ما زال يعمل. اطلب الربط مجددًا دون إعادة تثبيت Termux."
    "runtime_killed" -> "أنهى الهاتف محرك اتصال واتساب. افتح إعدادات بطارية Termux واجعل تشغيله دون تقييد ثم أعد الربط."
    "runtime_incomplete" -> "ملفات محرك واتساب غير مكتملة. انسخ أمر الإعداد من هذا التحديث وشغّله مرة واحدة في Termux."
    "pairing_timeout", "request_timeout" -> "انتهت مهلة الاتصال بواتساب. افحص الإنترنت ثم اطلب رمزًا جديدًا."
    "pairing_rejected" -> "لم يقبل واتساب الربط. افتح الأجهزة المرتبطة في واتساب ثم أعد طلب الرمز."
    "session_storage" -> "تعذر حفظ جلسة واتساب. افحص مساحة الهاتف وصلاحية مجلد Termux."
    "network_error" -> "تعذر الاتصال بخوادم واتساب؛ ستجري محاولات اتصال محدودة دون إرسال رسائل."
    else -> "تعذر بدء اتصال واتساب. الخدمة المحلية تعمل؛ افحص الاتصال ثم أعد طلب الربط."
}
