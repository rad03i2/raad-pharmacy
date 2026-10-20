package com.radwan.raadpharmacy.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.data.*
import com.radwan.raadpharmacy.followup.*
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.util.formatMoney
import kotlinx.coroutines.*

@Composable internal fun CustomerManualFollowupLine(customer: Customer,entries: List<LedgerEntry>) {
    val context=LocalContext.current
    val store=remember(context) {ManualFollowupStore.get(context)}
    val state by store.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(store) {store.start();store.request()}
    val account=remember(customer,entries,state.cases) {applyManualFollowups(listOf(DebtFollowupEngine.calculate(customer,entries)),state.cases).single()}
    TextButton(enabled=!busy && state.identity!=null && account.balance>0 && !inFollowup(account),onClick={
        val owner=state.identity ?: return@TextButton
        busy=true;scope.launch {
            try {store.add(owner,listOf(ManualFollowupCase(customer.id,account.unpaidDebtKeys)));message="أضيف الزبون إلى المتابعة. لا تتغير تواريخ الدين أو مبالغه."}
            catch(cancelled: CancellationException) {throw cancelled}
            catch(_: Exception) {message="تعذرت الإضافة للمتابعة. أعد المحاولة."}
            finally {busy=false}
        }
    },modifier=Modifier.testTag("add-manual-followup")) {
        Icon(if(inFollowup(account)) Icons.Rounded.CheckCircle else Icons.Rounded.PlaylistAdd,null,Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if(busy) "جارٍ الإضافة…" else if(inFollowup(account)) "موجود في المتابعة" else "إضافة إلى المتابعة",style=MaterialTheme.typography.labelMedium)
    }
    message?.let {AlertDialog(onDismissRequest={message=null},title={Text("المتابعة")},text={Text(it)},confirmButton={TextButton(onClick={message=null}) {Text("حسناً")}})}
}

@Composable internal fun BulkManualFollowupScreen(vm: PharmacyLedgerViewModel,onBack: ()->Unit) {
    val context=LocalContext.current
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val security by vm.securityState.collectAsStateWithLifecycle()
    val store=remember(context) {ManualFollowupStore.get(context)}
    val state by store.state.collectAsStateWithLifecycle()
    var records by remember {mutableStateOf<List<DebtFollowupEngine.Account>>(emptyList())}
    LaunchedEffect(store) {store.start();store.request()}
    LaunchedEffect(customers,entries,state.cases) {records=withContext(Dispatchers.Default) {applyManualFollowups(DebtFollowupEngine.build(customers,entries),state.cases)}}
    val latest by rememberUpdatedState(records)
    BulkManualFollowupContent(records,state.identity,security.hideAmounts,onBack) {owner,ids->
        val selected=latest.filter {it.customer.id in ids && it.balance>0 && !inFollowup(it)}
        check(selected.isNotEmpty()) {"الحسابات المحددة سُددت أو أُضيفت للمتابعة بالفعل."}
        store.add(owner,selected.map {ManualFollowupCase(it.customer.id,it.unpaidDebtKeys)})
        "أضيف ${selected.size} زبون إلى المتابعة. ستُزامن الإضافات بين أجهزة الصيدلية عند توفر الاتصال."
    }
}

@Composable internal fun BulkManualFollowupContent(accounts: List<DebtFollowupEngine.Account>,owner: FollowupIdentity?,hideAmounts: Boolean,
    onBack: ()->Unit,onAdd: suspend (FollowupIdentity,Set<String>)->String) {
    var query by rememberSaveable {mutableStateOf("")}
    var selected by rememberSaveable {mutableStateOf<List<String>>(emptyList())}
    var confirming by rememberSaveable {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    val listState=rememberLazyListState()
    val candidates=remember(accounts,query) {accounts.filter {it.balance>0 && !inFollowup(it) &&
        (query.isBlank() || it.customer.name.contains(query,true) || it.customer.phone.orEmpty().contains(query) || it.customer.area.contains(query,true))}.sortedBy {it.customer.name}}
    LaunchedEffect(owner) {selected=emptyList();confirming=false}
    BackHandler {if(!busy) onBack()}
    message?.let {AlertDialog(onDismissRequest={message=null},title={Text("الإضافة إلى المتابعة")},text={Text(it)},confirmButton={TextButton(onClick={message=null}) {Text("حسناً")}})}
    if(confirming) AlertDialog(onDismissRequest={if(!busy) confirming=false},title={Text("تأكيد الإضافة للمتابعة")},
        text={Text("إضافة ${selected.size} زبون إلى قائمة المطالبة. تبقى المبالغ والتواريخ الأصلية كما هي؛ وتُتجاهل الحسابات المسددة أو المضافة مسبقاً.")},
        confirmButton={TextButton(enabled=!busy && owner!=null,onClick={val expected=owner ?: return@TextButton;busy=true;scope.launch {
            try {message=onAdd(expected,selected.toSet());selected=emptyList()}
            catch(cancelled: CancellationException) {throw cancelled}
            catch(e: Exception) {message=e.message ?: "تعذرت الإضافة للمتابعة."}
            finally {busy=false;confirming=false}
        }}) {Text(if(busy) "جارٍ الإضافة…" else "تأكيد الإضافة")}},
        dismissButton={TextButton(enabled=!busy,onClick={confirming=false}) {Text("إلغاء")}})
    Scaffold(topBar={ScreenTopBar("إضافة جماعية إلى المتابعة",{if(!busy) onBack()})},bottomBar={Surface(tonalElevation=3.dp) {
        Button(enabled=selected.isNotEmpty() && owner!=null && !busy,onClick={confirming=true},modifier=Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()) {Text("إضافة ${selected.size} زبون إلى المتابعة")}
    }}) {padding->Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("اختر الحسابات المدينة التي تريد المطالبة بها الآن.",style=MaterialTheme.typography.bodyMedium)
        OutlinedTextField(query,{query=it},singleLine=true,label={Text("بحث بالاسم أو الهاتف أو المنطقة")},modifier=Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(enabled=!busy,onClick={selected=(selected+candidates.map {it.customer.id}).distinct()}) {Text("تحديد النتائج (${candidates.size})")}
            TextButton(enabled=!busy,onClick={selected=emptyList()}) {Text("إلغاء التحديد")}
        }
        if(candidates.isEmpty()) Text("لا توجد حسابات مطابقة خارج قائمة المتابعة.")
        LazyColumn(state=listState,verticalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.weight(1f).testTag("followup-candidates")) {
            items(candidates,key={it.customer.id}) {record->val checked=record.customer.id in selected
                Surface(shape=MaterialTheme.shapes.large,color=if(checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier=Modifier.fillMaxWidth().clickable(enabled=!busy) {selected=if(checked) selected-record.customer.id else selected+record.customer.id}) {
                    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(checked,null);Column(Modifier.weight(1f)) {Text(record.customer.name,style=MaterialTheme.typography.titleSmall);Text(record.customer.area,style=MaterialTheme.typography.bodySmall)}
                        Text(if(hideAmounts) "••••" else formatMoney(record.balance),style=MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }}
}

@Composable internal fun FollowupSharingSettings() {
    val context=LocalContext.current
    val settings=remember(context) {FollowupShareSettings(context)}
    var image by remember {mutableStateOf(settings.includeImage())}
    var sims by remember {mutableStateOf<List<SmsSim>>(emptyList())}
    var selected by remember {mutableStateOf(settings.selectedSim())}
    var error by remember {mutableStateOf<String?>(null)}
    fun refresh() {runCatching {sims=FollowupSms.sims(context);selected=settings.selectedSim()}.onFailure {error="تعذر قراءة الشرائح. تحقق من إذن الهاتف."}}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {if(it) refresh() else error="لم يُمنح إذن الهاتف. يلزم الإذن لعرض الشريحة الأولى والثانية."}
    val lifecycle=LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME) refresh()};lifecycle.lifecycle.addObserver(observer);refresh();onDispose {lifecycle.lifecycle.removeObserver(observer)}}
    OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {Text("إرفاق صورة الكشف مع النص",style=MaterialTheme.typography.titleSmall);Text("عند الإغلاق تُشارك الرسالة وحدها عبر واتساب وتطبيقات المشاركة.",style=MaterialTheme.typography.bodySmall)}
            Switch(image,{value->runCatching {settings.includeImage(value);image=value}.onFailure {error="تعذر حفظ الإعداد."}})
        }
        HorizontalDivider()
        Text("شريحة إرسال SMS",style=MaterialTheme.typography.titleSmall)
        Text("SMS نصي فقط. يُرسل زر المتابعة عبر الشريحة المختارة هنا، وفق باقة ورسوم شركة الاتصال.",style=MaterialTheme.typography.bodySmall)
        sims.forEach {sim->Row(Modifier.fillMaxWidth().clickable {runCatching {settings.selectSim(sim);selected=sim.id to sim.slot}.onFailure {error="تعذر حفظ الشريحة."}},verticalAlignment=Alignment.CenterVertically) {
            RadioButton(selected==(sim.id to sim.slot),onClick={settings.selectSim(sim);selected=sim.id to sim.slot});Text(sim.label)
        }}
        if(selected!=null && sims.none {it.id==selected?.first && it.slot==selected?.second}) Text("الشريحة المحفوظة غير متاحة؛ اختر الشريحة من جديد.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={permission.launch(android.Manifest.permission.READ_PHONE_STATE)}) {Text(if(sims.isEmpty()) "عرض الشرائح واختيار شريحة SMS" else "تحديث الشرائح")}
        error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
    }}
}
