package com.radwan.raadpharmacy.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.data.Customer
import com.radwan.raadpharmacy.data.MutationResult
import com.radwan.raadpharmacy.shopping.MedicineShoppingStore
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun normalizedArea(value: String)=value.trim().replace(Regex("\\s+")," ")

@Composable
internal fun BulkCustomerAreasScreen(vm: PharmacyLedgerViewModel,onBack: ()->Unit) {
    val context=LocalContext.current
    val store=remember(context) {MedicineShoppingStore.get(context)}
    val state by store.state.collectAsStateWithLifecycle()
    val customers by vm.customers.collectAsStateWithLifecycle()
    LaunchedEffect(store) {store.start();store.request()}
    BulkCustomerAreasContent(customers,state.areas,onBack,{store.addArea(it)},vm::assignCustomerAreas)
}

@Composable
internal fun BulkCustomerAreasContent(customers: List<Customer>,catalog: List<String>,onBack: ()->Unit,
    onCreate: suspend (String)->Unit,onAssign: suspend (Map<String,String>,String)->MutationResult) {
    val scope=rememberCoroutineScope()
    var area by rememberSaveable {mutableStateOf<String?>(null)}
    var selecting by rememberSaveable {mutableStateOf(false)}
    var selected by rememberSaveable {mutableStateOf<Map<String,String>>(emptyMap())}
    var query by rememberSaveable {mutableStateOf("")}
    var creating by rememberSaveable {mutableStateOf(false)}
    var name by rememberSaveable {mutableStateOf("")}
    var confirming by rememberSaveable {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var message by rememberSaveable {mutableStateOf<String?>(null)}
    val areaScroll=rememberLazyListState()
    val memberScroll=rememberLazyListState()
    val selectionScroll=rememberLazyListState()
    val grouped=remember(customers) {customers.groupBy {normalizedArea(it.area)}}
    val areas=remember(catalog,grouped) {(catalog+grouped.keys).map(::normalizedArea).filter {it.isNotBlank()}.distinct().sorted()}
    val members=grouped[area].orEmpty()
    val candidates=remember(customers,query,area) {customers.filter {normalizedArea(it.area)!=area &&
        (query.isBlank() || it.name.contains(query,true) || it.phone.orEmpty().contains(query) || it.area.contains(query,true))}.sortedBy {it.name}}
    fun back() {if(busy) return;if(selecting) {selecting=false;selected=emptyMap();query=""} else if(area!=null) area=null else onBack()}
    BackHandler {back()}
    if(message!=null) AlertDialog(onDismissRequest={message=null},title={Text("إدارة المناطق")},text={Text(message.orEmpty())},
        confirmButton={TextButton(onClick={message=null}) {Text("حسناً")}})
    if(creating) AlertDialog(onDismissRequest={if(!busy) creating=false},title={Text("إنشاء منطقة")},
        text={OutlinedTextField(name,{if(it.length<=160) name=it},label={Text("اسم المنطقة")},singleLine=true,enabled=!busy)},
        confirmButton={TextButton(enabled=!busy&&normalizedArea(name).isNotEmpty(),onClick={busy=true;scope.launch {
            try {val value=normalizedArea(name);onCreate(value);area=value;creating=false;name=""}
            catch(cancelled: CancellationException) {throw cancelled}
            catch(e: Exception) {message=e.message ?: "تعذر حفظ المنطقة؛ حاول مجدداً."}
            finally {busy=false}
        }}) {Text(if(busy) "جارٍ الحفظ…" else "حفظ المنطقة")}},
        dismissButton={TextButton(enabled=!busy,onClick={creating=false}) {Text("إلغاء")}})
    if(confirming) AlertDialog(onDismissRequest={if(!busy) confirming=false},title={Text("تأكيد تعيين المنطقة")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("تعيين ${selected.size} زبون إلى «${area.orEmpty()}».")
            val moving=selected.values.count {it.isNotBlank() && normalizedArea(it)!=area}
            if(moving>0) Text("سيُنقل $moving زبون من مناطقهم السابقة إلى هذه المنطقة.")
            Text("يتغير حقل المنطقة فقط. الزبون الذي تغيرت منطقته أثناء الاختيار يُترك للمراجعة.",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton(enabled=!busy,onClick={val target=area ?: return@TextButton;busy=true;scope.launch {
            try {val result=onAssign(selected.toMap(),target);message=result.message
                if(result.success) {selected=emptyMap();selecting=false;query=""};confirming=false}
            catch(cancelled: CancellationException) {throw cancelled}
            catch(_: Exception) {message="تعذر حفظ المناطق؛ أعد المحاولة.";confirming=false}
            finally {busy=false}
        }}) {Text(if(busy) "جارٍ الحفظ…" else "تأكيد إضافة الزبائن")}},
        dismissButton={TextButton(enabled=!busy,onClick={confirming=false}) {Text("إلغاء")}})
    Scaffold(topBar={ScreenTopBar(if(selecting) "إضافة زبائن للمنطقة" else area ?: "إضافة المنطقة بشكل جماعي",{back()})},
        bottomBar={if(selecting) Surface(tonalElevation=3.dp) {
            Button(enabled=selected.isNotEmpty()&&!busy,onClick={confirming=true},modifier=Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()) {
                Text("إضافة ${selected.size} زبون إلى المنطقة")
            }
        }}) {padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(area==null) {
                FilledTonalButton(enabled=!busy,onClick={creating=true},modifier=Modifier.fillMaxWidth()) {Icon(Icons.Rounded.AddLocationAlt,null);Spacer(Modifier.width(8.dp));Text("إنشاء منطقة جديدة")}
                Text("المناطق • ${areas.size}",style=MaterialTheme.typography.titleSmall)
                if(areas.isEmpty()) Text("أنشئ منطقة، ثم اختر الزبائن لإضافتهم إليها.")
                LazyColumn(state=areaScroll,verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=24.dp),modifier=Modifier.testTag("bulk-areas")) {
                    items(areas,key={it}) {value -> AreaCustomerRow(value,"${grouped[value].orEmpty().size} زبون",{area=value})}
                }
            } else if(!selecting) {
                Text("${members.size} زبون في المنطقة",style=MaterialTheme.typography.titleMedium)
                Button(enabled=!busy,onClick={selecting=true;selected=emptyMap();query=""},modifier=Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.GroupAdd,null);Spacer(Modifier.width(8.dp));Text("إضافة زبائن لهذه المنطقة")
                }
                if(members.isEmpty()) Text("لا يوجد زبائن في هذه المنطقة بعد.")
                LazyColumn(state=memberScroll,verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=24.dp),modifier=Modifier.testTag("area-members")) {
                    items(members,key={it.id}) {customer -> AreaCustomerRow(customer.name,customer.phone.orEmpty(),null)}
                }
            } else {
                Text("إلى ${area.orEmpty()} • حُدد ${selected.size}",style=MaterialTheme.typography.titleSmall)
                OutlinedTextField(query,{query=it},label={Text("بحث بالاسم أو الهاتف أو المنطقة")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(enabled=!busy&&candidates.isNotEmpty(),onClick={selected=selected+candidates.associate {it.id to it.area}}) {Text("تحديد النتائج (${candidates.size})")}
                    TextButton(enabled=!busy&&selected.isNotEmpty(),onClick={selected=emptyMap()}) {Text("إلغاء التحديد")}
                }
                LazyColumn(state=selectionScroll,verticalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.weight(1f).testTag("area-candidates")) {
                    items(candidates,key={it.id}) {customer ->
                        val checked=customer.id in selected
                        Surface(shape=RoundedCornerShape(16.dp),color=if(checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier=Modifier.fillMaxWidth().clickable(enabled=!busy) {selected=if(checked) selected-customer.id else selected+(customer.id to customer.area)}) {
                            Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                Checkbox(checked,null,enabled=!busy)
                                Column(Modifier.weight(1f).padding(horizontal=8.dp)) {Text(customer.name,style=MaterialTheme.typography.titleSmall)
                                    Text(customer.area.ifBlank {"بلا منطقة"},style=MaterialTheme.typography.bodySmall)}
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AreaCustomerRow(title: String,subtitle: String,onClick: (()->Unit)?) {
    Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,
        modifier=Modifier.fillMaxWidth().then(if(onClick!=null) Modifier.clickable(onClick=onClick) else Modifier)) {
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {Text(title,style=MaterialTheme.typography.titleSmall)
                if(subtitle.isNotBlank()) Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(onClick!=null) Icon(Icons.Rounded.ChevronLeft,null)
        }
    }
}
