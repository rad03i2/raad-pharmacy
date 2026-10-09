package com.radwan.raadpharmacy.ui.components

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Boost the release velocity; dragging still follows the user's finger exactly. */
internal class LedgerFlingBehavior(private val delegate: FlingBehavior) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float =
        with(delegate) { performFling(initialVelocity * SPEED) } / SPEED

    companion object { internal const val SPEED = 1.35f }
}

@Composable
fun rememberLedgerFlingBehavior(): FlingBehavior {
    val platform = ScrollableDefaults.flingBehavior()
    return remember(platform) { LedgerFlingBehavior(platform) }
}
