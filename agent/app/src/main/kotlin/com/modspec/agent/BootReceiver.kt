package com.modspec.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-starts [AgentService] and triggers profile reapply after boot when configured.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        AgentService.start(context)

        // TODO: read state.json / active profile reapply.on_boot flag before reapply
        AgentService.reapply(context, onlyFailed = false)
    }
}
