package com.radwan.raadpharmacy.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.BuildConfig
import com.radwan.raadpharmacy.cloud.CloudConnectionDiagnostics
import com.radwan.raadpharmacy.cloud.CloudFailureCode
import kotlinx.coroutines.launch

@Composable
internal fun ConnectionDiagnosticsPanel() {
    val context = LocalContext.current
    val failure by CloudConnectionDiagnostics.failure.collectAsStateWithLifecycle()
    val result by CloudConnectionDiagnostics.probeResult.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    Column {
        Text("الإصدار المثبت: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall)
        failure?.let {
            val cause = when (it.code) {
                CloudFailureCode.DNS -> "تعذر تحويل عنوان الخادم إلى عنوان اتصال"
                CloudFailureCode.TLS -> "تعذر إنشاء الاتصال الآمن بالخادم"
                CloudFailureCode.CONNECT -> "تعذر فتح الاتصال بالخادم"
                CloudFailureCode.TIMEOUT -> "انتهت مهلة الطلب؛ تبقى العملية محفوظة لإعادة المحاولة"
                CloudFailureCode.HTTP -> "رد الخادم برفض الطلب"
                CloudFailureCode.SESSION -> "جلسة الحساب تحتاج الاستعادة"
                CloudFailureCode.DECODE -> "وصل رد تعذر قراءته من الخادم"
                else -> "تعذر إكمال الطلب؛ تفاصيل الفحص تساعد على تحديد السبب"
            }
            Text("آخر إخفاق: $cause (${it.code}${it.status?.let { s -> " $s" } ?: ""})",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch { try { CloudConnectionDiagnostics.probe() } finally { busy = false } }
            }) { Text(if (busy) "جارٍ فحص الاتصال…" else "فحص الاتصال") }
            if (failure != null || result != null) TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("فحص اتصال الصيدلية", CloudConnectionDiagnostics.report()))
                copied = true
            }) { Text(if (copied) "نُسخت التفاصيل" else "نسخ تفاصيل الفحص") }
        }
    }
}
