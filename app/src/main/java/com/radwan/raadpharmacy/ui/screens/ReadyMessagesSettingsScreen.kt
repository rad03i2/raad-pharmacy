package com.radwan.raadpharmacy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.data.ReadyMessages
import com.radwan.raadpharmacy.ui.components.ScreenTopBar

@Composable
fun ReadyMessagesSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(topBar = { ScreenTopBar("الرسائل الجاهزة", onBack = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    "تُستخدم الرسالة المناسبة للمدة عند مشاركة كشف الحساب على واتساب. " +
                        "المتغيرات: {الزبون}، {المبلغ}، {المبلغ_كتابة}، {الأيام}. " +
                        "يُرسل كشف الحساب يدويًا فقط.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            items(ReadyMessages.Kind.entries) { kind ->
                key(kind) {
                    var text by remember(kind) { mutableStateOf(ReadyMessages.load(context, kind)) }
                    var notice by remember(kind) { mutableStateOf("") }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(kind.title, style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it.take(2000); notice = "" },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 4,
                            label = { Text("نص الرسالة") }
                        )
                        Text(
                            ReadyMessages.render(text, "زبون تجريبي", 50_000, 30),
                            style = MaterialTheme.typography.bodySmall
                        )
                        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = text.isNotBlank(),
                                onClick = {
                                    ReadyMessages.save(context, kind, text)
                                    notice = "تم حفظ الرسالة."
                                }
                            ) { Text("حفظ") }
                            OutlinedButton(onClick = {
                                text = kind.defaultText
                                ReadyMessages.save(context, kind, text)
                                notice = "تمت استعادة النص الافتراضي."
                            }) { Text("الافتراضي") }
                        }
                        if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
