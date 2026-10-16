package com.radwan.raadpharmacy.cloud

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.TimeoutCancellationException

/** User-facing diagnoses only. Never render raw server errors, emails or passwords. */
internal object CloudLoginFeedback {
    fun forFailure(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        val descriptions = causes.joinToString(" ") {
            (it.message.orEmpty() + " " + it::class.java.simpleName).lowercase()
        }
        return when {
            causes.any { it is TimeoutCancellationException || it is SocketTimeoutException } ||
                descriptions.contains("timeout") || descriptions.contains("timed out") ->
                "انتهت مهلة الاتصال بخادم تسجيل الدخول. تحقق من الشبكة أو جرّب مرة أخرى."
            descriptions.contains("over_request_rate_limit") ||
                descriptions.contains("too many requests") || descriptions.contains("429") ||
                descriptions.contains("rate limit") ->
                "عدد محاولات الدخول كبير مؤقتًا. حاول لاحقًا دون تكرار المحاولة بسرعة."
            descriptions.contains("email_not_confirmed") || descriptions.contains("email not confirmed") ->
                "الحساب يحتاج تأكيد البريد من مسؤول الصيدلية."
            descriptions.contains("invalid_credentials") ||
                descriptions.contains("invalid login credentials") ||
                descriptions.contains("invalid_grant") ->
                "رفض خادم الحسابات بيانات الدخول. تحقق من اسم المستخدم وكلمة المرور المسجلين لهذا الحساب."
            causes.any { it is UnknownHostException || it is ConnectException || it is IOException } ||
                descriptions.contains("unreachable") || descriptions.contains("connection refused") ||
                descriptions.contains("network") || descriptions.contains("dns") ->
                "تعذر الوصول إلى خادم الحسابات رغم الاتصال بالشبكة. قد يكون الخادم أو مسار الاتصال غير متاح."
            else ->
                "تعذر إكمال تسجيل الدخول لدى الخادم. أعد المحاولة، وإذا استمر الخطأ أبلغ المسؤول عن وقت حدوثه."
        }
    }
}
