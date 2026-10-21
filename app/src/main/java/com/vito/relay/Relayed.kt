package com.vito.relay

import org.json.JSONObject

/** One alert travelling from the main phone to the second phone. */
data class Relayed(
    val kind: String,
    val app: String = "",
    val pkg: String = "",
    val title: String = "",
    val text: String = "",
    val key: String = "",
    val number: String = "",
    val time: Long = System.currentTimeMillis()
) {

    fun toJson(): String = JSONObject()
        .put("v", 1)
        .put("k", kind)
        .put("a", app)
        .put("p", pkg)
        .put("t", title)
        .put("x", text)
        .put("id", key)
        .put("n", number)
        .put("w", time)
        .toString()

    /** Encrypts the event, trimming very long text so it fits in a single relay message. */
    fun seal(keys: Keys): String {
        var t = title.take(160)
        var x = text.take(1500)
        while (true) {
            val sealed = keys.seal(copy(title = t, text = x).toJson())
            if (sealed.length <= MAX_SEALED) return sealed
            when {
                x.length > 12 -> x = x.take(x.length / 2).trimEnd() + "…"
                x.isNotEmpty() -> x = ""
                t.length > 40 -> t = t.take(40)
                else -> return sealed
            }
        }
    }

    /** One line for the activity log. */
    fun summary(): String = when (kind) {
        RING -> "Incoming call · $title"
        ANSWERED -> "Answered · $title"
        MISSED -> "Missed call · $title"
        DECLINED -> "Declined · $title"
        else -> listOf(title, text).filter { it.isNotBlank() }.joinToString(" · ")
    }

    companion object {
        const val NOTIF = "notif"
        const val DISMISS = "dismiss"
        const val RING = "ring"
        const val ANSWERED = "answered"
        const val MISSED = "missed"
        const val DECLINED = "declined"
        const val TEST = "test"

        const val CALL_PKG = "relay.call"
        const val SELF_PKG = "relay.self"

        /** ntfy turns bodies over 4096 bytes into attachments, so stay well under. */
        private const val MAX_SEALED = 3800

        fun fromJson(raw: String): Relayed? = try {
            val o = JSONObject(raw)
            Relayed(
                kind = o.getString("k"),
                app = o.optString("a"),
                pkg = o.optString("p"),
                title = o.optString("t"),
                text = o.optString("x"),
                key = o.optString("id"),
                number = o.optString("n"),
                time = o.optLong("w", System.currentTimeMillis())
            )
        } catch (e: Exception) {
            null
        }
    }
}
