package com.radwan.raadpharmacy.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.cloud.*
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CentralCloudBackupScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val session by SupabaseProvider.client.auth.sessionStatus.collectAsState()
    var status by remember { mutableStateOf(CentralBackupStatus()) }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageError by remember { mutableStateOf(false) }
    var requestJob by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    fun refresh(manual: Boolean = false) {
        if (busy) return
        busy = true; message = null
        val epoch = generation
        requestJob = scope.launch {
            try {
                if (manual) CentralBackupClient.requestBackup()
                val loaded = CentralBackupClient.load()
                if (epoch == generation) {
                    status = loaded; messageError = false
                    if (manual) message = "تم إرسال الطلب. حدّث الحالة لاحقًا لمعرفة النتيجة."
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (epoch == generation) {
                    messageError = true
                    message = if (error is IllegalStateException) error.message else "تعذر الاتصال. تحقق من الإنترنت ثم حدّث الحالة."
                }
            } finally { if (epoch == generation) busy = false }
        }
    }
    LaunchedEffect(session) {
        generation++; requestJob?.cancel()
        status = CentralBackupStatus(); message = null; busy = true
        try { status = CentralBackupClient.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            messageError = true
            message = if (error is IllegalStateException) error.message else "تعذر تحميل الحالة. تحقق من الإنترنت ثم حاول مجددًا."
        } finally { busy = false }
    }
    CentralCloudBackupContent(status, busy, message, onBack, { refresh() }, { refresh(true) }, messageError)
}

internal fun cloudDate(value: String?): String = try {
    if (value == null) "لا توجد" else DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm", Locale.US)
        .withZone(ZoneId.of("Asia/Baghdad")).format(Instant.parse(value))
} catch (_: Exception) { "غير متاح" }
internal fun cloudSize(value: Long): String = String.format(Locale.US, "%.2f MB", value / 1024.0 / 1024.0)
private fun cloudPhase(value: String) = when (value) {
    "RUNNING" -> "جارٍ إنشاء النسخة"
    "VERIFIED" -> "تم التحقق من الملفات"
    "FAILED" -> "لم تكتمل المحاولة"
    "RETAINED_OUT" -> "انتهت مدة الاحتفاظ"
    else -> "لا توجد محاولة بعد"
}

@Composable
internal fun CentralCloudBackupContent(status: CentralBackupStatus, busy: Boolean, message: String?,
    onBack: () -> Unit, onRefresh: () -> Unit, onRequest: () -> Unit, messageError: Boolean = false) {
    var details by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Scaffold(topBar = { ScreenTopBar("النسخ السحابي", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("cloud-backup-list"), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                BackupPanel("Google Drive", icon = Icons.Rounded.Cloud) {
                    Text(when {
                        busy && !status.configured -> "جارٍ التحقق من الخدمة…"
                        messageError && !status.configured -> "تعذر التحقق من الحالة"
                        !status.configured -> "غير مفعّل"
                        status.health == "STALE" -> "تنبيه: لا توجد نسخة حديثة خلال 9 ساعات"
                        status.phase == "RUNNING" -> "جارٍ إنشاء نسخة سحابية"
                        status.lastSuccess == null -> "لا توجد نسخة سحابية مكتملة بعد"
                        !status.automatic -> "النسخ التلقائي متوقف"
                        else -> "النسخ السحابي مفعّل"
                    }, style = MaterialTheme.typography.titleMedium,
                        color = if (status.health == "STALE") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text(if (!status.configured) "تفعيل الخدمة يتم بواسطة مسؤول الصيدلية. النسخ المحلية تعمل بشكل مستقل."
                        else "نسخة من بيانات الصيدلية التي وصلت إلى الحساب المركزي، لجميع المستخدمين.")
                    if (status.configured) Text("آخر نسخة تم التحقق منها: ${cloudDate(status.lastSuccess)}", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(enabled = !busy, onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("تحديث الحالة") }
                    if (status.configured && status.canViewHistory && status.canRequest) Button(
                        enabled = !busy && status.phase != "RUNNING", onClick = onRequest, modifier = Modifier.fillMaxWidth()) { Text("طلب نسخة سحابية الآن") }
                }
            }
            message?.let { item { Text(it, color = if (messageError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) } }
            if (status.configured) {
                item {
                    BackupPanel("معلومات النسخة") {
                        Text("النسخ المحفوظة: ${String.format(Locale.US, "%d", status.retained)}")
                        Text("الحجم: ${cloudSize(status.bytes)}")
                        Text("الموعد القادم: ${if (status.automatic) cloudDate(status.nextExpected) else "النسخ التلقائي متوقف"}")
                        Text("التغييرات التي لم تصل إلى الحساب المركزي تحتاج نسخة محلية.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { details = !details }, modifier = Modifier.testTag("cloud-details-toggle")) { Text(if (details) "إخفاء التفاصيل" else "عرض التفاصيل") }
                    }
                }
                if (details) {
                    item {
                        BackupPanel("تفاصيل الخدمة") {
                            Text("آخر محاولة: ${cloudDate(status.lastAttempt)}")
                            Text("نتيجة المحاولة: ${cloudPhase(status.phase)}")
                            Text(if (status.restoreTested == null) "اختبار الاستعادة الحقيقي لم يكتمل بعد." else "آخر اختبار استعادة: ${cloudDate(status.restoreTested)}")
                            Text("الاستعادة السحابية يتولاها المسؤول. الأوقات بتوقيت العراق وقد يتأخر الموعد المتوقع.", style = MaterialTheme.typography.bodySmall)
                            if (status.quotaLimit > 0) {
                                Text("مساحة التخزين: ${cloudSize(status.quotaUsed)} / ${cloudSize(status.quotaLimit)}")
                                if (status.quotaUsed.toDouble() / status.quotaLimit >= 0.8) Text("المساحة تقترب من الامتلاء", color = MaterialTheme.colorScheme.error)
                            }
                            if (status.canViewHistory) {
                                status.owner?.let { Text("حساب التخزين: $it") }
                                status.error?.let { Text("رمز الخطأ: $it", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    if (status.canViewHistory) {
                        item { Text("سجل النسخ", style = MaterialTheme.typography.titleMedium) }
                        if (status.history.isEmpty()) item { Text("لا توجد محاولات مسجلة بعد.") }
                        items(status.history.size) { index ->
                            val run = status.history[index]
                            BackupPanel(cloudDate(run.date)) {
                                Text(cloudPhase(run.state)); Text(cloudSize(run.bytes))
                                run.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
}
