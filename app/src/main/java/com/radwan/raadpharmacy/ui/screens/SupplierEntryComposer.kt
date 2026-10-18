package com.radwan.raadpharmacy.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.data.MonthlyReports
import com.radwan.raadpharmacy.shopping.MedicineShoppingStore
import com.radwan.raadpharmacy.suppliers.SupplierEntry
import com.radwan.raadpharmacy.util.formatMoney
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SupplierEntryComposer(store: MedicineShoppingStore,names: List<String>,onDismiss: ()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var id by rememberSaveable {mutableStateOf(UUID.randomUUID().toString())}
    var name by rememberSaveable {mutableStateOf("")};var kind by rememberSaveable {mutableStateOf("DEBT")}
    var raw by rememberSaveable {mutableStateOf("")};var note by rememberSaveable {mutableStateOf("")}
    var day by rememberSaveable {mutableStateOf(LocalDate.now(MonthlyReports.zone).toString())}
    var confirm by rememberSaveable {mutableStateOf(false)};var busy by remember {mutableStateOf(false)}
    var error by rememberSaveable {mutableStateOf<String?>(null)}
    val amount=raw.map {Character.digit(it,10).let {d->if(d>=0) ('0'.code+d).toChar() else it}}.joinToString("").toLongOrNull()
    val valid=name.isNotBlank()&&amount!=null&&amount in 1..1_000_000_000_000L
    if(confirm) AlertDialog(onDismissRequest={if(!busy)confirm=false},title={Text("تأكيد حركة المذخر")},text={
        Text("$name\n${if(kind=="DEBT") "دين على الصيدلية" else "تسديد للمذخر"}: ${formatMoney(amount?:0)}\nالتاريخ: $day\nحركة مستقلة لا تغيّر ديون الزبائن.")
    },confirmButton={TextButton(enabled=!busy&&valid,onClick={busy=true;scope.launch {
        try {store.addSupplier(SupplierEntry(id,name.trim(),kind,amount!!,day,note.trim()));confirm=false;onDismiss()}
        catch(cancelled: CancellationException) {throw cancelled}
        catch(e: Exception) {error=e.message?:"تعذر حفظ الحركة.";confirm=false} finally {busy=false}
    }}) {Text(if(busy) "جارٍ الحفظ…" else "تأكيد الحفظ")}},dismissButton={TextButton(enabled=!busy,onClick={confirm=false}) {Text("مراجعة")}})
    ModalBottomSheet(onDismissRequest={if(!busy)onDismiss()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("حركة مذخر",style=MaterialTheme.typography.titleLarge)
            Text("سجّل الدين أو التسديد الفعلي وتاريخه. لإدخال دين سابق، استخدم تاريخ بدايته وملاحظة رصيد سابق. التصحيح يكون بحركة مقابلة مع ملاحظة توضح السبب.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(name,{if(it.length<=160)name=it},label={Text("اسم المذخر")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            if(names.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                names.forEach {value -> SuggestionChip(onClick={name=value},label={Text(value)})}
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=kind=="DEBT",onClick={kind="DEBT"},label={Text("دين على الصيدلية")})
                FilterChip(selected=kind=="PAYMENT",onClick={kind="PAYMENT"},label={Text("تسديد للمذخر")})
            }
            OutlinedTextField(raw,{if(it.length<=13 && it.all {c->Character.digit(c,10)>=0})raw=it},label={Text("المبلغ بالدينار العراقي")},singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
            OutlinedButton(onClick={val date=LocalDate.parse(day);DatePickerDialog(context,{_,y,m,d->day=LocalDate.of(y,m+1,d).toString()},date.year,date.monthValue-1,date.dayOfMonth).apply {
                datePicker.minDate=LocalDate.of(2000,1,1).atStartOfDay(MonthlyReports.zone).toInstant().toEpochMilli()
                datePicker.maxDate=LocalDate.of(2100,12,31).atStartOfDay(MonthlyReports.zone).toInstant().toEpochMilli()
            }.show()},modifier=Modifier.fillMaxWidth()) {Text("تاريخ الحركة: $day")}
            OutlinedTextField(note,{if(it.length<=1000)note=it},label={Text("ملاحظة اختيارية / رقم الوصل")},modifier=Modifier.fillMaxWidth(),maxLines=3)
            if(error!=null) Text(error.orEmpty(),color=MaterialTheme.colorScheme.error)
            Button(onClick={confirm=true},enabled=valid&&!busy,modifier=Modifier.fillMaxWidth()) {Text("مراجعة وحفظ الحركة")}
            TextButton(onClick=onDismiss,enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("إلغاء")}
        }
    }
}
