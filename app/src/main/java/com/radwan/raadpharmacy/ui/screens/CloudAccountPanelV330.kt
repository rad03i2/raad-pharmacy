package com.radwan.raadpharmacy.ui.screens

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.compose.runtime.produceState
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.rounded.Vibration
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import com.radwan.raadpharmacy.util.formatDate
import com.radwan.raadpharmacy.util.formatTime
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.cloud.CloudTeamMember
import com.radwan.raadpharmacy.cloud.CloudTeamSnapshot
import com.radwan.raadpharmacy.cloud.CloudTeamStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun CloudAccountPanelV330() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { CloudTeamStore(context) }
    var snapshot by remember { mutableStateOf(CloudTeamSnapshot(null, emptyList())) }
    var loading by remember { mutableStateOf(true) }
    var uploading by remember { mutableStateOf(false) }
    var sendingTo by remember { mutableStateOf(emptySet<String>()) }
    var alertMessage by remember { mutableStateOf<String?>(null) }
    val lastSent = remember { mutableMapOf<String, Long>() }
    val lifecycleOwner = LocalLifecycleOwner.current

    suspend fun refresh() {
        try { snapshot = withContext(Dispatchers.IO) { store.load() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep the last successful snapshot while offline. */ }
        finally { loading = false }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && !uploading) {
            scope.launch {
                uploading = true
                runCatching {
                    val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                        ?: error("تعذر قراءة الصورة")
                    withContext(Dispatchers.IO) { store.uploadMyAvatar(bytes) }
                }
                refresh()
                uploading = false
            }
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            refresh()
            while (true) { delay(30_000L); refresh() }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            snapshot.current?.let { current ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier.clickable(enabled = !uploading) {
                            picker.launch("image/*")
                        }
                    ) {
                        TeamAvatar(current.avatarFile, 72.dp)
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .size(26.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (uploading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Icon(
                                        Icons.Rounded.AddAPhoto,
                                        contentDescription = "تغيير الصورة",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            current.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "الحساب الحالي",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            if (current.isOnline) "نشط الآن" else lastSeenLabel(current.lastSeenAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (current.isOnline) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            if (loading && snapshot.current == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }

            if (snapshot.others.isNotEmpty()) {
                Text(
                    "المستخدمون الآخرون",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                snapshot.others.forEach { member ->
                    TeamMemberRow(member, member.id in sendingTo) {
                        val last = lastSent[member.id] ?: 0L
                        if (System.currentTimeMillis() - last < 30_000L) {
                            alertMessage = "انتظر 30 ثانية قبل تنبيه المستخدم مرة أخرى."
                        } else {
                            sendingTo = sendingTo + member.id
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { store.sendAlert(member.id) }
                                    lastSent[member.id] = System.currentTimeMillis()
                                    alertMessage = "تم إرسال طلب التنبيه إلى " + member.displayName
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (error: Exception) {
                                    alertMessage = if (error.message.orEmpty().contains("ALERT_RATE_LIMIT"))
                                        "انتظر 30 ثانية قبل إرسال تنبيه آخر."
                                    else "تعذر إرسال التنبيه. تحقق من اتصال الإنترنت ثم حاول مرة أخرى."
                                } finally { sendingTo = sendingTo - member.id }
                            }
                        }
                    }
                }
            }
            alertMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun TeamMemberRow(member: CloudTeamMember, sending: Boolean, onAlert: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box {
            TeamAvatar(member.avatarFile, 48.dp)
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(13.dp),
                shape = CircleShape,
                color = if (member.isOnline) Color(0xFF20B15A) else MaterialTheme.colorScheme.outline,
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface)
            ) {}
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                member.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (member.isOnline) "نشط الآن" else lastSeenLabel(member.lastSeenAt),
                style = MaterialTheme.typography.bodySmall,
                color = if (member.isOnline) {
                    Color(0xFF15803D)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        IconButton(onClick = onAlert, enabled = !sending) {
            if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Vibration, contentDescription = "تنبيه " + member.displayName,
                tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun TeamAvatar(file: File?, size: Dp) {
    val bitmap by produceState<Bitmap?>(null, file?.absolutePath, file?.lastModified()) {
        value = withContext(Dispatchers.IO) {
            file?.takeIf(File::isFile)?.let { image ->
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(image.absolutePath, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
                    BitmapFactory.decodeFile(image.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                }.getOrNull()
            }
        }
    }

    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        val loadedBitmap = bitmap
        if (loadedBitmap != null) {
            Image(
                bitmap = loadedBitmap.asImageBitmap(),
                contentDescription = "صورة المستخدم",
                modifier = Modifier.clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(size * 0.48f)
                )
            }
        }
    }
}

private fun lastSeenLabel(lastSeenAt: Long?): String {
    if (lastSeenAt == null) return "لم يظهر بعد"
    val diff = (System.currentTimeMillis() - lastSeenAt).coerceAtLeast(0L)
    val minute = 60_000L
    return when {
        diff < minute -> "آخر ظهور الآن"
        diff < 60L * minute -> "آخر ظهور قبل " + (diff / minute) + " دقيقة"
        diff < 24L * 60L * minute -> "آخر ظهور قبل " + (diff / (60L * minute)) + " ساعة"
        else -> {
            "آخر ظهور " + formatDate(lastSeenAt) + " • " + formatTime(lastSeenAt)
        }
    }
}
