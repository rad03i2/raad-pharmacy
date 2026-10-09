package com.radwan.raadpharmacy.ui.components

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerFlingBehaviorTest {
    @Test fun flingAcceleratesBothDirectionsAndReturnsOriginalVelocityAtAnUnscrollableBoundary() = runTest {
        var received = 0f
        val delegate = object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                received = initialVelocity
                return initialVelocity
            }
        }
        val scroll = object : ScrollScope { override fun scrollBy(pixels: Float) = 0f }
        val fast = LedgerFlingBehavior(delegate)
        listOf(2_000f,-2_000f,0f).forEach { velocity ->
            val left = with(fast) { scroll.performFling(velocity) }
            assertEquals(velocity * 1.35f, received,0.01f)
            assertEquals(velocity,left,0.01f)
        }
    }
    @Test fun consumedFlingDoesNotLeakArtificialVelocityToTheParent() = runTest {
        val delegate = object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float = 0f
        }
        assertEquals(0f,with(LedgerFlingBehavior(delegate)) { (object : ScrollScope { override fun scrollBy(pixels: Float) = pixels }).performFling(2_000f) },0f)
    }
}
