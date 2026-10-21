package com.vito.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri

/** Builds the notifications the second phone shows. */
object Alerts {
    private const val CH_CALLS = "relay_calls"
    private const val CH_MESSAGES = "relay_messages"
    private const val CH_QUIET = "relay_quiet"
    private const val CH_STATUS = "relay_status"

    private const val RING_ID = 4242
    private const val MAX_AGE_MS = 6 * 3_600_000L
    private const val CATCH_UP_MS = 2 * 60_000L
    private const val RING_FRESH_MS = 40_000L

    /** Recent lines per conversation, so a chat shows its last few messages together. */
    private val threads = HashMap<Int, ArrayDeque<String>>()

    @Volatile
    private var channelsReady = false

    fun ensureChannels(ctx: Context) {
        if (channelsReady) return
        val calls = NotificationChannel(CH_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings while your main phone is ringing"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 600, 800)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val messages = NotificationChannel(CH_MESSAGES, "Messages and missed calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Texts, WhatsApp chats and missed calls from your main phone"
            enableVibration(true)
        }
        val quiet = NotificationChannel(CH_QUIET, "Catch-up and call updates", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Older alerts delivered after a reconnect plus answered or declined calls"
        }
        val status = NotificationChannel(CH_STATUS, "Connection", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shows that Relay is connected"
            setShowBadge(false)
        }
        manager(ctx).createNotificationChannels(listOf(calls, messages, quiet, status))
        channelsReady = true
    }

    fun show(ctx: Context, ev: Relayed, ageMs: Long) {
        ensureChannels(ctx)
        val payloadAge = System.currentTimeMillis() - ev.time
        if (ageMs > MAX_AGE_MS || payloadAge > MAX_AGE_MS) return
        val catchUp = ageMs > CATCH_UP_MS
        val nm = manager(ctx)

        when (ev.kind) {
            Relayed.RING -> if (ageMs < RING_FRESH_MS) ring(ctx, ev)
            Relayed.ANSWERED -> {
                nm.cancel(RING_ID)
                if (!catchUp) answered(ctx, ev)
            }
            Relayed.MISSED, Relayed.DECLINED -> {
                nm.cancel(RING_ID)
                missed(ctx, ev, catchUp)
            }
            Relayed.DISMISS -> {
                val id = idFor(ev)
                nm.cancel(id)
                synchronized(threads) { threads.remove(id) }
            }
            else -> message(ctx, ev, catchUp)
        }
    }

    private fun message(ctx: Context, ev: Relayed, catchUp: Boolean) {
        val id = idFor(ev)
        val line = if (" · " in ev.title) "${ev.title.substringBefore(" · ")}: ${ev.text}" else ev.text
        val lines = synchronized(threads) {
            val q = threads.getOrPut(id) { ArrayDeque() }
            if (line.isNotBlank() && q.lastOrNull() != line) q.addLast(line)
            while (q.size > 6) q.removeFirst()
            q.toList()
        }

        val builder = Notification.Builder(ctx, if (catchUp) CH_QUIET else CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(ev.title.ifEmpty { ev.app })
            .setContentText(ev.text)
            .setSubText(ev.app)
            .setWhen(ev.time)
            .setShowWhen(true)
            .setColor(colorFor(ev.pkg))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(openApp(ctx, ev.pkg, id))

        if (lines.size > 1) {
            val style = Notification.InboxStyle()
            lines.forEach { style.addLine(it) }
            builder.setStyle(style)
        } else {
            builder.setStyle(Notification.BigTextStyle().bigText(ev.text))
        }
        manager(ctx).notify(id, builder.build())
    }

    private fun ring(ctx: Context, ev: Relayed) {
        val text = if (ev.text.isNotBlank()) "Incoming call · ${ev.text}" else "Incoming call on your main phone"
        val notification = Notification.Builder(ctx, CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(ev.title)
            .setContentText(text)
            .setSubText("Main phone")
            .setCategory(Notification.CATEGORY_CALL)
            .setColor(GREEN)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setTimeoutAfter(45_000)
            .setAutoCancel(true)
            .setContentIntent(openSelf(ctx, RING_ID))
            .build()
        // Keep ringing until the call is answered, missed or declined on the main phone.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        manager(ctx).notify(RING_ID, notification)
    }

    private fun answered(ctx: Context, ev: Relayed) {
        val notification = Notification.Builder(ctx, CH_QUIET)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(ev.title)
            .setContentText("Answered on your main phone")
            .setColor(GREEN)
            .setTimeoutAfter(15_000)
            .setAutoCancel(true)
            .build()
        manager(ctx).notify(RING_ID + 1, notification)
    }

    private fun missed(ctx: Context, ev: Relayed, catchUp: Boolean) {
        val declined = ev.kind == Relayed.DECLINED
        val headline = if (declined) "Declined on your main phone" else "Missed call"
        val text = if (ev.text.isNotBlank()) "$headline · ${ev.text}" else headline
        val id = ("missed" + ev.time).hashCode() and 0x7fffffff
        val builder = Notification.Builder(ctx, if (catchUp || declined) CH_QUIET else CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(ev.title)
            .setContentText(text)
            .setSubText("Main phone")
            .setWhen(ev.time)
            .setShowWhen(true)
            .setColor(if (declined) MUTED else RED)
            .setCategory(Notification.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(openSelf(ctx, id))
        if (ev.number.isNotEmpty()) {
            val dial = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", ev.number, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(ctx, id, dial, PENDING_FLAGS)
            @Suppress("DEPRECATION")
            builder.addAction(R.drawable.ic_stat_call, "Call back", pending)
        }
        manager(ctx).notify(id, builder.build())
    }

    fun statusNotification(ctx: Context, status: String): Notification {
        ensureChannels(ctx)
        return Notification.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle("Relay is receiving")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColor(Palette.ACCENT)
            .setContentIntent(openSelf(ctx, 1))
            .build()
    }

    private fun openApp(ctx: Context, pkg: String, requestCode: Int): PendingIntent {
        // If the same app is installed here (WhatsApp as a linked device, say), open it.
        val launch = (if (pkg.contains('.') && !pkg.startsWith("relay.")) {
            ctx.packageManager.getLaunchIntentForPackage(pkg)
        } else {
            null
        }) ?: Intent(ctx, MainActivity::class.java)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, requestCode, launch, PENDING_FLAGS)
    }

    private fun openSelf(ctx: Context, requestCode: Int): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, requestCode, intent, PENDING_FLAGS)
    }

    private fun idFor(ev: Relayed): Int {
        val id = (ev.pkg + "|" + ev.key).hashCode() and 0x7fffffff
        return if (id < 10_000) id + 10_000 else id
    }

    private fun colorFor(pkg: String): Int = when {
        pkg.startsWith("com.whatsapp") -> WHATSAPP
        pkg.contains("messag") || pkg == "com.android.mms" -> BLUE
        pkg == Relayed.CALL_PKG -> GREEN
        else -> Palette.ACCENT
    }

    private fun manager(ctx: Context): NotificationManager =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private const val PENDING_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    private val WHATSAPP = 0xFF25D366.toInt()
    private val BLUE = 0xFF4C8DF6.toInt()
    private val GREEN = 0xFF34C46A.toInt()
    private val RED = 0xFFF0504A.toInt()
    private val MUTED = 0xFF8A93A3.toInt()
}
