package com.radwan.raadpharmacy.ui.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding\nimport androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.radwan.raadpharmacy.BuildConfig
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.update.AppUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun AppUpdateScreenV314(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var showCurrentInfo by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<AppUpdateManager.Release?>(null) }
    var pending by remember { mutableStateOf(AppUpdateManager.pending(context)) }
    var downloadStatus by remember { mutableStateOf<AppUpdateManager.Progress?>(null) }
    var checked by remember { mutableStateOf(false) }
    var verified by remember { mutableStateOf<File?>(null) }
    var allowed by remember { mutableStateOf(AppUpdateManager.canInstall(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)
    DisposableEffect(lifecycleOwner) {
        val listener = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allowed = AppUpdateManager.canInstall(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(listener)
        onDispose { lifecycleOwner.lifecycle.removeObserver(listener) }
    }
    LaunchedEffect(pending?.id) {
        val session = pending ?: return@LaunchedEffect
        while (isActive) {
            val state = withContext(Dispatchers.IO) { AppUpdateManager.progress(context, session) }
            downloadStatus = state
            if (state.failed != null) {
                problem = state.failed
                AppUpdateManager.reset(context)
                pending = null
                break
            }
            if (state.finished) {
                try {
                    verified = AppUpdateManager.validateApk(context, session)
                    message = "اكتمل التنزيل والتحقق من البصمة والشهادة واسم الحزمة. التثبيت فوق النسخة الحالية آمن من حيث التوافق."
                    problem = null
                } catch (ex: Exception) {
                    problem = ex.message ?: "ملف التحديث غير متوافق."
                    AppUpdateManager.reset(context)
                    pending = null
                }
                break
            }
            delay(650L)
        }
    }
    Scaffold(topBar = { ScreenTopBar("تحديث التطبيق", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("دفتر صيدلية رعد", style = MaterialTheme.typography.titleLarge)
                        Text("النسخة المثبتة: ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = { showCurrentInfo = !showCurrentInfo }) {
                            androidx.compose.material3.Icon(Icons.Rounded.Info, contentDescription = null)
                            Text("  معلومات النسخة الحالية")
                        }
                        if (showCurrentInfo) {
                            Text("رقم البناء: ${BuildConfig.VERSION_CODE}")
                            Text("معرّف التطبيق: ${BuildConfig.APPLICATION_ID}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Android: ${Build.VERSION.RELEASE}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("تبقى البيانات والمزامنة والحسابات محفوظة عند تحديث متوافق.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("البحث عن إصدار جديد", style = MaterialTheme.typography.titleMedium)
                        Text("يفحص الإصدارات الرسمية في GitHub، وليس الملفات المؤقتة أو التجريبية.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(
                            onClick = {
                                scope.launch {
                                    checking = true
                                    checked = false
                                    problem = null
                                    message = null
                                    release = null
                                    try {
                                        release = AppUpdateManager.checkForUpdate(context)
                                        checked = true
                                    } catch (e: Exception) {
                                        problem = e.message ?: "تعذر البحث عن تحديث."
                                    } finally { checking = false }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !checking && !starting
                        ) {
                            if (checking) CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp)
                            else androidx.compose.material3.Icon(Icons.Rounded.Refresh, null)
                            Text("  ${if (checking) "جارٍ البحث..." else "البحث عن نسخ جديدة"}")
                        }
                        if (checked && release == null) {
                            Text("لا توجد حالياً نسخة رسمية أحدث ومتوافقة منشورة.",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        release?.let { found ->
                            HorizontalDivider()
                            Text("تحديث متوفر: ${found.tag}",
                                style = MaterialTheme.typography.titleMedium)
                            Text("الحجم: ${"%.1f".format(found.size / 1024.0 / 1024.0)} MB",
                                style = MaterialTheme.typography.bodySmall)
                            if (found.notes.isNotBlank()) Text(
                                found.notes,
                                style = MaterialTheme.typography.bodySmall)
                            if (pending == null && verified == null) {
                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !starting,
                                    onClick = {
                                        starting = true
                                        problem = null
                                        scope.launch {
                                            try {
                                                pending = withContext(Dispatchers.IO) {
                                                    AppUpdateManager.begin(context, found)
                                                }
                                            } catch (e: Exception) {
                                                problem = e.message ?: "تعذر بدء التنزيل."
                                            } finally { starting = false }
                                        }
                                    }
                                ) {
                                    androidx.compose.material3.Icon(Icons.Rounded.Download, null)
                                    Text("  تنزيل التحديث")
                                }
                            }
                        }
                    }
                }
            }
            if (pending != null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("تنزيل الإصدار ${pending?.tag}",
                            style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(
                            progress = { (downloadStatus?.percent ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            if (verified != null) "تم فحص التحديث بنجاح"
                            else "جارٍ التنزيل: ${downloadStatus?.percent ?: 0}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            if (verified != null) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("التحديث جاهز للتثبيت", style = MaterialTheme.typography.titleMedium)
                            Text("سيعرض أندرويد نافذة تأكيد لتثبيت النسخة فوق الحالية. لا تحذف التطبيق.",
                                style = MaterialTheme.typography.bodySmall)
                            if (!allowed) {
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        try { context.startActivity(AppUpdateManager.permissionIntent(context)) }
                                        catch (e: Exception) { problem = "تعذر فتح إذن التثبيت: ${e.message}" }
                                    }
                                ) { Text("السماح بالتثبيت من هذا التطبيق") }
                            }
                            Button(
                                onClick = {
                                    try { AppUpdateManager.install(context, requireNotNull(verified)) }
                                    catch (e: Exception) { problem = e.message ?: "تعذر فتح مثبّت Android." }
                                },
                                enabled = allowed,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                androidx.compose.material3.Icon(Icons.Rounded.InstallMobile, null)
                                Text("  تثبيت التحديث فوق النسخة الحالية")
                            }
                        }
                    }
                }
            }
            message?.let { text -> item { Text(text, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary) } }
            problem?.let { text ->
                item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text, color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall)
                            if (pending == null) OutlinedButton(onClick = {
                                problem = null
                                verified = null
                                downloadStatus = null
                            }) { Text("حسنًا") }
                        }
                    }
                }
            }
            item {
                Text(
                    "الحماية: لا يُقبل أي تحديث قبل مطابقة مفتاح التوقيع، واسم الحزمة، ورقم الإصدار، وبصمة SHA-256. لا توجد إزالة تلقائية أو مساس بقاعدة بيانات الزبائن.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
