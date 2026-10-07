package com.radwan.raadpharmacy.ui.screens

import android.graphics.BitmapFactory
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

    suspend fun refresh() {
        runCatching { store.load() }
            .onSuccess { snapshot = it }
        loading = false
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && !uploading) {
            scope.launch {
                uploading = true
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("تعذر قراءة الصورة")
                    store.uploadMyAvatar(bytes)
                }
                refresh()
                uploading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        while (true) {
            delay(12_000L)
            refresh()
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
                    TeamMemberRow(member)
                }
            }
        }
    }
}

@Composable
private fun TeamMemberRow(member: CloudTeamMember) {
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
    }
}

@Composable
private fun TeamAvatar(file: File?, size: Dp) {
    val bitmap = remember(file?.absolutePath, file?.lastModified()) {
        file?.takeIf { it.isFile }?.let {
            runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull()
        }
    }

    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
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
            val formatter = DateTimeFormatter.ofPattern("d MMM • HH:mm", Locale("ar", "IQ"))
            "آخر ظهور " + Instant.ofEpochMilli(lastSeenAt)
                .atZone(ZoneId.systemDefault())
                .format(formatter)
        }
    }
}
