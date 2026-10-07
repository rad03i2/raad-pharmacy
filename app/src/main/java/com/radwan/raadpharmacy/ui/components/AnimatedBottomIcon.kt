package com.radwan.raadpharmacy.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** One brief selection animation; rendering state is read only by the icon layer. */
@Composable
fun AnimatedBottomIcon(icon: ImageVector, label: String, selected: Boolean, index: Int) {
    val progress = remember { Animatable(1f) }
    val lift = with(LocalDensity.current) { 3.dp.toPx() }
    LaunchedEffect(selected) {
        if (selected) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(460, easing = FastOutSlowInEasing))
        } else progress.snapTo(1f)
    }
    Icon(icon, contentDescription = label, modifier = Modifier.graphicsLayer {
        val pulse = if (selected) sin(PI * progress.value).toFloat() else 0f
        val scale = (if (selected) 1.06f else 1f) + pulse * 0.18f
        scaleX = scale
        scaleY = scale
        translationY = -lift * pulse
        rotationZ = if (selected) {
            val angle = if (index == 4) 20f else if (index % 2 == 0) 7f else -7f
            angle * sin(2 * PI * progress.value).toFloat()
        } else 0f
    })
}
