package com.radwan.raadpharmacy.cloud

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean

data class CloudUiEvent(
    val title: String,
    val message: String,
    val customerId: String? = null,
    val actorName: String? = null,
    val kind: Kind = Kind.INFO,
    val occurredAt: Long = System.currentTimeMillis()
) {
    enum class Kind {
        DEBT,
        PAYMENT,
        EDIT,
        DELETE,
        REFRESH,
        INFO
    }
}

object CloudUiEvents {
    private val _events = MutableSharedFlow<CloudUiEvent>(
        replay = 0,
        extraBufferCapacity = 24,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    private val appForeground = AtomicBoolean(false)

    fun emit(event: CloudUiEvent) {
        _events.tryEmit(event)
    }

    fun setAppForeground(value: Boolean) {
        appForeground.set(value)
    }

    fun isAppForeground(): Boolean = appForeground.get()
}
