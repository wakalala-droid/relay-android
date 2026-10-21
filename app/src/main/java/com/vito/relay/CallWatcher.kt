package com.vito.relay

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Follows the main phone's call state and relays ringing, answered, missed and declined calls,
 * with the caller's name and number type taken from saved contacts.
 */
class CallWatcher(private val context: Context) : BroadcastReceiver() {

    private val handler = Handler(Looper.getMainLooper())

    private var ringing = false
    private var answered = false
    private var announced = false
    private var number = ""
    private var caller: Caller? = null

    /** Android can report a ringing call before the number is known; wait briefly for it. */
    private val announceLater = Runnable { if (ringing && !announced) announce() }

    fun start() {
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(this, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(this, filter)
        }
    }

    fun stop() {
        handler.removeCallbacks(announceLater)
        try {
            context.unregisterReceiver(this)
        } catch (e: IllegalArgumentException) {
            // Not registered.
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val incoming = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (!ringing) {
                    ringing = true
                    answered = false
                    announced = false
                    number = ""
                    caller = null
                }
                if (!incoming.isNullOrBlank()) number = incoming
                if (announced) return
                if (number.isNotEmpty()) {
                    announce()
                } else {
                    handler.removeCallbacks(announceLater)
                    handler.postDelayed(announceLater, 1_500)
                }
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (ringing && !answered) {
                    answered = true
                    handler.removeCallbacks(announceLater)
                    val who = callerInfo()
                    send(Relayed.ANSWERED, who, "Answered on main phone")
                }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (!ringing) return
                handler.removeCallbacks(announceLater)
                val wasAnswered = answered
                val who = callerInfo()
                ringing = false
                answered = false
                announced = false
                number = ""
                caller = null
                if (!wasAnswered) {
                    // Give the call log a moment to record whether the call was declined.
                    handler.postDelayed({
                        val kind = if (wasDeclined()) Relayed.DECLINED else Relayed.MISSED
                        send(kind, who, who.detail)
                    }, 2_000)
                }
            }
        }
    }

    private fun announce() {
        announced = true
        handler.removeCallbacks(announceLater)
        val who = callerInfo()
        send(Relayed.RING, who, who.detail)
    }

    private fun callerInfo(): Caller = caller ?: lookup(context, number).also { caller = it }

    private fun send(kind: String, who: Caller, text: String) {
        Relay.send(
            context,
            Relayed(
                kind = kind,
                app = "Phone",
                pkg = Relayed.CALL_PKG,
                title = who.display,
                text = text,
                key = "call",
                number = who.number
            )
        )
    }

    private fun wasDeclined(): Boolean {
        if (!granted(context, Manifest.permission.READ_CALL_LOG)) return false
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null,
                null,
                CallLog.Calls.DATE + " DESC"
            )?.use { c ->
                if (!c.moveToFirst()) return@use false
                val type = c.getInt(0)
                val date = c.getLong(1)
                type == CallLog.Calls.REJECTED_TYPE && System.currentTimeMillis() - date < 120_000
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    data class Caller(val name: String?, val type: String?, val number: String) {
        val display: String
            get() = name?.takeIf { it.isNotBlank() } ?: number.ifEmpty { "Unknown caller" }

        /** "Mobile · +260 97 123 4567" for a saved contact, empty otherwise. */
        val detail: String
            get() = if (name.isNullOrBlank()) "" else listOfNotNull(
                type?.takeIf { it.isNotBlank() },
                number.takeIf { it.isNotEmpty() }
            ).joinToString(" · ")
    }

    companion object {
        private const val TAG = "RelayCalls"

        fun granted(ctx: Context, permission: String): Boolean =
            ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        /** Call alerts need the call state and the caller's number. Contacts add the name. */
        fun canWatch(ctx: Context): Boolean =
            granted(ctx, Manifest.permission.READ_PHONE_STATE) &&
                granted(ctx, Manifest.permission.READ_CALL_LOG)

        fun lookup(ctx: Context, number: String): Caller {
            if (number.isEmpty() || !granted(ctx, Manifest.permission.READ_CONTACTS)) {
                return Caller(null, null, number)
            }
            try {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                ctx.contentResolver.query(uri, arrayOf("display_name", "type", "label"), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val name = c.getString(0)
                        val type = if (c.isNull(1)) {
                            null
                        } else {
                            ContactsContract.CommonDataKinds.Phone
                                .getTypeLabel(ctx.resources, c.getInt(1), c.getString(2))
                                ?.toString()
                        }
                        return Caller(name, type, number)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Contact lookup failed", e)
            }
            return Caller(null, null, number)
        }
    }
}
