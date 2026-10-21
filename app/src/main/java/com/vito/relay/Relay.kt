package com.vito.relay

import android.content.Context
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Sends encrypted events from the main phone to the relay server, one at a time, in order. */
object Relay {
    private const val TAG = "Relay"
    private const val ATTEMPTS = 4
    private val worker = Executors.newSingleThreadExecutor()

    fun send(context: Context, event: Relayed, logIt: Boolean = true) {
        val app = context.applicationContext
        worker.execute { deliver(app, event, logIt) }
    }

    private fun deliver(context: Context, event: Relayed, logIt: Boolean) {
        val cfg = Config(context)
        val code = cfg.code ?: return
        val keys = Keys(code)
        val body = event.seal(keys)
        var wait = 2_000L
        repeat(ATTEMPTS) { attempt ->
            try {
                post(cfg, keys.topic, body)
                if (logIt) cfg.log(Config.SENT, event.app, event.summary())
                return
            } catch (e: Exception) {
                Log.w(TAG, "Send failed on attempt ${attempt + 1}", e)
                if (attempt < ATTEMPTS - 1) {
                    try {
                        Thread.sleep(wait)
                    } catch (ie: InterruptedException) {
                        return
                    }
                    wait *= 2
                }
            }
        }
        if (logIt) cfg.log(Config.FAILED, event.app, "Not sent. Check this phone's internet connection.")
    }

    private fun post(cfg: Config, topic: String, body: String) {
        val conn = URL("${cfg.server}/$topic").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            // Delivery is handled by Relay's own connection, so skip ntfy's Firebase fan-out.
            conn.setRequestProperty("X-Firebase", "no")
            val token = cfg.token
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            if (status !in 200..299) throw IOException("Relay server answered HTTP $status")
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
