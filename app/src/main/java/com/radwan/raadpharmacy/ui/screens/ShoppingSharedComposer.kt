package com.radwan.raadpharmacy.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.shopping.*
import kotlinx.coroutines.*

/** Shown only after the normal account + PIN gate. The quick target commits without a details dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShoppingSharedComposer(store: MedicineShoppingStore,request: String,onHandled: ()->Unit) {
    val context=LocalContext.current
    val inbox=remember(context) {ShoppingShareInbox(context)}
    val scope=rememberCoroutineScope()
    var draft by remember(request) {mutableStateOf<SharedShoppingDraft?>(null)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    suspend fun save(value: SharedShoppingDraft) {
        if(busy) return
        busy=true;error=null
        try {
            val identity=checkNotNull(store.identity()) {"انتظر اكتمال تسجيل الدخول للصيدلية."}
            withContext(Dispatchers.IO) {inbox.write(value)}
            for(id in value.ids) store.add(id,value.title,
                listOfNotNull(value.quantity.takeIf {it.isNotBlank()}?.let {"العدد: $it"},value.caption.takeIf {it.isNotBlank()}).joinToString("\n"),true,identity)
            withContext(Dispatchers.IO) {inbox.discard(request)}
            onHandled()
        }catch(cancelled: CancellationException) {throw cancelled}
        catch(e: Exception) {error=e.message?:"لم تكتمل الإضافة؛ الصور محفوظة ويمكن إعادة المحاولة."}
        finally {busy=false}
    }
    LaunchedEffect(request) {
        try {draft=withContext(Dispatchers.IO) {inbox.read(request)}}
        catch(_: Exception) {error="تعذر فتح الصور المشتركة؛ أعد مشاركتها."}
    }
    val state by store.state.collectAsState()
    val value=draft
    LaunchedEffect(request,value?.quick,state.identity) {if(value?.quick==true && state.identity!=null && error==null) save(value)}
    if(value?.quick==true) {
        // Intentionally no success dialog, toast, notification, title or quantity prompt.
        if(error!=null) AlertDialog(onDismissRequest=onHandled,title={Text("لم تكتمل الإضافة السريعة")},text={Text(error.orEmpty())},
            confirmButton={TextButton(onClick={scope.launch {save(value)}}) {Text("إعادة المحاولة")}},
            dismissButton={TextButton(onClick=onHandled) {Text("لاحقاً")}})
    } else if(value!=null) ModalBottomSheet(onDismissRequest={if(!busy) scope.launch {withContext(Dispatchers.IO) {inbox.discard(request)};onHandled()}},
        sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("إضافة الصور إلى قائمة الشراء",style=MaterialTheme.typography.titleLarge)
            Text("${value.ids.size} صورة • كل صورة مادة مستقلة • الصور محفوظة على هذا الهاتف فقط",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(value.title,{if(it.length<=240)draft=value.copy(title=it)},label={Text("عنوان اختياري")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
            OutlinedTextField(value.quantity,{if(it.length<=20&&it.all {c->Character.digit(c,10)>=0})draft=value.copy(quantity=it)},
                label={Text("عدد اختياري")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            if(value.ids.size>1) Text("العنوان والعدد يطبّقان على كل صورة محددة، ويمكن تركهما فارغين.",style=MaterialTheme.typography.bodySmall)
            if(error!=null) Text(error.orEmpty(),color=MaterialTheme.colorScheme.error)
            Button(onClick={scope.launch {save(value)}},enabled=!busy&&state.identity!=null,modifier=Modifier.fillMaxWidth()) {Text(if(busy) "جارٍ الحفظ…" else "إضافة إلى المواد المطلوبة")}
            TextButton(onClick={scope.launch {withContext(Dispatchers.IO) {inbox.discard(request)};onHandled()}},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("إلغاء")}
        }
    } else if(error!=null) AlertDialog(onDismissRequest=onHandled,title={Text("تعذرت المشاركة")},text={Text(error.orEmpty())},confirmButton={TextButton(onClick=onHandled) {Text("إغلاق")}})
}
