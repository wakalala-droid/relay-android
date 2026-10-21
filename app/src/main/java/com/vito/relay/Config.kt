package com.vito.relay

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Every setting lives in one small SharedPreferences file. */
class Config(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** "sender" on the main phone, "receiver" on the second phone, empty until chosen. */
    var role: String
        get() = prefs.getString(KEY_ROLE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ROLE, value).apply()

    /** Shared pairing code. Both the relay topic and the encryption key come from it. */
    var code: String?
        get() = prefs.getString(KEY_CODE, null)
        set(value) = putNullable(KEY_CODE, value)

    var server: String
        get() = prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER
        set(value) = prefs.edit().putString(KEY_SERVER, cleanServer(value)).apply()

    /** Optional access token for a private or paid relay server. */
    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /** Package names whose notifications are forwarded. */
    var apps: Set<String>
        get() = prefs.getStringSet(KEY_APPS, null)?.toSet() ?: DEFAULT_APPS
        set(value) = prefs.edit().putStringSet(KEY_APPS, HashSet(value)).apply()

    var callAlerts: Boolean
        get() = prefs.getBoolean(KEY_CALLS, true)
        set(value) = prefs.edit().putBoolean(KEY_CALLS, value).apply()

    var syncDismiss: Boolean
        get() = prefs.getBoolean(KEY_DISMISS, true)
        set(value) = prefs.edit().putBoolean(KEY_DISMISS, value).apply()

    var receiving: Boolean
        get() = prefs.getBoolean(KEY_RECEIVING, false)
        set(value) = prefs.edit().putBoolean(KEY_RECEIVING, value).apply()

    /** Id of the last relay message handled, so a reconnect picks up anything missed. */
    var lastId: String?
        get() = prefs.getString(KEY_LAST_ID, null)
        set(value) = putNullable(KEY_LAST_ID, value)

    private fun putNullable(key: String, value: String?) {
        val editor = prefs.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }

    // ---- Activity log shown in the app ----

    fun log(direction: String, app: String, title: String) {
        synchronized(LOCK) {
            val old = readArray()
            val fresh = JSONArray()
            fresh.put(
                JSONObject()
                    .put("t", System.currentTimeMillis())
                    .put("d", direction)
                    .put("a", app)
                    .put("x", title.take(140))
            )
            for (i in 0 until minOf(old.length(), MAX_LOG - 1)) {
                old.optJSONObject(i)?.let { fresh.put(it) }
            }
            prefs.edit().putString(KEY_LOG, fresh.toString()).apply()
            logVersion++
        }
    }

    fun readLog(): List<LogEntry> {
        val arr = synchronized(LOCK) { readArray() }
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                LogEntry(it.optLong("t"), it.optString("d"), it.optString("a"), it.optString("x"))
            }
        }
    }

    fun clearLog() {
        synchronized(LOCK) {
            prefs.edit().remove(KEY_LOG).apply()
            logVersion++
        }
    }

    private fun readArray(): JSONArray = try {
        JSONArray(prefs.getString(KEY_LOG, "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    data class LogEntry(val time: Long, val direction: String, val app: String, val title: String)

    companion object {
        const val ROLE_SENDER = "sender"
        const val ROLE_RECEIVER = "receiver"

        const val SENT = "sent"
        const val RECEIVED = "received"
        const val FAILED = "failed"

        const val DEFAULT_SERVER = "https://ntfy.sh"

        /** Forwarded out of the box: WhatsApp, WhatsApp Business and the common SMS apps. */
        val DEFAULT_APPS: Set<String> = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging",
            "com.android.mms"
        )

        /** Phone apps. Their notifications are replaced by Relay's own call alerts. */
        val DIALERS: Set<String> = setOf(
            "com.samsung.android.incallui",
            "com.samsung.android.dialer",
            "com.google.android.dialer",
            "com.android.incallui",
            "com.android.dialer",
            "com.android.server.telecom",
            "com.android.phone"
        )

        @Volatile
        var logVersion = 0

        private val LOCK = Any()
        private const val MAX_LOG = 25
        private const val FILE = "relay"
        private const val KEY_ROLE = "role"
        private const val KEY_CODE = "code"
        private const val KEY_SERVER = "server"
        private const val KEY_TOKEN = "token"
        private const val KEY_APPS = "apps"
        private const val KEY_CALLS = "call_alerts"
        private const val KEY_DISMISS = "sync_dismiss"
        private const val KEY_RECEIVING = "receiving"
        private const val KEY_LAST_ID = "last_id"
        private const val KEY_LOG = "log"

        fun cleanServer(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return DEFAULT_SERVER
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            return s
        }
    }
}
