package com.radwan.raadpharmacy.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

internal data class LedgerNavigationItem(val route: String, val label: String, val icon: ImageVector)

/** Fixed hit targets: the indicator animates only in draw, never the six slots' measurements. */
@Composable
internal fun FloatingLedgerNavigation(items: List<LedgerNavigationItem>, selectedRoute: String,
    onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val selectedIndex = items.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val indicator = animateFloatAsState(selectedIndex.toFloat(), tween(170), label = "navigation-indicator")
    val labelStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    val measurer = rememberTextMeasurer(cacheSize = 12)
    Box(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
        .padding(horizontal = 4.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp),
            color = colors.surface, border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.55f)), shadowElevation = 6.dp) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(4.dp)) {
                val width = maxWidth / items.size
                val barHeight = maxOf(58.dp, (labelStyle.lineHeight.value * density.fontScale + 30f).dp)
                // One measurement per label/width/font, rather than subcomposing during every animation frame.
                val styles = remember(items, width, labelStyle, density.density, density.fontScale) {
                    val available = with(density) { (width - 6.dp).toPx() }
                    items.map { item ->
                        val measured = measurer.measure(item.label, style = labelStyle).size.width.coerceAtLeast(1)
                        labelStyle.copy(fontSize = labelStyle.fontSize * (available / measured).coerceIn(0.25f, 1f))
                    }
                }
                Box(Modifier.fillMaxWidth().height(barHeight)) {
                    Canvas(Modifier.matchParentSize()) {
                        val slot = size.width / items.size
                        val offset = indicator.value * slot
                        val left = if (rtl) size.width - offset - slot else offset
                        drawRoundRect(Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.14f),
                            colors.secondary.copy(alpha = 0.15f)), Offset(left, 0f), Offset(left + slot, size.height)),
                            topLeft = Offset(left, 0f), size = Size(slot, size.height), cornerRadius = CornerRadius(20.dp.toPx()))
                    }
                    Row(Modifier.fillMaxSize().selectableGroup()) {
                        items.forEachIndexed { index, item ->
                            val selected = index == selectedIndex
                            val tint by animateColorAsState(if (selected) colors.primary else colors.onSurfaceVariant,
                                tween(120), label = "navigation-tint")
                            val outline = remember(item.route) { when (item.route) {
                                "home" -> Icons.Outlined.Home
                                "customers" -> Icons.Outlined.Groups
                                "collections" -> Icons.Outlined.AccountBalanceWallet
                                "reports" -> Icons.Outlined.BarChart
                                "medicine_shopping" -> Icons.Outlined.ShoppingBag
                                else -> Icons.Outlined.Settings
                            } }
                            Column(Modifier.weight(1f).fillMaxHeight()
                                .selectable(selected = selected, role = Role.Tab, onClick = { if (!selected) onSelect(item.route) })
                                .semantics { contentDescription = item.label },
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(if (selected) item.icon else outline, null, tint = tint, modifier = Modifier.size(24.dp))
                                Box(Modifier.fillMaxWidth().height((labelStyle.lineHeight.value * density.fontScale).dp),
                                    contentAlignment = Alignment.Center) {
                                    if (selected) Text(item.label, color = tint, style = styles[index], textAlign = TextAlign.Center, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
