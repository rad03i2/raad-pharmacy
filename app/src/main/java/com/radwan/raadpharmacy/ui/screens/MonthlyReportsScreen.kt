package com.radwan.raadpharmacy.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.data.LedgerEntry
import com.radwan.raadpharmacy.data.MonthlyReports
import com.radwan.raadpharmacy.shopping.MedicineShoppingStore
import com.radwan.raadpharmacy.suppliers.*
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.ui.components.rememberLedgerFlingBehavior
import com.radwan.raadpharmacy.util.formatMoney
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.*

@Composable
fun MonthlyReportsScreen(vm: PharmacyLedgerViewModel,onBack: ()->Unit = {}) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val security by vm.securityState.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val store=remember(context) {MedicineShoppingStore.get(context)}
    val shared by store.state.collectAsStateWithLifecycle()
    var adding by rememberSaveable {mutableStateOf(false)}
    DisposableEffect(store) {store.visible=true;store.start();store.request();onDispose {store.visible=false}}
    if(adding) SupplierEntryComposer(store,shared.suppliers.map {it.supplier}.distinct(),onDismiss={adding=false})
    MonthlyReportsContent(entries,security.hideAmounts,onBack=onBack,suppliers=shared.suppliers,
        onAddSupplier={adding=true},syncMessage=when {
            shared.error!=null -> "سجل المذاخر محفوظ محلياً؛ تعذرت المزامنة وستُعاد المحاولة."
            shared.suppliers.any {it.pending} -> "حركات المذاخر محفوظة • بانتظار الرفع للسحابة"
            shared.lastSyncedAt==0L -> "جارٍ التحقق من سجل المذاخر على السحابة…"
            else -> "سجل المذاخر مشترك ومحدّث"
        })
}

@Composable
internal fun MonthlyReportsContent(entries: List<LedgerEntry>,hideAmounts: Boolean,
    today: LocalDate = LocalDate.now(MonthlyReports.zone),onBack: ()->Unit = {},
    suppliers: List<SupplierEntry> = emptyList(),onAddSupplier: ()->Unit = {},syncMessage: String="") {
    val context=LocalContext.current
    val colors=MaterialTheme.colorScheme
    val followsClock=remember {today==LocalDate.now(MonthlyReports.zone)}
    var current by remember {mutableStateOf(YearMonth.from(today))}
    var selected by rememberSaveable {mutableStateOf(current.toString())}
    var custom by rememberSaveable {mutableStateOf(false)}
    var from by rememberSaveable {mutableStateOf(current.atDay(1).toString())}
    var through by rememberSaveable {mutableStateOf(today.toString())}
    var selectedSupplier by rememberSaveable {mutableStateOf<String?>(null)}
    var showMonths by rememberSaveable {mutableStateOf(false)}
    val period=remember(selected,custom,from,through) {
        if(custom) MonthlyReports.Period(LocalDate.parse(from),LocalDate.parse(through)) else MonthlyReports.month(YearMonth.parse(selected))
    }
    LaunchedEffect(Unit) {while(true) {
        val next=YearMonth.now(MonthlyReports.zone)
        if(next!=current && followsClock) {if(!custom && selected==current.toString()) selected=next.toString();current=next}
        val now=java.time.ZonedDateTime.now(MonthlyReports.zone)
        delay(java.time.Duration.between(now,now.toLocalDate().plusDays(1).atStartOfDay(MonthlyReports.zone)).toMillis().coerceAtLeast(1000))
    }}
    var calculation by remember {mutableStateOf<Pair<MonthlyReports.Period,MonthlyReports.Totals>?>(null)}
    var supplierCalculation by remember {mutableStateOf<Pair<MonthlyReports.Period,SupplierTotals>?>(null)}
    val totals=calculation?.takeIf {it.first==period}?.second
    val payable=supplierCalculation?.takeIf {it.first==period}?.second
    LaunchedEffect(entries,period) {calculation=period to withContext(Dispatchers.Default) {MonthlyReports.totals(entries,period)}}
    LaunchedEffect(suppliers,period) {supplierCalculation=period to withContext(Dispatchers.Default) {SupplierReports.totals(suppliers,period)}}
    fun amount(value: Long)=if(hideAmounts) "•••• د.ع" else formatMoney(value)
    fun pickDate(start: Boolean) {
        val date=LocalDate.parse(if(start)from else through)
        DatePickerDialog(context,{_,year,month,day ->
            val picked=LocalDate.of(year,month+1,day)
            if(start) {from=picked.toString();if(picked>LocalDate.parse(through)) through=from}
            else {through=picked.toString();if(picked<LocalDate.parse(from)) from=through};custom=true
        },date.year,date.monthValue-1,date.dayOfMonth).show()
    }
    if(showMonths) AlertDialog(onDismissRequest={showMonths=false},title={Text("اختيار الشهر")},text={
        LazyColumn(Modifier.heightIn(max=420.dp)) {items(MonthlyReports.availableMonths(current),key={it.toString()}) {month ->
            TextButton(onClick={selected=month.toString();custom=false;showMonths=false},modifier=Modifier.fillMaxWidth()) {Text(monthTitle(month,current))}
        }}
    },confirmButton={TextButton(onClick={showMonths=false}) {Text("إغلاق")}})
    selectedSupplier?.let {name ->
        val movements=remember(suppliers,period,name) {suppliers.filter {it.supplier==name && LocalDate.parse(it.day)>=period.from && LocalDate.parse(it.day)<=period.through}.sortedByDescending {it.day}}
        AlertDialog(onDismissRequest={selectedSupplier=null},title={Text(name)},text={
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("حركات الفترة المحددة فقط",style=MaterialTheme.typography.labelLarge)
                if(movements.isEmpty()) Text("لا توجد حركات داخل هذه الفترة؛ المتبقي يعود إلى حركات سابقة.")
                LazyColumn(Modifier.heightIn(max=420.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    items(movements,key={it.id}) {entry ->
                        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(if(entry.kind=="DEBT") "دين: ${amount(entry.amount)}" else "تسديد: ${amount(entry.amount)}",fontWeight=FontWeight.Bold)
                            Text(entry.day,style=MaterialTheme.typography.labelSmall)
                            if(entry.note.isNotBlank()) Text(entry.note,style=MaterialTheme.typography.bodySmall)
                            if(entry.pending) Text("محفوظ • بانتظار الرفع",style=MaterialTheme.typography.labelSmall)
                            HorizontalDivider()
                        }
                    }
                }
            }
        },confirmButton={TextButton(onClick={selectedSupplier=null}) {Text("إغلاق")}})
    }
    Scaffold(topBar={ScreenTopBar("التقارير الشهرية",onBack)}) {padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("monthly-list"),flingBehavior=rememberLedgerFlingBehavior(),contentPadding=PaddingValues(16.dp,6.dp,16.dp,32.dp),
            verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {
                Surface(shape=RoundedCornerShape(26.dp),color=colors.primaryContainer) {
                    Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(colors.primaryContainer,colors.secondaryContainer))).padding(20.dp),
                        verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CalendarMonth,null,tint=colors.primary,modifier=Modifier.size(28.dp))
                            Spacer(Modifier.width(10.dp));Text("نظرة مالية للشهر",style=MaterialTheme.typography.labelLarge,color=colors.onPrimaryContainer)
                        }
                        Text(if(custom) "فترة تختارها أنت" else monthTitle(YearMonth.parse(selected),current),
                            style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,color=colors.onPrimaryContainer)
                        Text("من ${period.from} إلى ${period.through}",style=MaterialTheme.typography.bodyMedium,color=colors.onPrimaryContainer)
                        FilledTonalButton(onClick={showMonths=true},modifier=Modifier.fillMaxWidth()) {
                            Text("اختيار شهر");Spacer(Modifier.width(8.dp));Icon(Icons.Rounded.ExpandMore,null)
                        }
                    }
                }
            }
            item {Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=!custom&&selected==current.toString(),onClick={selected=current.toString();custom=false},label={Text("الشهر الحالي")})
                FilterChip(selected=!custom&&selected==current.minusMonths(1).toString(),onClick={selected=current.minusMonths(1).toString();custom=false},
                    enabled=current.minusMonths(1)>=MonthlyReports.firstMonth,label={Text("الشهر السابق")})
                FilterChip(selected=custom,onClick={if(!custom) {val month=YearMonth.parse(selected);from=month.atDay(1).toString();through=month.atEndOfMonth().toString()};custom=!custom},
                    label={Text("تحديد فترة زمنية")},leadingIcon={Icon(Icons.Rounded.DateRange,null,Modifier.size(18.dp))})
            }}
            if(custom) item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={pickDate(true)},modifier=Modifier.weight(1f)) {Text("من: $from")}
                OutlinedButton(onClick={pickDate(false)},modifier=Modifier.weight(1f)) {Text("إلى: $through")}
            }}
            item {Text("حسابات الزبائن",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)}
            if(totals==null) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
            else {
                item {Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    MonthlyMetric("ديون الفترة",amount(totals.debts),"${totals.debtCount} عملية",Icons.Rounded.ReceiptLong,Modifier.weight(1f))
                    MonthlyMetric("تحصيلات الفترة",amount(totals.payments),"${totals.paymentCount} عملية",Icons.Rounded.Payments,Modifier.weight(1f))
                }}
                item {Surface(shape=RoundedCornerShape(20.dp),color=colors.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("صافي حركات الفترة",style=MaterialTheme.typography.labelLarge)
                        Text(amount(totals.debts-totals.payments),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                        Text("ديون − تحصيلات • ليس الرصيد المتبقي لجميع الحسابات. الرصيد الافتتاحي لا يُحسب حركة شهرية.",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                    }
                }}
            }
            item {Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {Text("ديون المذاخر",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    Text("ما تطلبه المذاخر من الصيدلية",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)}
                FilledTonalButton(onClick=onAddSupplier) {Icon(Icons.Rounded.Add,null,Modifier.size(18.dp));Text("تسجيل حركة")}
            }}
            item {Surface(shape=RoundedCornerShape(24.dp),color=colors.tertiaryContainer) {
                Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.LocalShipping,null,tint=colors.onTertiaryContainer)
                        Text("المتبقي حتى نهاية الفترة",style=MaterialTheme.typography.labelLarge,color=colors.onTertiaryContainer)
                    }
                    Text(payable?.let {amount(it.closing)} ?: "…",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,color=colors.onTertiaryContainer)
                    Text("يشمل الرصيد السابق المسجّل • السالب رصيد لصالح الصيدلية",style=MaterialTheme.typography.bodySmall,color=colors.onTertiaryContainer)
                }
            }}
            payable?.let {value ->
                item {Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    MonthlyMetric("ديون المذاخر في الفترة",amount(value.debts),"مشتريات بالدين",Icons.Rounded.Inventory2,Modifier.weight(1f))
                    MonthlyMetric("تسديدات المذاخر في الفترة",amount(value.payments),"مدفوع للمذاخر",Icons.Rounded.Payments,Modifier.weight(1f))
                }}
                if(value.suppliers.isEmpty()) item {Text("لم تُسجّل حركات للمذاخر بعد. أضف الدين الفعلي أو التسديد مع تاريخه؛ لا تُستنتج هذه المبالغ من ديون الزبائن.",style=MaterialTheme.typography.bodySmall)}
                items(value.suppliers,key={it.name}) {supplier ->Surface(shape=RoundedCornerShape(20.dp),color=colors.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                        Text(supplier.name,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Bold)
                        Text("المتبقي: ${amount(supplier.closing)}",style=MaterialTheme.typography.titleMedium)
                        Text("ديون الفترة: ${amount(supplier.debts)}\nتسديدات الفترة: ${amount(supplier.payments)}",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                        TextButton(onClick={selectedSupplier=supplier.name}) {Text("تفاصيل حركات المذخر")}
                    }
                }}
            }
            if(syncMessage.isNotBlank()) item {Text(syncMessage,style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)}
            item {Text("الفترة شاملة اليومين • توقيت بغداد • ديون المذاخر مستقلة عن حسابات الزبائن",style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)}
        }
    }
}
private fun monthTitle(month: YearMonth,current: YearMonth): String {
    val names=listOf("كانون الثاني","شباط","آذار","نيسان","أيار","حزيران","تموز","آب","أيلول","تشرين الأول","تشرين الثاني","كانون الأول")
    val prefix=when(month) {current->"الشهر الحالي • ";current.minusMonths(1)->"الشهر السابق • ";else->""}
    return prefix+names[month.monthValue-1]+" ${month.year}"
}
@Composable
private fun MonthlyMetric(title: String,amount: String,caption: String,icon: androidx.compose.ui.graphics.vector.ImageVector,modifier: Modifier=Modifier) {
    Surface(modifier,shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Icon(icon,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(22.dp))
            Text(title,style=MaterialTheme.typography.labelLarge)
            Text(amount,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Text(caption,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
