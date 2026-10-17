package com.radwan.raadpharmacy.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import io.github.jan.supabase.exceptions.RestException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.TimeoutCancellationException

/** Classify locally; never log exception text, tokens or message contents. */
internal object CloudMessagingFeedback {
    enum class Kind { OFFLINE, SERVER, TIMEOUT, SESSION, PERMISSION, DEVICE, RATE, UNKNOWN }

    fun kind(error: Throwable, online: Boolean = true): Kind {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        val text = causes.joinToString(" ") { it.message.orEmpty() + " " + it.javaClass.simpleName }.lowercase()
        val status = causes.filterIsInstance<RestException>().firstOrNull()?.statusCode
        return when {
            !online -> Kind.OFFLINE
            status == 429 || "rate_limit" in text || "429" in text -> Kind.RATE
            "device_invalid" in text -> Kind.DEVICE
            status == 401 || "401" in text || "jwt" in text || "auth_required" in text || "session" in text -> Kind.SESSION
            status == 403 || "42501" in text || "403" in text || "not_allowed" in text || "recipient_invalid" in text -> Kind.PERMISSION
            status == 408 || causes.any { it is TimeoutCancellationException || it is SocketTimeoutException } || "timeout" in text -> Kind.TIMEOUT
            (status ?: 0) in 500..599 || causes.any { it is IOException } || "connect" in text || "dns" in text -> Kind.SERVER
            else -> Kind.UNKNOWN
        }
    }

    fun forFailure(context: Context, error: Throwable, alert: Boolean = false): String {
        if (error.isCreditBlockedFailure()) return CustomerCreditStore.BLOCKED_MESSAGE
        val caps = context.getSystemService(ConnectivityManager::class.java).let { it.getNetworkCapabilities(it.activeNetwork) }
        return when (kind(error, caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)) {
            Kind.OFFLINE -> "لا تتوفر شبكة حاليًا. أعد المحاولة بعد الاتصال."
            Kind.SERVER -> "تعذر الوصول إلى خادم الصيدلية. أعد المحاولة بعد قليل."
            Kind.TIMEOUT -> "انتهت مهلة تأكيد الحفظ. أعد المحاولة بالطلب نفسه لتجنب التكرار."
            Kind.SESSION -> "تعذر تجديد جلسة الحساب. أعد المحاولة، وإذا استمر الخطأ سجّل الدخول مجددًا."
            Kind.PERMISSION -> "رفض الخادم صلاحية الإرسال لهذا الحساب أو المستلم. أبلغ مسؤول الصيدلية."
            Kind.DEVICE -> "لم يكتمل تسجيل هذا الجهاز لدى الخادم. أعد المحاولة."
            Kind.RATE -> if (alert) "انتظر 30 ثانية قبل إرسال تنبيه آخر." else "انتظر 3 ثوانٍ قبل إرسال رسالة أخرى."
            Kind.UNKNOWN -> "تعذر تأكيد الحفظ لدى الخادم. أعد المحاولة، وأبلغ المسؤول بوقت الخطأ إذا استمر."
        }
    }

    fun record(path: String, error: Throwable) {
        CloudConnectionDiagnostics.record(path, error)
        Log.w("RaadMessaging", "path=$path kind=${kind(error)} at=${System.currentTimeMillis()}")
    }
}

internal fun Throwable.isCreditBlockedFailure(): Boolean = generateSequence(this) { it.cause }.take(8)
    .any { it.message?.contains("CUSTOMER_CREDIT_BLOCKED") == true }
