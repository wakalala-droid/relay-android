package com.vito.relay

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Runs on the second phone. Holds one long-lived connection to the relay server and turns
 * each encrypted event into a notification on this phone.
 */
class ReceiverService : Service() {

    private val wake = LinkedBlockingQueue<Boolean>()

    @Volatile
    private var stopping = false

    @Volatile
    private var connection: HttpURLConnection? = null

    private var worker: Thread? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var currentNetwork: Network? = null

    private val seen = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 300
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Alerts.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (worker?.isAlive != true) {
            stopping = false
            worker = Thread({ loop() }, "relay-receiver").apply {
                isDaemon = true
                start()
            }
        }
        watchNetwork()
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        wake.offer(true)
        closeConnection()
        networkCallback?.let { cb ->
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                // Already gone.
            }
        }
        networkCallback = null
        worker?.interrupt()
        worker = null
        status = "Stopped"
        connected = false
        super.onDestroy()
    }

    private fun goForeground() {
        val notification = Alerts.statusNotification(this, status)
        when {
            Build.VERSION.SDK_INT >= 34 ->
                startForeground(STATUS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            Build.VERSION.SDK_INT >= 29 ->
                startForeground(STATUS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(STATUS_ID, notification)
        }
    }

    private fun setStatus(text: String, isConnected: Boolean) {
        connected = isConnected
        if (text == status) return
        status = text
        if (stopping) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(STATUS_ID, Alerts.statusNotification(this, text))
        } catch (e: Exception) {
            Log.w(TAG, "Could not update status", e)
        }
    }

    private fun loop() {
        var backoff = MIN_BACKOFF_MS
        while (!stopping) {
            val cfg = Config(this)
            val code = cfg.code
            if (code == null) {
                setStatus("Add the pairing code from your main phone", false)
                pause(30_000)
                continue
            }
            val keys = Keys(code)
            try {
                setStatus("Connecting…", false)
                val since = cfg.lastId?.let { "?since=" + URLEncoder.encode(it, "UTF-8") } ?: ""
                val conn = URL("${cfg.server}/${keys.topic}/json$since").openConnection() as HttpURLConnection
                connection = conn
                conn.connectTimeout = 15_000
                // The relay sends a keepalive roughly every 45 seconds; silence for longer means a dead link.
                conn.readTimeout = 95_000
                val token = cfg.token
                if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
                val http = conn.responseCode
                if (http != 200) throw IOException("Relay server answered HTTP $http")
                setStatus("Connected · waiting for alerts", true)
                backoff = MIN_BACKOFF_MS
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    while (!stopping) {
                        val line = reader.readLine() ?: break
                        handle(line, keys, cfg)
                    }
                }
            } catch (e: Exception) {
                if (!stopping) Log.w(TAG, "Connection dropped", e)
            } finally {
                closeConnection()
            }
            if (stopping) break
            setStatus("Reconnecting…", false)
            pause(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun handle(line: String, keys: Keys, cfg: Config) {
        val obj = try {
            JSONObject(line)
        } catch (e: Exception) {
            return
        }
        if (obj.optString("event") != "message") return
        val id = obj.optString("id")
        if (id.isEmpty() || seen.containsKey(id)) return
        seen[id] = true
        cfg.lastId = id

        val plain = keys.open(obj.optString("message")) ?: return
        val event = Relayed.fromJson(plain) ?: return
        val relayedAt = obj.optLong("time") * 1000
        val ageMs = if (relayedAt > 0) System.currentTimeMillis() - relayedAt else 0L

        Alerts.show(this, event, ageMs)
        Inbox.record(this, event)
        if (event.kind != Relayed.DISMISS) cfg.log(Config.RECEIVED, event.app, event.summary())
    }

    private fun pause(ms: Long) {
        wake.clear()
        try {
            wake.poll(ms, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // Woken up to stop or reconnect.
        }
    }

    /** Closing a socket can touch the network, so never do it on the main thread. */
    private fun closeConnection() {
        val c = connection ?: return
        connection = null
        Thread { c.disconnect() }.start()
    }

    private fun watchNetwork() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = currentNetwork
                currentNetwork = network
                // Moved from Wi-Fi to mobile data or back: drop the stale link and reconnect now.
                if (previous != null && previous != network) closeConnection()
                wake.offer(true)
            }

            override fun onLost(network: Network) {
                if (network == currentNetwork) {
                    currentNetwork = null
                    closeConnection()
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch the network", e)
        }
    }

    companion object {
        private const val TAG = "RelayReceiver"
        private const val STATUS_ID = 1
        private const val MIN_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L

        @Volatile
        var status: String = "Stopped"
            private set

        @Volatile
        var connected = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ReceiverService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReceiverService::class.java))
        }
    }
}
