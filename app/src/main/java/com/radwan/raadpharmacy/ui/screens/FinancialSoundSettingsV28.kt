package com.radwan.raadpharmacy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.notifications.FinancialFeedbackSettings

@Composable
fun FinancialSoundSettingsCardV28(vm: PharmacyLedgerViewModel) {
    var settings by remember { mutableStateOf(vm.financialFeedbackSettings()) }

    FinancialSoundSettingsContent(
        settings = settings,
        onOperationSoundEnabledChange = { enabled ->
            vm.setOperationSoundEnabled(enabled)
            settings = vm.financialFeedbackSettings()
        }
    )
}

@Composable
internal fun FinancialSoundSettingsContent(
    settings: FinancialFeedbackSettings,
    onOperationSoundEnabledChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "صوت اكتمال العملية",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (settings.operationSoundEnabled) {
                        "يعمل بعد نجاح تسجيل الدين أو التحصيل."
                    } else {
                        "متوقف. إشعارات التطبيق السحابية تبقى فعّالة بصوتها المعتاد."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = settings.operationSoundEnabled,
                onCheckedChange = onOperationSoundEnabledChange,
                modifier = Modifier.testTag("operation_sound_toggle")
            )
        }
    }
}
