package com.radwan.raadpharmacy.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.cloud.*
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
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
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun refresh(manual: Boolean = false) {
        scope.launch {
            busy = true; message = null
            try {
                if (manual) CentralBackupClient.requestBackup()
                status = CentralBackupClient.load()
                if (manual) message = "تم إرسال الطلب إلى الخادم. حدّث الحالة لاحقًا لمتابعة النتيجة."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = if (error is IllegalStateException) error.message else "تعذر الوصول إلى خدمة النسخ. تحقق من اتصال الإنترنت." }
            finally { busy = false }
        }
    }
    LaunchedEffect(session) {
        status = CentralBackupStatus(); message = null
        busy = true
        try { status = CentralBackupClient.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { message = if (error is IllegalStateException) error.message else "تعذر تحميل حالة النسخ المركزي." }
        finally { busy = false }
    }
    CentralCloudBackupContent(status,busy,message,onBack,{ refresh() },{ refresh(true) })
}

internal fun cloudDate(value: String?): String = try {
    if (value == null) "لا توجد" else DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm",Locale.US)
        .withZone(ZoneId.of("Asia/Baghdad")).format(Instant.parse(value))
} catch (_: Exception) { "غير متاح" }
internal fun cloudSize(value: Long): String = String.format(Locale.US,"%.2f MB",value/1024.0/1024.0)
private fun cloudPhase(value: String) = when(value) {
    "RUNNING" -> "جارٍ إنشاء النسخة"
    "VERIFIED" -> "الملفات المشفرة متحقق منها"
    "FAILED" -> "فشلت المحاولة"
    "RETAINED_OUT" -> "انتهت مدة الاحتفاظ"
    else -> "غير مهيأ"
}

@Composable
internal fun CentralCloudBackupContent(status: CentralBackupStatus,busy: Boolean,message: String?,
    onBack: () -> Unit,onRefresh: () -> Unit,onRequest: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = { ScreenTopBar("النسخ السحابي",onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("cloud-backup-list"),
            contentPadding = PaddingValues(16.dp),verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                CloudCard("النسخ الاحتياطي السحابي المركزي") {
                    Text("Google Drive",style = MaterialTheme.typography.titleMedium,color = MaterialTheme.colorScheme.primary)
                    Text(when {
                        !status.configured -> "الخدمة غير مهيأة بعد"
                        status.health == "STALE" -> "تنبيه: لا توجد نسخة حديثة خلال 9 ساعات"
                        status.lastSuccess == null -> "لم تُسجل نسخة متحقق منها بعد"
                        !status.automatic -> "الجدولة التلقائية غير مفعلة"
                        else -> "آخر ملفات النسخ متحقق منها"
                    },color = if (status.health == "STALE") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text("نسخة واحدة من بيانات الصيدلية المركزية، لجميع المستخدمين ودون الاعتماد على أي هاتف.")
                    OutlinedButton(enabled = !busy,onClick = onRefresh) { Text("تحديث الحالة") }
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            message?.let { item { Text(it,color = MaterialTheme.colorScheme.error) } }
            item {
                CloudCard("حالة النسخ") {
                    Text("آخر نسخة متحقق منها: ${cloudDate(status.lastSuccess)}")
                    Text("آخر محاولة: ${cloudDate(status.lastAttempt)}")
                    Text("نتيجة المحاولة: ${cloudPhase(status.phase)}")
                    Text("الموعد المتوقع: ${if (status.configured && status.automatic) cloudDate(status.nextExpected) else "غير مفعل"}")
                    Text("النسخ المحفوظة: ${String.format(Locale.US,"%d",status.retained)}")
                    Text("حجم الملفات النشطة: ${cloudSize(status.bytes)}")
                    Text("الأوقات بتوقيت العراق. قد تؤخر خدمة الجدولة التنفيذ.",style = MaterialTheme.typography.bodySmall)
                }
            }
            if (status.quotaLimit > 0) item {
                CloudCard("مساحة حساب التخزين") {
                    Text("${cloudSize(status.quotaUsed)} / ${cloudSize(status.quotaLimit)}")
                    if (status.quotaUsed.toDouble()/status.quotaLimit >= 0.8) Text("تنبيه: الحساب يقترب من الامتلاء",color = MaterialTheme.colorScheme.error)
                }
            }
            item {
                CloudCard("التحقق والاستعادة") {
                    Text(if (status.restoreTested == null) "لم يُوثق اختبار استعادة حقيقي لهذه الخدمة بعد." else "اختبار الاستعادة الموثق: ${cloudDate(status.restoreTested)}")
                    Text("تحتوي النسخ على البيانات التي وصلت إلى Supabase والصور السحابية. التغييرات غير المتزامنة على الهواتف تحتاج النسخ المحلية.",style = MaterialTheme.typography.bodySmall)
                    Text("الاستعادة إجراء إداري في بيئة منفصلة باستخدام مفتاح الاسترداد المحفوظ لدى المسؤول.",style = MaterialTheme.typography.bodySmall)
                }
            }
            if (status.canViewHistory) {
                item { CloudCard("إدارة النسخ") {
                    status.owner?.let { Text("الحساب المركزي: $it") }
                    status.error?.let { Text("رمز الخطأ: $it",color = MaterialTheme.colorScheme.error) }
                    if (status.canRequest) Button(enabled = !busy,onClick = onRequest) { Text("طلب نسخة سحابية الآن") }
                    Text("سجل النسخ",style = MaterialTheme.typography.titleMedium)
                    if (status.history.isEmpty()) Text("لا توجد محاولات مسجلة بعد.")
                } }
                items(status.history.size) { index ->
                    val run = status.history[index]
                    CloudCard(cloudDate(run.date)) {
                        Text(cloudPhase(run.state)); Text(cloudSize(run.bytes))
                        run.error?.let { Text(it,color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudCard(title: String,content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(),shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(18.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title,style = MaterialTheme.typography.titleLarge); content()
        }
    }
}
