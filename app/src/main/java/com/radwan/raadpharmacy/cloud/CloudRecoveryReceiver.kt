package com.radwan.raadpharmacy.cloud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Schedule durable recovery after unlock/reboot or update; no network work in a receiver. */
class CloudRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!CloudSyncScheduler.isEnabled(context)) return
        CloudSyncScheduler.ensurePeriodic(context)
        CloudPushRegistrationWorker.enqueue(context)
        CloudSyncScheduler.ensureNetworkCatchUp(context)
        CloudSyncScheduler.enqueue(context)
    }
}
