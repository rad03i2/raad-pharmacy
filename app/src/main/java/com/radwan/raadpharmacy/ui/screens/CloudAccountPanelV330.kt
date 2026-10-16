package com.radwan.raadpharmacy.ui.screens

import android.graphics.Bitmap
import com.radwan.raadpharmacy.cloud.CloudTeamRuntime
import com.radwan.raadpharmacy.cloud.TeamLoadingState
import com.radwan.raadpharmacy.cloud.CloudTeamAvatars
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.radwan.raadpharmacy.cloud.CloudMessagingFeedback
import kotlinx.coroutines.TimeoutCancellationException
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
import androidx.compose.material3.IconButton
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
import com.radwan.raadpharmacy.cloud.CloudTeamMessageStore
import com.radwan.raadpharmacy.cloud.CloudNotificationEventRow
import com.radwan.raadpharmacy.cloud.CloudTeamStore
import java.util.UUID
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
    val updates = remember(store) { store.snapshots() }
    val snapshot by updates.collectAsStateWithLifecycle(initialValue = store.cachedSnapshot())
    val automatic = remember(context) { CloudTeamRuntime.start(context) }
    val teamState by automatic.state.collectAsStateWithLifecycle()
    var uploading by remember { mutableStateOf(false) }
    var avatarTargetId by remember { mutableStateOf<String?>(null) }
    var sendingTo by remember { mutableStateOf(emptySet<String>()) }
    var alertMessage by remember { mutableStateOf<String?>(null) }
    val messagesStore = remember(context) { CloudTeamMessageStore(context) }
    val messageUpdates = remember(messagesStore) { messagesStore.snapshots() }
    val unread by messageUpdates.collectAsStateWithLifecycle(initialValue = messagesStore.current())
    var composeTo by remember { mutableStateOf<CloudTeamMember?>(null) }
    var messageText by remember { mutableStateOf("") }
    var messageId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var messageSending by remember { mutableStateOf(false) }
    var messageError by remember { mutableStateOf<String?>(null) }
    var readFrom by remember { mutableStateOf<CloudTeamMember?>(null) }
    var displayedMessages by remember { mutableStateOf(emptyList<CloudNotificationEventRow>()) }
    var reading by remember { mutableStateOf(false) }
    val lastSent = remember { mutableMapOf<String, Long>() }
    val lifecycleOwner = LocalLifecycleOwner.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val targetId = avatarTargetId ?: snapshot.current?.id
        if (uri != null && targetId != null && !uploading) {
            scope.launch {
                uploading = true
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } ?: error("تعذر قراءة الصورة")
                    withContext(Dispatchers.IO) { store.uploadAvatar(targetId, bytes) }
                    alertMessage = "تم تغيير صورة المستخدم بنجاح."
                    withContext(Dispatchers.IO) { store.load(force = true) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    alertMessage = "تعذر تغيير الصورة. تحقق من الاتصال ثم أعد المحاولة."
                } finally {
                    uploading = false
                    avatarTargetId = null
                }
            }
        } else {
            avatarTargetId = null
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            CloudTeamRuntime.request(context)
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { withContext(Dispatchers.IO) { messagesStore.refresh() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Retain the offline inbox. */ }
        }
    }
    composeTo?.let { recipient ->
        ComposeTeamMessageDialog(recipient.displayName, messageText, messageSending, messageError,
            onText = { messageText = it; messageId = UUID.randomUUID().toString(); messageError = null },
            onDismiss = { composeTo = null }, onSend = {
                if (!messageSending) {
                    messageSending = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { messagesStore.send(recipient.id, messageId, messageText) }
                            alertMessage = "تم حفظ الرسالة، ويجري توصيلها إلى " + recipient.displayName
                            composeTo = null
                            messageText = ""
                        } catch (error: TimeoutCancellationException) {
                            messageError = CloudMessagingFeedback.forFailure(context, error)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            messageError = CloudMessagingFeedback.forFailure(context, error)
                        } finally { messageSending = false }
                    }
                }
            })
    }
    readFrom?.let { sender ->
        ReadTeamMessagesDialog(sender.displayName, displayedMessages, reading,
            onDismiss = { readFrom = null }, onRead = {
                if (!reading) {
                    reading = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { messagesStore.markRead(displayedMessages.map { it.id }.toSet()) }
                            readFrom = null
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { alertMessage = "تعذر حفظ حالة القراءة. أعد المحاولة." }
                        finally { reading = false }
                    }
                }
            })
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
                            avatarTargetId = current.id
                            picker.launch("image/*")
                        }
                    ) {
                        TeamAvatar(current.avatarFile, current.avatarRevision, 72.dp)
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

            if (snapshot.current == null) {
                TeamAutomaticLoading(teamState)
            }

            if (snapshot.others.isNotEmpty()) {
                Text(
                    "المستخدمون الآخرون",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                snapshot.others.forEach { member ->
                    TeamMemberRow(member, member.id in sendingTo, uploading,
                        hasUnread = unread.any { it.actorUserId == member.id },
                        onChangeAvatar = {
                            if (!uploading) {
                                avatarTargetId = member.id
                                picker.launch("image/*")
                            }
                        },
                        onCompose = {
                            composeTo = member; messageText = ""; messageError = null
                            messageId = UUID.randomUUID().toString()
                        },
                        onRead = {
                            displayedMessages = unread.filter { it.actorUserId == member.id }
                            readFrom = member
                        }) {
                        val last = lastSent[member.id] ?: 0L
                        if (System.currentTimeMillis() - last < 30_000L) {
                            alertMessage = "انتظر 30 ثانية قبل تنبيه المستخدم مرة أخرى."
                        } else {
                            sendingTo = sendingTo + member.id
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { store.sendAlert(member.id) }
                                    lastSent[member.id] = System.currentTimeMillis()
                                    alertMessage = "تم حفظ طلب التنبيه، ويجري توصيله إلى " + member.displayName
                                } catch (error: TimeoutCancellationException) {
                                    alertMessage = CloudMessagingFeedback.forFailure(context, error, alert = true)
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (error: Exception) {
                                    alertMessage = CloudMessagingFeedback.forFailure(context, error, alert = true)
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
internal fun TeamAutomaticLoading(state: TeamLoadingState) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state == TeamLoadingState.LOADING) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        Text(when (state) {
            TeamLoadingState.WAITING_FOR_SESSION -> "تجري استعادة جلسة الحساب تلقائيا…"
            TeamLoadingState.RETRYING -> "تجري إعادة الاتصال بالحسابات تلقائيا…"
            else -> "جاري تحميل الحسابات تلقائيا…"
        }, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TeamMemberRow(member: CloudTeamMember, sending: Boolean, uploading: Boolean, hasUnread: Boolean,
    onChangeAvatar: () -> Unit, onCompose: () -> Unit, onRead: () -> Unit, onAlert: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box {
            TeamAvatar(member.avatarFile, member.avatarRevision, 48.dp)
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
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
        IconButton(onClick = onChangeAvatar, enabled = !uploading) {
            Icon(Icons.Rounded.AddAPhoto, contentDescription = "تغيير صورة " + member.displayName,
                tint = MaterialTheme.colorScheme.primary)
        }
        TeamMessageActions(member.displayName, sending, hasUnread, onAlert, onCompose, onRead)
    }
}

@Composable
private fun TeamAvatar(file: File?, revision: Long, size: Dp) {
    val bitmap by produceState<Bitmap?>(CloudTeamAvatars.cached(file, revision), file?.absolutePath, revision) {
        value = withContext(Dispatchers.IO) { CloudTeamAvatars.load(file, revision) }
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
