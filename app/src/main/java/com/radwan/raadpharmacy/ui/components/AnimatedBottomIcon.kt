package com.radwan.raadpharmacy.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Coordinated outline-to-fill transition, spring focus and a single accent trace. */
@Composable
fun AnimatedBottomIcon(icon: ImageVector, label: String, selected: Boolean, index: Int) {
    val focus = remember { Animatable(0f) }
    val trace = remember { Animatable(1f) }
    val outline = remember(index) {
        when (index) {
            0 -> Icons.Outlined.Home
            1 -> Icons.Outlined.People
            2 -> Icons.Outlined.Payments
            3 -> Icons.Outlined.Assessment
            else -> Icons.Outlined.Settings
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    LaunchedEffect(selected) {
        if (selected) {
            trace.snapTo(0f)
            focus.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMedium))
            trace.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
        } else {
            focus.animateTo(0f, tween(180))
            trace.snapTo(1f)
        }
    }
    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val strength = focus.value.coerceIn(0f, 1f)
            drawCircle(accent.copy(alpha = 0.07f * strength), radius = size.minDimension * 0.46f * strength)
            if (selected && trace.value < 1f) {
                drawArc(accent.copy(alpha = (1f - trace.value) * 0.65f),
                    startAngle = -90f, sweepAngle = 360f * trace.value, useCenter = false,
                    style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Crossfade(targetState = selected, animationSpec = tween(220), label = "navigation-fill") { filled ->
            Icon(if (filled) icon else outline, contentDescription = label,
                modifier = Modifier.size(25.dp).graphicsLayer {
                    val strength = focus.value
                    scaleX = 0.94f + 0.1f * strength
                    scaleY = 0.94f + 0.1f * strength
                    translationY = -1.5.dp.toPx() * strength
                })
        }
    }
}
