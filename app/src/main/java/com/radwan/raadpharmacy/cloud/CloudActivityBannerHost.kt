package com.radwan.raadpharmacy.cloud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

@Composable
fun CloudActivityBannerHost(
    onCustomerClick: (String) -> Unit = {}
) {
    var current by remember { mutableStateOf<CloudUiEvent?>(null) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        CloudUiEvents.events.collectLatest { event ->
            current = event
            visible = true
            delay(3_000L)
            visible = false
            delay(150L)
            if (current == event) current = null
        }
    }

    val event = current ?: return

    Popup(
        alignment = Alignment.TopCenter,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(
                    animationSpec = tween(130),
                    initialOffsetY = { -it }
                ) + fadeIn(tween(100)),
                exit = slideOutVertically(
                    animationSpec = tween(120),
                    targetOffsetY = { -it }
                ) + fadeOut(tween(100))
            ) {
                CloudActivityBanner(
                    event = event,
                    onClick = {
                        event.customerId
                            ?.takeIf { it.isNotBlank() }
                            ?.let(onCustomerClick)
                    }
                )
            }
        }
    }
}

@Composable
private fun CloudActivityBanner(
    event: CloudUiEvent,
    onClick: () -> Unit
) {
    val icon: ImageVector = when (event.kind) {
        CloudUiEvent.Kind.DEBT -> Icons.Rounded.ReceiptLong
        CloudUiEvent.Kind.PAYMENT -> Icons.Rounded.Payments
        CloudUiEvent.Kind.EDIT -> Icons.Rounded.Edit
        CloudUiEvent.Kind.DELETE -> Icons.Rounded.Delete
        CloudUiEvent.Kind.REFRESH,
        CloudUiEvent.Kind.INFO -> Icons.Rounded.CloudDone
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = !event.customerId.isNullOrBlank(),
                onClick = onClick
            ),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 10.dp,
        shadowElevation = 14.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(23.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    event.actorName?.takeIf { it.isNotBlank() }?.let { actor ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = actor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                Spacer(Modifier.height(3.dp))

                Text(
                    text = event.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.Rounded.CloudDone,
                contentDescription = "تمت المزامنة سحابيًا",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
