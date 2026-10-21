package com.vito.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Reconnects the second phone after a restart or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cfg = Config(context)
        if (cfg.role == Config.ROLE_RECEIVER && cfg.receiving && cfg.code != null) {
            try {
                ReceiverService.start(context)
            } catch (e: Exception) {
                Log.w("Relay", "Could not reconnect after boot", e)
            }
        }
    }
}
