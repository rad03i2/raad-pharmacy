package com.radwan.raadpharmacy.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.data.*
import com.radwan.raadpharmacy.followup.*
import com.radwan.raadpharmacy.util.validatedIraqiWhatsappPhone
import kotlinx.coroutines.*

internal data class FollowupSmsActions(val attempts: Map<String,SmsAttempt>,val busy: String?,val now: Long,val send: (String)->Unit)

@Composable internal fun rememberFollowupSmsActions(accounts: List<DebtFollowupEngine.Account>,entries: List<LedgerEntry>,owner: FollowupIdentity?): FollowupSmsActions {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val revision by SmsUpdates.revision.collectAsStateWithLifecycle()
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    var attempts by remember(owner) {mutableStateOf<Map<String,SmsAttempt>>(emptyMap())}
    var busy by remember {mutableStateOf<String?>(null)}
    var message by remember {mutableStateOf<String?>(null)}
    var repeatId by remember {mutableStateOf<String?>(null)}
    var permissionId by rememberSaveable {mutableStateOf<String?>(null)}
    var permissionOwner by rememberSaveable {mutableStateOf<String?>(null)}
    var permissionRepeat by rememberSaveable {mutableStateOf(false)}
    var permissionTick by remember {mutableIntStateOf(0)}
    fun ownerKey()=owner?.let {it.owner+"|"+it.pharmacy}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {result->
        if(result.values.isNotEmpty() && result.values.all {it}) permissionTick++
        else {permissionId=null;message="لم يُمنح إذن SMS أو الهاتف. لم تُرسل أي رسالة."}
    }
    LaunchedEffect(Unit) {while(isActive) {delay(15_000L);now=System.currentTimeMillis()}}
    LaunchedEffect(owner,accounts,entries,revision,now) {
        val expected=owner
        attempts=if(expected==null) emptyMap() else withContext(Dispatchers.IO) {
            SmsAttemptStore(context).use {store->accounts.mapNotNull {account->
                val case=FollowupReminderStore.caseFor(account,entries)
                val text=ReadyMessages.textOnlyFor(context,account)
                val phone=validatedIraqiWhatsappPhone(account.customer.phone.orEmpty())?.let {"+$it"}.orEmpty()
                val hash=FollowupSms.fingerprint(case,phone,text)
                store.latest(expected,account.customer.id)?.takeIf {it.fingerprint==hash || it.state(now)==SmsState.SENDING}?.let {account.customer.id to it}
            }.toMap()}
        }
    }
    fun send(id: String,allowRepeat: Boolean=false) {
        if(busy!=null) return
        val expected=owner ?: run {message="انتظر فتح حساب الصيدلية أولاً.";return}
        val account=accounts.firstOrNull {it.customer.id==id && it.balance>0} ?: run {message="هذا الحساب لم يعد يحتاج إلى مطالبة.";return}
        if(!FollowupSms.permitted(context)) {
            permissionId=id;permissionOwner=ownerKey();permissionRepeat=allowRepeat
            permission.launch(FollowupSms.permissions);return
        }
        busy=id
        scope.launch {
            try {
                val case=FollowupReminderStore.caseFor(account,entries)
                val text=ReadyMessages.textOnlyFor(context,account)
                withContext(Dispatchers.IO) {FollowupSms.send(context,expected,case,account.customer.phone.orEmpty(),text,allowRepeat)}
            } catch(cancelled: CancellationException) {throw cancelled}
            catch(_: SmsRepeatRequired) {repeatId=id}
            catch(e: Exception) {message=e.message ?: "تعذر إرسال SMS. تحقق من الشريحة والإذن والرقم."}
            finally {busy=null;SmsUpdates.revision.value=SmsUpdates.revision.value+1}
        }
    }
    LaunchedEffect(permissionTick) {
        if(permissionTick>0) {
            val id=permissionId;val expected=permissionOwner;val allow=permissionRepeat
            permissionId=null;permissionOwner=null;permissionRepeat=false
            if(id!=null && expected==ownerKey()) send(id,allow)
            else message="تغيّر الحساب أثناء طلب الإذن. اضغط SMS من جديد." 
        }
    }
    message?.let {AlertDialog(onDismissRequest={message=null},title={Text("إرسال SMS")},text={Text(it)},confirmButton={TextButton(onClick={message=null}) {Text("حسناً")}})}
    repeatId?.let {id->AlertDialog(onDismissRequest={repeatId=null},title={Text("إعادة إرسال SMS؟")},
        text={Text("سبق طلب إرسال رسالة لهذا الزبون. إذا فشلت بعض الأجزاء أو لم تصل نتيجة النظام، فقد تكون أجزاء أخرى أُرسلت. تحقق من الرسائل قبل إعادة الإرسال.")},
        confirmButton={TextButton(onClick={repeatId=null;send(id,true)}) {Text("إعادة الإرسال")}},
        dismissButton={TextButton(onClick={repeatId=null}) {Text("إلغاء")}})}
    return FollowupSmsActions(attempts,busy,now,{send(it)})
}
