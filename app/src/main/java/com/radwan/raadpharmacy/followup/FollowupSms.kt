package com.radwan.raadpharmacy.followup

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.database.sqlite.*
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.radwan.raadpharmacy.data.FollowupReminderStore
import com.radwan.raadpharmacy.util.validatedIraqiWhatsappPhone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal data class SmsSim(val id: Int,val slot: Int,val label: String)
internal class FollowupShareSettings(context: Context) {
    private val prefs=context.applicationContext.getSharedPreferences("raad_followup_share_v27",Context.MODE_PRIVATE)
    fun includeImage()=prefs.getBoolean("include-image",true)
    fun includeImage(value: Boolean) {check(prefs.edit().putBoolean("include-image",value).commit())}
    fun selectedSim(): Pair<Int,Int>? = if(prefs.contains("sim-id")) prefs.getInt("sim-id",-1) to prefs.getInt("sim-slot",-1) else null
    fun selectSim(sim: SmsSim) {require(sim.id>=0 && sim.slot>=0);check(prefs.edit().putInt("sim-id",sim.id).putInt("sim-slot",sim.slot).commit())}
}
internal enum class SmsState {SENDING,SENT,FAILED,UNCERTAIN}
internal data class SmsAttempt(val token: String,val fingerprint: String,val parts: Int,val results: List<Int?>,val started: Long,val sentAt: Long?,val uncertain: Boolean=false) {
    fun state(now: Long=System.currentTimeMillis())=when {
        results.any {it!=null && it!=Activity.RESULT_OK}->SmsState.FAILED
        results.size==parts && results.all {it==Activity.RESULT_OK}->SmsState.SENT
        uncertain || now-started>=120_000L->SmsState.UNCERTAIN
        else->SmsState.SENDING
    }
}
internal class SmsRepeatRequired: IllegalStateException("سبق طلب إرسال رسالة لهذا الزبون. تحقق قبل إعادة الإرسال.")
internal object SmsUpdates {val revision=MutableStateFlow(0L)}

/** Durable callbacks survive Activity/process recreation. No automatic resend of an uncertain SMS. */
internal class SmsAttemptStore(context: Context,name: String=File(context.noBackupFilesDir,"followup-sms-v27.db").absolutePath): SQLiteOpenHelper(context,name,null,1) {
    override fun onCreate(db: SQLiteDatabase) {db.execSQL("CREATE TABLE attempts(token TEXT PRIMARY KEY,owner TEXT NOT NULL,pharmacy TEXT NOT NULL,customer TEXT NOT NULL,fingerprint TEXT NOT NULL,parts INTEGER NOT NULL,results TEXT NOT NULL,started INTEGER NOT NULL,sent_at INTEGER,uncertain INTEGER NOT NULL DEFAULT 0)");db.execSQL("CREATE INDEX sms_customer ON attempts(owner,pharmacy,customer,started)")}
    override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int)=error("Unsupported SMS status version")
    private fun find(where: String,args: Array<String>): SmsAttempt? = readableDatabase.query("attempts",null,where,args,null,null,"started DESC, rowid DESC","1").use {c->
        if(!c.moveToFirst()) null else {
            fun text(key: String)=c.getString(c.getColumnIndexOrThrow(key))
            fun number(key: String)=c.getLong(c.getColumnIndexOrThrow(key))
            val rows=JSONArray(text("results"))
            SmsAttempt(text("token"),text("fingerprint"),number("parts").toInt(),List(rows.length()) {if(rows.isNull(it)) null else rows.getInt(it)},number("started"),
                if(c.isNull(c.getColumnIndexOrThrow("sent_at"))) null else number("sent_at"),number("uncertain")!=0L)
        }
    }
    fun latest(scope: FollowupIdentity,customer: String)=find("owner=? AND pharmacy=? AND customer=?",arrayOf(scope.owner,scope.pharmacy,customer))
    fun begin(scope: FollowupIdentity,customer: String,fingerprint: String,parts: Int,allowRepeat: Boolean=false,now: Long=System.currentTimeMillis()): SmsAttempt {
        require(parts in 1..32 && fingerprint.isNotBlank())
        val db=writableDatabase;db.beginTransaction()
        try {
            val old=latest(scope,customer)
            check(old?.state(now)!=SmsState.SENDING) {"جارٍ إرسال رسالة لهذا الزبون. انتظر نتيجة الإرسال."}
            if(old!=null && !allowRepeat && (old.fingerprint==fingerprint || old.state(now)!=SmsState.SENT)) throw SmsRepeatRequired()
            val token=UUID.randomUUID().toString()
            val results=JSONArray().apply {repeat(parts) {put(org.json.JSONObject.NULL)}}
            val values=ContentValues().apply {put("token",token);put("owner",scope.owner);put("pharmacy",scope.pharmacy);put("customer",customer)
                put("fingerprint",fingerprint);put("parts",parts);put("results",results.toString());put("started",now)}
            check(db.insertOrThrow("attempts",null,values)!=-1L);db.setTransactionSuccessful()
            return SmsAttempt(token,fingerprint,parts,List(parts) {null},now,null)
        } finally {db.endTransaction();SmsUpdates.revision.update {it+1}}
    }
    fun result(token: String,part: Int,code: Int,now: Long=System.currentTimeMillis()) {
        val db=writableDatabase;db.beginTransaction()
        try {
            val old=find("token=?",arrayOf(token)) ?: return
            if(part !in 0 until old.parts || old.results[part]!=null) return
            val results=old.results.toMutableList().apply {this[part]=code}
            val values=ContentValues().apply {put("results",JSONArray(results).toString())
                if(results.all {it==Activity.RESULT_OK}) put("sent_at",now)}
            db.update("attempts",values,"token=?",arrayOf(token));db.setTransactionSuccessful()
        } finally {db.endTransaction();SmsUpdates.revision.update {it+1}}
    }
    fun failedBeforeConfirmation(token: String) {
        // A framework exception can occur after accepting a part: keep it uncertain rather than retrying it.
        writableDatabase.execSQL("UPDATE attempts SET uncertain=1 WHERE token=?",arrayOf(token));SmsUpdates.revision.update {it+1}
    }
}

/** Explicit, non-exported, immutable PendingIntent: other apps cannot forge a success mark. */
class FollowupSmsSentReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action!=ACTION) return
        val uri=intent.data ?: return
        if(uri.scheme!="raad-sms" || uri.host!="sent") return
        val token=uri.pathSegments.getOrNull(0) ?: return
        val part=uri.pathSegments.getOrNull(1)?.toIntOrNull() ?: return
        runCatching {UUID.fromString(token);SmsAttemptStore(context).use {it.result(token,part,resultCode)}}
    }
    companion object {internal const val ACTION="com.radwan.raadpharmacy.SMS_SENT_V27"}
}

internal object FollowupSms {
    val permissions=arrayOf(Manifest.permission.READ_PHONE_STATE,Manifest.permission.SEND_SMS)
    fun permitted(context: Context)=permissions.all {ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED}
    @SuppressLint("MissingPermission")
    fun sims(context: Context): List<SmsSim> {
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED) return emptyList()
        return context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
            .filter {it.simSlotIndex>=0}.map {SmsSim(it.subscriptionId,it.simSlotIndex,"الشريحة ${it.simSlotIndex+1} • ${it.carrierName}")}.sortedBy {it.slot}
    }
    fun fingerprint(case: FollowupReminderStore.Case,phone: String,text: String): String = MessageDigest.getInstance("SHA-256")
        .digest((case.fingerprint+"\u0000"+phone+"\u0000"+text).toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
    fun selected(settings: FollowupShareSettings,available: List<SmsSim>): SmsSim {
        val saved=settings.selectedSim() ?: error("اختر شريحة SMS من الإعدادات الإضافية أولاً.")
        return available.firstOrNull {it.id==saved.first && it.slot==saved.second}
            ?: error("الشريحة المحفوظة غير متاحة. أعد اختيار الشريحة من الإعدادات؛ لم يُرسل على شريحة أخرى.")
    }
    fun send(context: Context,owner: FollowupIdentity,case: FollowupReminderStore.Case,phone: String,text: String,allowRepeat: Boolean=false): String =
        dispatch(context,owner,case,phone,text,allowRepeat,AndroidSmsPlatform(context)) {ManualFollowupStore.get(context).identity()}

    internal fun dispatch(context: Context,owner: FollowupIdentity,case: FollowupReminderStore.Case,phone: String,text: String,
        allowRepeat: Boolean=false,platform: SmsPlatform,currentIdentity: ()->FollowupIdentity?): String {
        check(currentIdentity()==owner) {"تغيّر حساب الصيدلية. أعد فتح المتابعة."}
        check(platform.permitted()) {"يلزم السماح بإرسال SMS والوصول إلى معلومات الشرائح."}
        check(platform.supported()) {"هذا الجهاز لا يدعم إرسال SMS."}
        val recipient=validatedIraqiWhatsappPhone(phone)?.let {"+$it"} ?: error("أضف رقم موبايل عراقي صحيح للزبون أولاً.")
        require(text.isNotBlank() && text.length<=8000) {"نص الرسالة فارغ أو طويل جداً."}
        val sim=selected(FollowupShareSettings(context),platform.sims())
        val parts=platform.parts(sim.id,text)
        require(parts.size in 1..32) {"الرسالة طويلة جداً؛ اختصر قالب الرسالة قبل الإرسال."}
        SmsAttemptStore(context).use {store->
            val attempt=store.begin(owner,case.customerId,fingerprint(case,recipient,text),parts.size,allowRepeat)
            try {
                check(currentIdentity()==owner) {"تغيّر حساب الصيدلية. أعد فتح المتابعة."}
                val callbacks=ArrayList(parts.indices.map {part->PendingIntent.getBroadcast(context,0,
                    Intent(context,FollowupSmsSentReceiver::class.java).setAction(FollowupSmsSentReceiver.ACTION)
                        .setData(Uri.parse("raad-sms://sent/${attempt.token}/$part")),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)})
                platform.send(sim.id,recipient,parts,callbacks)
            } catch(error: Exception) {store.failedBeforeConfirmation(attempt.token);throw error}
            return attempt.token
        }
    }
}

internal interface SmsPlatform {
    fun permitted(): Boolean
    fun supported(): Boolean
    fun sims(): List<SmsSim>
    fun parts(subscription: Int,text: String): ArrayList<String>
    fun send(subscription: Int,recipient: String,parts: ArrayList<String>,callbacks: ArrayList<PendingIntent>)
}
internal class AndroidSmsPlatform(private val context: Context): SmsPlatform {
    override fun permitted()=FollowupSms.permitted(context)
    override fun supported()=context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
    override fun sims()=FollowupSms.sims(context)
    private fun manager(subscription: Int): SmsManager = if(Build.VERSION.SDK_INT>=31)
        context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscription)
        else @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(subscription)
    override fun parts(subscription: Int,text: String)=manager(subscription).divideMessage(text)
    @SuppressLint("MissingPermission") // dispatch() checks SEND_SMS and READ_PHONE_STATE before reaching this adapter.
    override fun send(subscription: Int,recipient: String,parts: ArrayList<String>,callbacks: ArrayList<PendingIntent>) {
        manager(subscription).sendMultipartTextMessage(recipient,null,parts,callbacks,null)
    }
}
