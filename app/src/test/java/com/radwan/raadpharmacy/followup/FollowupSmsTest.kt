package com.radwan.raadpharmacy.followup

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.data.*
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35])
class FollowupSmsTest {
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    private val owner=FollowupIdentity("raad","pharmacy")
    private val case=FollowupReminderStore.Case("customer","financial-case")
    private val second=SmsSim(42,1,"الشريحة 2")
    private lateinit var store: SmsAttemptStore
    private class Platform: SmsPlatform {
        var allowed=true;var available=listOf(SmsSim(11,0,"SIM 1"),SmsSim(42,1,"SIM 2"))
        var callbacks= arrayListOf<PendingIntent>();var subscription: Int?=null;var recipient: String?=null;var calls=0
        override fun permitted()=allowed
        override fun supported()=true
        override fun sims()=available
        override fun parts(subscription: Int,text: String)=arrayListOf("نص عربي 1","نص عربي 2","نص عربي 3")
        override fun send(subscription: Int,recipient: String,parts: ArrayList<String>,callbacks: ArrayList<PendingIntent>) {
            this.subscription=subscription;this.recipient=recipient;this.callbacks=callbacks;calls++
        }
    }
    @Before fun open() {
        File(context.noBackupFilesDir,"followup-sms-v27.db").delete()
        context.getSharedPreferences("raad_followup_share_v27",Context.MODE_PRIVATE).edit().clear().commit()
        store=SmsAttemptStore(context);FollowupShareSettings(context).selectSim(second)
    }
    @After fun close() {store.close()}
    private fun send(platform: Platform,allow: Boolean=false)=FollowupSms.dispatch(context,owner,case,"07701234567","تذكير بالدين",allow,platform) {owner}
    @Test fun exactSelectedSimIsUsedAndCheckmarkRequiresEveryRealPendingIntentCallback() {
        val platform=Platform();val token=send(platform)
        assertEquals(42,platform.subscription);assertEquals("+9647701234567",platform.recipient);assertEquals(3,platform.callbacks.size)
        assertEquals(SmsState.SENDING,store.latest(owner,case.customerId)!!.state())
        platform.callbacks[2].send(context,Activity.RESULT_OK,null);shadowOf(Looper.getMainLooper()).idle()
        platform.callbacks[0].send(context,Activity.RESULT_OK,null);shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SmsState.SENDING,store.latest(owner,case.customerId)!!.state())
        platform.callbacks[1].send(context,Activity.RESULT_OK,null);shadowOf(Looper.getMainLooper()).idle()
        val result=store.latest(owner,case.customerId)!!
        assertEquals(token,result.token);assertEquals(SmsState.SENT,result.state());assertNotNull(result.sentAt)
        store.close();store=SmsAttemptStore(context);assertEquals(SmsState.SENT,store.latest(owner,case.customerId)!!.state())
    }
    @Test fun deniedPermissionMissingSelectedSimAndChangedOwnerCannotFallBackToFirstSimOrSendAnything() {
        val denied=Platform().apply {allowed=false}
        try {send(denied);fail()} catch(_: IllegalStateException) {assertEquals(0,denied.calls)}
        val missing=Platform().apply {available=available.take(1)}
        try {send(missing);fail()} catch(_: IllegalStateException) {assertEquals(0,missing.calls)}
        val wrong=Platform()
        try {FollowupSms.dispatch(context,owner,case,"07701234567","text",false,wrong) {owner.copy(owner="ahmad")};fail()}
        catch(_: IllegalStateException) {assertEquals(0,wrong.calls)}
        assertNull(store.latest(owner,case.customerId))
    }
    @Test fun doubleTapTimeoutAndUnknownResultNeverCauseAutomaticResend() {
        val first=store.begin(owner,case.customerId,"case",2,now=1000)
        try {store.begin(owner,case.customerId,"case",2,now=1100);fail()} catch(_: IllegalStateException) {}
        assertEquals(SmsState.UNCERTAIN,first.state(121001))
        try {store.begin(owner,case.customerId,"case",2,now=121001);fail()} catch(_: SmsRepeatRequired) {}
        val second=store.begin(owner,case.customerId,"case",2,allowRepeat=true,now=121001)
        assertNotEquals(first.token,second.token)
    }
    @Test fun partialFailureWrongTokenAndDuplicateCallbacksNeverProduceSuccess() {
        val first=store.begin(owner,case.customerId,"case",3)
        store.result("unknown",0,Activity.RESULT_OK)
        store.result(first.token,0,Activity.RESULT_OK);store.result(first.token,1,4);store.result(first.token,2,Activity.RESULT_OK)
        store.result(first.token,1,Activity.RESULT_OK) // A repeated callback cannot rewrite a recorded failure.
        assertEquals(SmsState.FAILED,store.latest(owner,case.customerId)!!.state());assertNull(store.latest(owner,case.customerId)!!.sentAt)
        assertNull(store.latest(owner.copy(owner="ahmad"),case.customerId))
    }
    @Test fun newPhoneOrTextDoesNotReuseAnOldSentMarkAndImageDefaultsPreserveExistingShares() {
        val original=FollowupSms.fingerprint(case,"+9647701234567","message")
        assertNotEquals(original,FollowupSms.fingerprint(case,"+9647801234567","message"))
        assertNotEquals(original,FollowupSms.fingerprint(case,"+9647701234567","new message"))
        val prefs=FollowupShareSettings(context);assertTrue(prefs.includeImage());prefs.includeImage(false)
        assertFalse(FollowupShareSettings(context).includeImage());assertEquals(42 to 1,prefs.selectedSim())
    }
    @Test fun textOnlyMessagesRetainActualAmountsWithoutClaimingAnAttachmentOrChangingImageTemplate() {
        val person=Customer("customer","زبون تجريبي",openingDebt=900_000L,createdAt=System.currentTimeMillis())
        val account=DebtFollowupEngine.calculate(person,emptyList())
        val image=ReadyMessages.messageFor(context,account)
        val sms=ReadyMessages.textOnlyFor(context,account)
        assertTrue(image.contains("نرفق"));assertFalse(sms.contains("نرفق"));assertTrue(sms.contains("900,000"));assertTrue(sms.contains("تسعمئة ألف"))
    }
    @Test fun olderPhoneSupportUsesLegacyTelephonyFeatureWhileModernDevicesUseMessagingFeature() {
        val manager=shadowOf(context.packageManager)
        manager.setSystemFeature(PackageManager.FEATURE_TELEPHONY,true)
        manager.setSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING,Build.VERSION.SDK_INT>=33)
        assertTrue(AndroidSmsPlatform(context).supported())
        manager.setSystemFeature(PackageManager.FEATURE_TELEPHONY,false)
        manager.setSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING,false)
        assertFalse(AndroidSmsPlatform(context).supported())
    }
}
