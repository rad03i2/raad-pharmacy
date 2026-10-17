package com.radwan.raadpharmacy.backup

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.data.PharmacyLedgerDatabase
import com.radwan.raadpharmacy.security.AppSecurityStore
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.ui.screens.AppLockScreenV9
import com.radwan.raadpharmacy.ui.screens.backupTime
import com.radwan.raadpharmacy.ui.theme.PharmacyLedgerTheme
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

class BackupRecoveryActivity : FragmentActivity() {
    private lateinit var session: RecoverySession
    private var lockModel: PharmacyLedgerViewModel? = null
    private var launchRequested = false
    // Register unconditionally at Activity creation, outside auth/navigation composition.
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) session.cancelPicker() else session.accept(uri)
    }
    internal fun chooseFile() {
        try { session.picker(true); picker.launch(arrayOf("*/*")) }
        catch (_: Exception) { session.cancelPicker(); session.problem("تعذر فتح مزود الملفات؛ اختر مزودًا متاحًا ثم حاول مجددًا.") }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = RecoverySession.get(this)
        launchRequested = savedInstanceState == null && intent.getBooleanExtra("pick", false)
        if (AppSecurityStore(this).state().secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (AppSecurityStore(this).isPinEnabled())
            lockModel = ViewModelProvider(this)[PharmacyLedgerViewModel::class.java]
        setContent {
            val state by session.state.collectAsStateWithLifecycle()
            val controls by PharmacyLedgerDatabase.get(this).dao().observeRecoveryControls().collectAsStateWithLifecycle(initialValue = emptyList())
            val vm = lockModel
            val unlocked = if (vm == null) true else vm.isUnlocked.collectAsStateWithLifecycle().value
            LaunchedEffect(unlocked, state.busy) {
                if (unlocked && !state.busy && launchRequested && !session.pickerPending()) {
                    launchRequested = false; intent.removeExtra("pick"); chooseFile()
                }
            }
            PharmacyLedgerTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    if (!unlocked && vm != null) AppLockScreenV9(vm)
                    else RecoveryContent(state, controls.mapNotNull {
                        if (it.id.startsWith(RecoveryKeys.ITEM)) runCatching { RecoveryRecord.parse(it.value) }.getOrNull() else null
                    }, onBack = { finish() }, onChoose = ::chooseFile, onCompare = { session.reload() }, onConfirm = session::confirm,
                        onRetry = { RecoveryScheduler.enqueue(this) }, onKey = session::reload)
                }
            }
        }
        RecoveryScheduler.resume(this)
    }
    override fun onStart() { super.onStart(); lockModel?.onAppForegrounded() }
    override fun onStop() { lockModel?.onAppBackgrounded(); super.onStop() }
    companion object {
        fun open(context: Context, pick: Boolean = false) { context.startActivity(Intent(context, BackupRecoveryActivity::class.java).putExtra("pick", pick)) }
    }
}

@Composable
internal fun RecoveryContent(state: RecoveryUiState, records: List<RecoveryRecord>,
    onBack: () -> Unit, onChoose: () -> Unit, onCompare: () -> Unit, onConfirm: () -> Unit,
    onRetry: () -> Unit, onKey: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    BackHandler { if (!state.busy) onBack() }
    fun money(value: Long) = NumberFormat.getIntegerInstance(Locale.US).format(value) + " د.ع"
    Scaffold(topBar = { ScreenTopBar("استرداد البيانات ومقارنتها", onBack = { if (!state.busy) onBack() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("تبقى بيانات الهاتف الحالية. تُضاف السجلات الناقصة فقط، وتُراجع التعارضات دون استبدال تلقائي.")
            Button(enabled = !state.busy, onClick = onChoose, modifier = Modifier.fillMaxWidth()) { Text("اختيار ملف من الهاتف أو البطاقة") }
            if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.progress.ifBlank { "تجري المعالجة…" }) }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            state.plan?.let { p ->
                Text("الملف: " + state.sourceName, style = MaterialTheme.typography.bodySmall)
                Text("المقارنة من السجلات الفعلية", style = MaterialTheme.typography.titleLarge)
                funValues("عدد الزبائن", p.current.customers.toString(), p.incoming.customers.toString())
                funValues("إجمالي الديون مع الرصيد الافتتاحي", money(p.current.debts), money(p.incoming.debts))
                funValues("إجمالي التسديدات", money(p.current.payments), money(p.incoming.payments))
                funValues("الرصيد المتبقي", money(Math.subtractExact(p.current.debts, p.current.payments)),
                    money(Math.subtractExact(p.incoming.debts, p.incoming.payments)))
                funValues("عمليات الديون", p.current.debtCount.toString(), p.incoming.debtCount.toString())
                funValues("عمليات التسديد", p.current.paymentCount.toString(), p.incoming.paymentCount.toString())
                Text("سجلات ستُضاف: " + p.additions + " • موجودة ومطابقة: " + p.existing)
                Text("تعارضات: " + p.conflicts + " • سجلات تحتاج تحققًا من الخادم: " + p.checking)
                Text("سيُتحقق من معرّفات السجلات في صيدلية الحساب الحالي. علامة المزامنة القديمة داخل الملف لا تُعد إثباتًا.")
                Text("لا يُحذف سجل حالي ولا تُستبدل حركة مختلفة. التعارضات تبقى للمراجعة، ولا يُنشأ معرّف بديل لها.")
                OutlinedButton(enabled = !state.busy, onClick = onCompare) { Text("إعادة المقارنة") }
                Button(enabled = !state.busy && !state.completed, onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.completed) "اكتمل الاسترداد المحلي" else "تأكيد استرداد البيانات")
                }
                if (p.conflicts > 0) p.records.filter { it.state == "CONFLICT" }.take(50).forEach { record ->
                    RecoveryConflict(record)
                }
            }
            if (state.plan == null && !state.busy) {
                OutlinedTextField(key, onValueChange = { key = it }, label = { Text("مفتاح نسخة مشفرة من هاتف آخر — إن لزم") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { onKey(key); key = "" }) { Text("فتح الملف المحفوظ بالمفتاح") }
            }
            if (records.isNotEmpty()) {
                HorizontalDivider()
                Text("حالة السجلات المستردة المحفوظة", style = MaterialTheme.typography.titleLarge)
                Text("مطابقة ومؤكدة على الخادم: " + records.count { it.state == "VERIFIED" })
                Text("بانتظار التحقق: " + records.count { it.state == "PENDING" } +
                    " • محاولات متعثرة محفوظة: " + records.count { it.state == "PENDING" && it.reason.isNotBlank() })
                Text("تعارضات تحتاج المراجعة: " + records.count { it.state == "CONFLICT" })
                OutlinedButton(enabled = !state.busy, onClick = onRetry) { Text("إعادة محاولة التحقق والمزامنة") }
                records.filter { it.state == "CONFLICT" }.take(50).forEach { RecoveryConflict(it) }
                if (records.count { it.state == "CONFLICT" } > 50) Text("تُعرض أول 50 حالة؛ جميع الحالات محفوظة على الهاتف.")
            }
        }
    }
}

@Composable
private fun funValues(label: String, current: String, incoming: String) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text("الحالي: " + current)
        Text("النسخة المختارة: " + incoming)
    } }
}

@Composable
private fun RecoveryConflict(record: RecoveryRecord) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        Text(record.kind + " • " + record.id, style = MaterialTheme.typography.labelSmall)
        Text(record.reason, color = MaterialTheme.colorScheme.error)
        val data = runCatching { JSONObject(record.payload) }.getOrNull()
        val row = data?.optJSONArray(if (record.kind == "CUSTOMER") "customers" else "entries")?.optJSONObject(0)
        row?.let { Text(if (record.kind == "CUSTOMER") it.optString("name") + " • رصيد افتتاحي: " + it.optLong("openingDebt")
            else it.optString("type") + " • مبلغ النسخة: " + it.optLong("amount") + " • تاريخ: " + backupTime(it.optLong("createdAt"))) }
    } }
}
