package com.radwan.raadpharmacy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.cloud.CloudNotificationEventRow
import com.radwan.raadpharmacy.cloud.messageTimestamp
import com.radwan.raadpharmacy.ui.components.rememberLedgerFlingBehavior
import com.radwan.raadpharmacy.util.formatDate
import com.radwan.raadpharmacy.util.formatTime

@Composable
internal fun TeamMessageActions(name: String, sendingAlert: Boolean, hasUnread: Boolean,
    onAlert: () -> Unit, onCompose: () -> Unit, onRead: () -> Unit) {
    Row {
        IconButton(onClick = onAlert, enabled = !sendingAlert) {
            if (sendingAlert) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Vibration, "تنبيه $name", tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onCompose) {
            Icon(Icons.AutoMirrored.Rounded.Send, "إرسال رسالة إلى $name", tint = MaterialTheme.colorScheme.primary)
        }
        if (hasUnread) IconButton(onClick = onRead) {
            BadgedBox(badge = { Badge(Modifier.size(7.dp), containerColor = Color(0xFFE23C48)) }) {
                Icon(Icons.Rounded.MarkEmailUnread, "رسائل جديدة من $name", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
internal fun ComposeTeamMessageDialog(name: String, text: String, sending: Boolean, error: String?,
    onText: (String) -> Unit, onDismiss: () -> Unit, onSend: () -> Unit) {
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("رسالة إلى $name") },
        text = {
            LaunchedEffect(Unit) { focus.requestFocus() }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = onText, enabled = !sending,
                    modifier = Modifier.focusRequester(focus), minLines = 3, maxLines = 5,
                    placeholder = { Text("اكتب رسالتك") }, supportingText = { Text("${text.length}/500") },
                    isError = text.length > 500)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = onSend, enabled = !sending && text.trim().isNotEmpty() && text.length <= 500) {
                if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("إرسال")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !sending) { Text("إلغاء") } }
    )
}

@Composable
internal fun ReadTeamMessagesDialog(name: String, messages: List<CloudNotificationEventRow>, reading: Boolean,
    onDismiss: () -> Unit, onRead: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!reading) onDismiss() }, title = { Text("رسائل من $name") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp), flingBehavior = rememberLedgerFlingBehavior(),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(messages, key = { it.id }, contentType = { "message" }) { message ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(message.messageBody.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                        Text(formatDate(messageTimestamp(message)) + " • " + formatTime(messageTimestamp(message)),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onRead, enabled = !reading) {
            if (reading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("تمت القراءة")
        } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !reading) { Text("إغلاق") } })
}
