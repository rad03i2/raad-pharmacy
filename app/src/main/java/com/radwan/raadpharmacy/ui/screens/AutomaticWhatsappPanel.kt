package com.radwan.raadpharmacy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.cloud.CloudSyncScheduler
import com.radwan.raadpharmacy.cloud.SupabaseProvider
import com.radwan.raadpharmacy.data.DebtFollowupEngine
import com.radwan.raadpharmacy.data.PharmacyLedgerDatabase
import com.radwan.raadpharmacy.data.toModel
import com.radwan.raadpharmacy.util.validatedIraqiWhatsappPhone
import com.radwan.raadpharmacy.whatsapp.AutomaticWhatsappPlanner
import com.radwan.raadpharmacy.whatsapp.AutomaticWhatsappScheduler
import com.radwan.raadpharmacy.whatsapp.AutomaticWhatsappStore
import com.radwan.raadpharmacy.whatsapp.PhoneWhatsappStatus
import com.radwan.raadpharmacy.whatsapp.PhoneWhatsappStore
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** Local phone only. Never silently opt in a customer or a sender account. */
@Composable
internal fun AutomaticWhatsappPanel(status: PhoneWhatsappStatus) {
    val context = LocalContext.current
    val store = remember { AutomaticWhatsappStore(context) }
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<DebtFollowupEngine.Account>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var revision by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            val dao = PharmacyLedgerDatabase.get(context).dao()
            accounts = AutomaticWhatsappPlanner.due(DebtFollowupEngine.build(
                dao.getCustomers().map { it.toModel() }, dao.getEntries().map { it.toModel() }))
        } catch (_: Exception) { notice = "تعذر عرض الحسابات. افتح الصيدلية وتحقق من البيانات." }
        loading = false
    }
    val signedTrial = status.state == "connected" &&
        PhoneWhatsappStore(context).verifiedAt(status.phone) > 0L
    val approved = accounts.count {
        val phone = it.customer.phone?.let(::validatedIraqiWhatsappPhone)
        phone != null && store.approved(it.customer.id, phone)
    }
    fun refresh() { revision++; }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("تفعيل إرسال التذكيرات اليومية؟") },
        text = { Text("سيراجع هذا الهاتف المزامنة والديون، ثم يرسل صورة ملخص ونص التذكير فقط إلى الزبائن الذين سجلت موافقتهم هنا. قد يتأخر تشغيل أندرويد في الخلفية، وقد يقيّد واتساب غير الرسمي الحساب. يقتصر الإرسال على عشر محاولات باليوم دون إعادة إرسال النتائج المجهولة.") },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                scope.launch {
                    try {
                        val auth = SupabaseProvider.client.auth
                        auth.awaitInitialization()
                        val user = auth.currentSessionOrNull()?.user?.id ?: error("سجل الدخول أولًا.")
                        val sender = status.phone ?: error("اربط واتساب أولًا.")
                        if (!signedTrial || approved == 0 || !CloudSyncScheduler.isEnabled(context))
                            error("يجب نجاح التجربة وموافقة زبون وتفعيل مزامنة الصيدلية.")
                        store.setEnabled(true, sender, user)
                        AutomaticWhatsappScheduler.apply(context)
                        notice = "فُعلت مهمة الفحص اليومي لهذا الهاتف. راجع السجل بعد التشغيل."
                        refresh()
                    } catch (e: Exception) { notice = e.message ?: "تعذر تفعيل التذكير التلقائي." }
                }
            }) { Text("نعم، تشغيل يومي") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("إلغاء") } }
    )
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("التنبيهات التلقائية • مرة يوميًا", style = MaterialTheme.typography.titleMedium)
            Text(
                "تُفحص أرصدة الزبائن بعد مزامنتها، ثم يُختار نص الرسالة الجاهزة بحسب الموعد الخاص أو 30/60 يومًا. يُرفق ملخص مصور للحساب وآخر حركاته. " +
                "لا يجري الإرسال إلا من هذا الهاتف، وللأرقام التي تؤكد موافقتها. لا تفعّلها من هاتف ثانٍ.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(if (store.enabled()) "الإرسال التلقائي: مفعّل على هذا الهاتف" else "الإرسال التلقائي: متوقف",
                color = MaterialTheme.colorScheme.primary)
            Text("نجاح تجربة ربط واتساب: ${if (signedTrial) "مؤكد" else "غير مؤكد"}")
            Text("الزبائن المستحقون: ${accounts.size} • الموافقون: $approved")
            if (loading) Text("جارٍ فحص الحسابات...")
            accounts.take(35).forEach { account ->
                val phone = account.customer.phone?.let(::validatedIraqiWhatsappPhone)
                if (phone != null) Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(account.customer.name.take(45), modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall)
                    Checkbox(
                        checked = store.approved(account.customer.id, phone) && revision >= 0,
                        onCheckedChange = { checked ->
                            store.approve(account.customer.id, phone, checked)
                            refresh()
                        }
                    )
                }
            }
            if (accounts.size > 35) Text("تُعرض أول 35 حالة فقط؛ باقي الزبائن غير معتمدين تلقائيًا.",
                style = MaterialTheme.typography.bodySmall)
            Text("حدد الزبون فقط بعد التأكد من موافقته على التذكيرات عبر واتساب. تغيير رقمه يلغي اعتماد الرقم السابق تلقائيًا.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = { confirm = true }, enabled = !store.enabled() && signedTrial && approved > 0,
                modifier = Modifier.fillMaxWidth()) { Text("تفعيل الإرسال اليومي") }
            OutlinedButton(onClick = {
                store.setEnabled(false)
                AutomaticWhatsappScheduler.apply(context)
                notice = "أُوقف الإرسال التلقائي. لا تُحذف السجلات السابقة."
                refresh()
            }, enabled = store.enabled(), modifier = Modifier.fillMaxWidth()) { Text("إيقاف الإرسال التلقائي") }
            OutlinedButton(onClick = {
                AutomaticWhatsappScheduler.checkNow(context)
                notice = "أُرسل طلب الفحص إلى نظام أندرويد. النتائج تظهر في السجل."
                refresh()
            }, enabled = store.enabled(), modifier = Modifier.fillMaxWidth()) { Text("فحص الآن") }
            OutlinedButton(onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) { Text("تحديث حالة وسجل المساعد") }
            val last = store.lastChecked()
            Text("آخر فحص: ${if (last <= 0L) "لم يبدأ" else Instant.ofEpochMilli(last).atZone(ZoneId.of("Asia/Baghdad")).toLocalDateTime()}")
            if (store.lastSummary().isNotBlank()) Text(store.lastSummary(), style = MaterialTheme.typography.bodySmall)
            store.logs().take(8).forEach { log ->
                Text("• ${log.phone.take(6)}•••• • ${log.state} • ${Instant.ofEpochMilli(log.at).atZone(ZoneId.of("Asia/Baghdad")).toLocalDate()}",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
            Text("للتشغيل بعد إعادة إقلاع الهاتف، ثبّت إضافة Termux:Boot الرسمية وافتحها مرة، ثم استثنِ Termux من قيود البطارية. استمرار العمل مع قفل الشاشة يحتاج تجربة فعلية.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
