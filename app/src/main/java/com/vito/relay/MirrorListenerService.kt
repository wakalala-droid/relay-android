package com.vito.relay

import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Runs on the main phone once notification access is granted. Android hands it every
 * notification; it keeps the ones from the chosen apps and relays them.
 */
class MirrorListenerService : NotificationListenerService() {

    private var calls: CallWatcher? = null

    /** Recently relayed content, so an app re-posting the same alert is not sent twice. */
    private val recent = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 300
    }

    /** Keys of notifications that were relayed, so removing them can be mirrored too. */
    private val forwarded = LinkedHashSet<String>()

    private val labels = HashMap<String, String>()

    override fun onListenerConnected() {
        instance = this
        connected = true
        refreshCallWatcher()
    }

    override fun onListenerDisconnected() {
        connected = false
        stopCallWatcher()
        try {
            NotificationListenerService.requestRebind(ComponentName(this, MirrorListenerService::class.java))
        } catch (e: Exception) {
            // Access was switched off. Nothing to rebind.
        }
    }

    override fun onDestroy() {
        stopCallWatcher()
        connected = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Starts or stops call alerts to match the current settings and permissions. */
    fun refreshCallWatcher() {
        val cfg = Config(this)
        val wanted = cfg.role == Config.ROLE_SENDER && cfg.code != null &&
            cfg.callAlerts && CallWatcher.canWatch(this)
        if (wanted && calls == null) {
            calls = CallWatcher(applicationContext).also { it.start() }
        } else if (!wanted && calls != null) {
            stopCallWatcher()
        }
    }

    private fun stopCallWatcher() {
        calls?.stop()
        calls = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val cfg = Config(this)
        if (cfg.role != Config.ROLE_SENDER || cfg.code == null) return
        refreshCallWatcher()

        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        val isDialer = pkg in Config.DIALERS
        if (isDialer) {
            // With call alerts running, Relay sends richer call events of its own.
            if (cfg.callAlerts) {
                if (calls != null) return
            } else if (pkg !in cfg.apps) {
                return
            }
        } else if (pkg !in cfg.apps) {
            return
        }

        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        // Phone calls and app calls (a WhatsApp call ringing, say) are ongoing alerts worth sending.
        if (!isDialer && n.category != Notification.CATEGORY_CALL) {
            if (sbn.isOngoing) return
            if ((n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return
            if (n.category in QUIET_CATEGORIES) return
        }

        val (title, text) = extract(n) ?: return
        val key = sbn.key ?: "$pkg:${sbn.id}"

        val signature = "$pkg|$key|$title|$text"
        val now = System.currentTimeMillis()
        val seenAt = recent[signature]
        if (seenAt != null && now - seenAt < DEDUPE_WINDOW_MS) return
        recent[signature] = now

        forwarded.remove(key)
        forwarded.add(key)
        while (forwarded.size > 300) forwarded.remove(forwarded.first())

        Relay.send(
            this,
            Relayed(
                kind = Relayed.NOTIF,
                app = label(pkg),
                pkg = pkg,
                title = title,
                text = text,
                key = shortKey(key),
                time = sbn.postTime
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val cfg = Config(this)
        if (cfg.role != Config.ROLE_SENDER || cfg.code == null || !cfg.syncDismiss) return
        val key = sbn.key ?: return
        if (!forwarded.remove(key)) return
        val pkg = sbn.packageName ?: ""
        recent.keys.removeAll { it.startsWith("$pkg|$key|") }
        Relay.send(
            this,
            Relayed(kind = Relayed.DISMISS, app = label(pkg), pkg = pkg, key = shortKey(key)),
            logIt = false
        )
    }

    /** Pulls the sender and the newest message out of a notification. */
    private fun extract(n: Notification): Pair<String, String>? {
        val extras = n.extras ?: return null
        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        var text = ""

        // Chat apps (WhatsApp, Messages) use MessagingStyle: read only the newest message.
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val last = messages?.lastOrNull() as? Bundle
        if (last != null) {
            text = last.getCharSequence("text")?.toString()?.trim().orEmpty()
            val sender = last.getCharSequence("sender")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: personName(last)
            val isFromMe = sender == null &&
                (extras.containsKey("android.selfDisplayName") || extras.containsKey("android.messagingUser"))
            if (isFromMe) return null
            val convo = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
            if (text.isNotEmpty()) {
                title = when {
                    !convo.isNullOrEmpty() && !sender.isNullOrEmpty() && sender != convo -> "$sender · $convo"
                    !sender.isNullOrEmpty() -> sender
                    else -> title
                }
            }
        }

        if (text.isEmpty()) {
            text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.trim().orEmpty()
        }
        if (title.isEmpty() && text.isEmpty()) return null
        if (NOISE.any { text.startsWith(it, ignoreCase = true) }) return null
        return title to text
    }

    private fun personName(bundle: Bundle): String? {
        if (Build.VERSION.SDK_INT < 28) return null
        @Suppress("DEPRECATION")
        val person = bundle.getParcelable<Parcelable>("sender_person") as? Person ?: return null
        return person.name?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun label(pkg: String): String {
        if (pkg in Config.DIALERS) return "Phone"
        labels[pkg]?.let { return it }
        val name = try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
        labels[pkg] = name
        return name
    }

    private fun shortKey(key: String): String = Integer.toHexString(key.hashCode())

    companion object {
        @Volatile
        var instance: MirrorListenerService? = null
            private set

        @Volatile
        var connected = false
            private set

        private const val DEDUPE_WINDOW_MS = 10 * 60_000L

        private val QUIET_CATEGORIES = setOf(
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SYSTEM
        )

        /** Housekeeping alerts that are not worth sending to another phone. */
        private val NOISE = listOf(
            "Checking for new messages",
            "Backup in progress",
            "Backing up",
            "Downloading",
            "Uploading",
            "WhatsApp Web is currently active"
        )
    }
}
