package com.radwan.raadpharmacy.demo

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The promotional Activity never loads real data. It also prevents app-owned
 * customer-name notifications from being posted while filming; undelivered
 * events remain queued for normal delivery after the preview is closed.
 */
object DemoPrivacySession {
    private val active = AtomicBoolean(false)
    fun setActive(value: Boolean) { active.set(value) }
    fun isActive(): Boolean = active.get()
}
