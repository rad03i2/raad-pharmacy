package com.radwan.raadpharmacy.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.cloud.*
import kotlinx.coroutines.*

@Composable
internal fun rememberCreditSnapshot(): CreditSnapshot {
    val context=LocalContext.current
    val store=remember(context) { CustomerCreditStore.get(context) }
    val updates=remember(store) { store.snapshots() }
    val state by updates.collectAsStateWithLifecycle(initialValue=store.current())
    LaunchedEffect(store) { store.start(); store.requestRefresh() }
    return state
}

@Composable
internal fun CustomerCreditControlLine(customerId: String, name: String) {
    val state=rememberCreditSnapshot()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val blocked=customerId in state.blockedIds
    val pending=state.requests.any { it.customerId==customerId && it.block != blocked }
    Column {
        if (blocked) Text(if (state.canManage) "الزبون محجوب • رعد يملك إدارة الحجب" else "الزبون محجوب • التحصيل متاح فقط",
            style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.error)
        if (state.owner.isNotBlank()) TextButton(onClick={confirm=true},enabled=!busy && (!pending || state.canManage)) {
            Text(if(busy) "جارٍ تأكيد العملية…" else if(pending && !state.canManage) "طلبك قيد مراجعة رعد" else
                (if(state.canManage) "" else "طلب ") + if(blocked) "رفع حجب الزبون" else "حجب الزبون")
        }
    }
    if(confirm) AlertDialog(onDismissRequest={if(!busy)confirm=false},title={Text(if(blocked)"رفع الحجب؟" else "حجب الزبون؟")},
        text={Text(if(state.canManage) "${if(blocked) "السماح لأحمد وفؤاد بإضافة دين إلى" else "منع أحمد وفؤاد من إضافة دين إلى"} $name؟ يبقى التحصيل متاحاً."
            else "إرسال طلب ${if(blocked) "رفع الحجب" else "حجب"} الزبون $name إلى رعد؟")},
        confirmButton={TextButton(enabled=!busy,onClick={busy=true;scope.launch {
            try {
                withContext(Dispatchers.IO) { if(state.canManage) CustomerCreditStore.get(context).setBlocked(customerId,!blocked)
                    else CustomerCreditStore.get(context).request(customerId,!blocked) }
                confirm=false;message=if(state.canManage) "تم تأكيد التغيير على الخادم." else "تم إرسال الطلب إلى رعد."
            } catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){message=CloudMessagingFeedback.forFailure(context,error)}
            finally{busy=false}
        }}){Text("تأكيد")}},dismissButton={TextButton(enabled=!busy,onClick={confirm=false}){Text("إلغاء")}})
    message?.let { AlertDialog(onDismissRequest={message=null},text={Text(it)},confirmButton={TextButton(onClick={message=null}){Text("حسنًا")}}) }
}

/** Inside the existing incoming-message dialog; both buttons execute the guarded server RPC. */
@Composable
internal fun CreditRequestActions(requestId: String) {
    val state=rememberCreditSnapshot()
    val request=state.requests.firstOrNull { it.id==requestId }
    if(!state.canManage) return
    if(request==null) { Text("لم يعد الطلب معلقاً.",style=MaterialTheme.typography.labelSmall);return }
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var decision by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled=!busy,onClick={decision=true}){Text("وضع حجب")}
        OutlinedButton(enabled=!busy,onClick={decision=false}){Text("رفع حجب")}
    }
    decision?.let { block -> AlertDialog(onDismissRequest={if(!busy)decision=null},
        title={Text(if(block) "تأكيد حجب الزبون" else "تأكيد رفع الحجب")},
        text={Text("${request.customerName} • طلب من ${request.requestedByName}\nسيُحفظ القرار على الخادم، ويبقى التحصيل متاحاً.")},
        confirmButton={TextButton(enabled=!busy,onClick={busy=true;scope.launch {
            try { withContext(Dispatchers.IO){CustomerCreditStore.get(context).setBlocked(request.customerId,block,request.id)};decision=null }
            catch(cancelled:CancellationException){throw cancelled}
            catch(failure:Exception){error=CloudMessagingFeedback.forFailure(context,failure)}
            finally{busy=false}
        }}){Text(if(busy) "جارٍ التأكيد…" else "تأكيد")}},
        dismissButton={TextButton(enabled=!busy,onClick={decision=null}){Text("إلغاء")}}) }
    error?.let { AlertDialog(onDismissRequest={error=null},text={Text(it)},confirmButton={TextButton(onClick={error=null}){Text("حسنًا")}}) }
}

@Composable
internal fun CreditRequestsInbox() {
    val state=rememberCreditSnapshot()
    var open by remember { mutableStateOf(false) }
    if(!state.canManage) return
    OutlinedButton(onClick={open=true},modifier=Modifier.fillMaxWidth()) {
        Text("الرسائل الواردة • طلبات الحجب (${state.requests.size})")
    }
    if(open) AlertDialog(onDismissRequest={open=false},title={Text("طلبات حجب الزبائن ورفع الحجب")},
        text={LazyColumn(Modifier.heightIn(max=420.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(state.requests.isEmpty()) item { Text("لا توجد طلبات معلقة.") }
            items(state.requests,key={it.id}) { request -> Column {
                Text(request.customerName,style=MaterialTheme.typography.titleMedium)
                Text("${request.requestedByName}: ${if(request.block) "طلب حجب" else "طلب رفع الحجب"}")
                CreditRequestActions(request.id)
            } }
        }},confirmButton={TextButton(onClick={open=false}){Text("إغلاق")}})
}
