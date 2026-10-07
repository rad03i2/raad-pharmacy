package com.radwan.raadpharmacy.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal data class LedgerNavigationItem(val route: String, val label: String, val icon: ImageVector)

/** One moving indicator; a shared transition keeps the slots and indicator in step. */
@Composable
internal fun FloatingLedgerNavigation(
    items: List<LedgerNavigationItem>, selectedRoute: String, onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val selectedIndex = items.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier.fillMaxWidth()
        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
        .padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp), color = colors.surface,
            border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.55f)),
            shadowElevation = 9.dp) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(6.dp)) {
                val activeWidth = minOf(maxWidth * 1.9f, maxWidth - 48.dp * (items.size - 1))
                    .coerceAtLeast(maxWidth / items.size)
                val inactiveWidth = if (items.size > 1) (maxWidth - activeWidth) / (items.size - 1) else maxWidth
                val compact = maxWidth < 296.dp || density.fontScale * labelStyle.fontSize.value / 13f > 1.25f
                val barHeight = if (compact) maxOf(56.dp, (labelStyle.lineHeight.value * density.fontScale + 29f).dp) else 52.dp
                val transition = updateTransition(selectedIndex, label = "ledger-navigation")
                val start by transition.animateDp(transitionSpec = {
                    spring(dampingRatio = 0.88f, stiffness = 550f)
                }, label = "indicator-position") { index -> inactiveWidth * index }
                val slots = items.mapIndexed { index, _ ->
                    transition.animateFloat(transitionSpec = {
                        spring(dampingRatio = 0.88f, stiffness = 550f)
                    }, label = "slot-$index") { if (it == index) 1f else 0f }
                }
                Box(Modifier.fillMaxWidth().height(barHeight)) {
                    Canvas(Modifier.matchParentSize()) {
                        val width = activeWidth.toPx()
                        val left = if (rtl) size.width - start.toPx() - width else start.toPx()
                        drawRoundRect(
                            brush = Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.14f), colors.secondary.copy(alpha = 0.15f)),
                                start = Offset(left, 0f), end = Offset(left + width, size.height)),
                            topLeft = Offset(left, 1.dp.toPx()), size = Size(width, size.height - 2.dp.toPx()),
                            cornerRadius = CornerRadius(20.dp.toPx()))
                    }
                    Layout(modifier = Modifier.fillMaxSize().selectableGroup(), content = {
                        items.forEachIndexed { index, item ->
                            LedgerNavigationButton(item, index, index == selectedIndex, compact, onSelect)
                        }
                    }) { measurables, constraints ->
                        val progress = slots.map { it.value.coerceIn(0f, 1f) }
                        val sum = progress.sum().coerceAtLeast(0.001f)
                        var used = 0
                        val widths = progress.mapIndexed { index, value ->
                            val width = if (index == items.lastIndex) constraints.maxWidth - used else
                                (inactiveWidth.toPx() + (activeWidth - inactiveWidth).toPx() * value / sum).roundToInt()
                            used += width
                            width.coerceAtLeast(0)
                        }
                        val placeables = measurables.mapIndexed { index, measurable ->
                            measurable.measure(androidx.compose.ui.unit.Constraints.fixed(widths[index], constraints.maxHeight))
                        }
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            var x = 0
                            placeables.forEach { placeable -> placeable.placeRelative(x, 0); x += placeable.width }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LedgerNavigationButton(item: LedgerNavigationItem, index: Int, selected: Boolean,
    compact: Boolean, onSelect: (String) -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(100), label = "navigation-press")
    val tint by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(190), label = "navigation-color")
    val outline = remember(index) { when (index) {
        0 -> Icons.Outlined.Home
        1 -> Icons.Outlined.Groups
        2 -> Icons.Outlined.AccountBalanceWallet
        3 -> Icons.Outlined.BarChart
        else -> Icons.Outlined.Tune
    } }
    val style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))
        .selectable(selected = selected, role = Role.Tab, interactionSource = interactions,
            indication = androidx.compose.material3.ripple(), onClick = { if (!selected) onSelect(item.route) })
        .semantics { contentDescription = item.label }
        .graphicsLayer { scaleX = pressScale; scaleY = pressScale }, contentAlignment = Alignment.Center) {
        val icon: @Composable () -> Unit = {
            Crossfade(selected, animationSpec = tween(180), label = "navigation-symbol") { filled ->
                Icon(if (filled) item.icon else outline, null, tint = tint, modifier = Modifier.size(24.dp))
            }
        }
        if (compact) {
            Column(Modifier.padding(horizontal = 5.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                icon()
                AnimatedVisibility(selected, enter = fadeIn(tween(160)) + androidx.compose.animation.expandVertically(tween(230)),
                    exit = fadeOut(tween(80)) + androidx.compose.animation.shrinkVertically(tween(210))) {
                    Text(item.label, color = tint, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                icon()
                AnimatedVisibility(selected,
                    enter = fadeIn(tween(170, delayMillis = 60)) + expandHorizontally(tween(240)),
                    exit = fadeOut(tween(80)) + shrinkHorizontally(tween(220))) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(6.dp))
                        Text(item.label, color = tint, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
