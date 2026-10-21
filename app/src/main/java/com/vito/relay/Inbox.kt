package com.vito.relay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The receiving phone's history of everything mirrored to it, grouped into conversations
 * regardless of which app each message came from. Stored as one small JSON file on this
 * phone only; nothing here is sent anywhere.
 */
object Inbox {

    private const val FILE = "inbox.json"
    private const val MAX_THREADS = 100
    private const val MAX_PER_THREAD = 200

    private val LOCK = Any()

    @Volatile
    var version = 0
        private set

    fun record(context: Context, event: Relayed) {
        if (event.kind == Relayed.DISMISS || event.kind == Relayed.TEST) return
        synchronized(LOCK) {
            val root = read(context)
            val threads = root.optJSONObject("threads") ?: JSONObject().also { root.put("threads", it) }

            val key = keyFor(event)
            val thread = threads.optJSONObject(key) ?: JSONObject().also { threads.put(key, it) }
            thread.put("app", event.app)
            thread.put("pkg", event.pkg)
            thread.put("name", nameFor(event))
            thread.put("updated", event.time)

            val messages = thread.optJSONArray("messages") ?: JSONArray().also { thread.put("messages", it) }
            messages.put(
                JSONObject()
                    .put("t", event.time)
                    .put("k", event.kind)
                    .put("x", lineFor(event))
                    .put("n", event.number)
            )
            thread.put("messages", trimEnd(messages, MAX_PER_THREAD))

            capThreads(threads)
            write(context, root)
            version++
        }
    }

    fun threads(context: Context): List<Thread> {
        val root = synchronized(LOCK) { read(context) }
        val threads = root.optJSONObject("threads") ?: return emptyList()
        val out = ArrayList<Thread>()
        val names = threads.keys()
        while (names.hasNext()) {
            val key = names.next()
            val t = threads.optJSONObject(key) ?: continue
            val messages = t.optJSONArray("messages")
            val count = messages?.length() ?: 0
            val last = if (count > 0) messages!!.optJSONObject(count - 1)?.optString("x").orEmpty() else ""
            out.add(
                Thread(
                    key = key,
                    name = t.optString("name").ifEmpty { t.optString("app") },
                    app = t.optString("app"),
                    pkg = t.optString("pkg"),
                    updated = t.optLong("updated"),
                    preview = last,
                    count = count
                )
            )
        }
        out.sortByDescending { it.updated }
        return out
    }

    fun messages(context: Context, key: String): List<Message> {
        val root = synchronized(LOCK) { read(context) }
        val thread = root.optJSONObject("threads")?.optJSONObject(key) ?: return emptyList()
        val arr = thread.optJSONArray("messages") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Message(it.optLong("t"), it.optString("k"), it.optString("x"), it.optString("n"))
            }
        }
    }

    fun clear(context: Context) {
        synchronized(LOCK) {
            File(context.filesDir, FILE).delete()
            version++
        }
    }

    data class Thread(
        val key: String,
        val name: String,
        val app: String,
        val pkg: String,
        val updated: Long,
        val preview: String,
        val count: Int
    )

    data class Message(val time: Long, val kind: String, val text: String, val number: String)

    private fun keyFor(event: Relayed): String = when (event.kind) {
        Relayed.RING, Relayed.ANSWERED, Relayed.MISSED, Relayed.DECLINED ->
            "call|" + event.title.lowercase()
        else -> {
            val sender = event.title.substringBefore(" · ").trim().lowercase()
            event.pkg + "|" + sender.ifEmpty { event.app.lowercase() }
        }
    }

    private fun nameFor(event: Relayed): String = when {
        event.title.isBlank() -> event.app
        " · " in event.title -> event.title.substringBefore(" · ").trim()
        else -> event.title
    }

    /** One readable line summarising the message for the thread. */
    private fun lineFor(event: Relayed): String = when (event.kind) {
        Relayed.RING -> "Incoming call"
        Relayed.ANSWERED -> "Answered on main phone"
        Relayed.MISSED -> "Missed call"
        Relayed.DECLINED -> "Declined on main phone"
        else -> {
            val convo = event.title.substringAfter(" · ", "").trim()
            if (convo.isNotEmpty()) "$convo: ${event.text}" else event.text
        }
    }

    private fun trimEnd(arr: JSONArray, max: Int): JSONArray {
        if (arr.length() <= max) return arr
        val fresh = JSONArray()
        for (i in (arr.length() - max) until arr.length()) {
            arr.optJSONObject(i)?.let { fresh.put(it) }
        }
        return fresh
    }

    private fun capThreads(threads: JSONObject) {
        if (threads.length() <= MAX_THREADS) return
        val ordered = ArrayList<Pair<String, Long>>()
        val names = threads.keys()
        while (names.hasNext()) {
            val k = names.next()
            ordered.add(k to (threads.optJSONObject(k)?.optLong("updated") ?: 0L))
        }
        ordered.sortBy { it.second }
        var remove = threads.length() - MAX_THREADS
        for ((k, _) in ordered) {
            if (remove-- <= 0) break
            threads.remove(k)
        }
    }

    private fun read(context: Context): JSONObject = try {
        val file = File(context.filesDir, FILE)
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }

    private fun write(context: Context, root: JSONObject) {
        try {
            File(context.filesDir, FILE).writeText(root.toString())
        } catch (e: Exception) {
            // Out of space or similar; the history is best effort.
        }
    }
}
