package com.vito.relay

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var cfg: Config

    private val handler = Handler(Looper.getMainLooper())

    /** Each live part of the screen registers how to update itself; refresh() runs them all. */
    private val updaters = mutableListOf<() -> Unit>()
    private var logBox: LinearLayout? = null
    private var shownLogVersion = -1
    private val labelCache = HashMap<String, String?>()

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1_500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        cfg = Config(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        MirrorListenerService.instance?.refreshCallWatcher()
        refresh()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1_500)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    // ---------------------------------------------------------------- screens

    private fun render() {
        updaters.clear()
        logBox = null
        shownLogVersion = -1

        val root = ui.column().apply { setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(32)) }
        root.addView(header())
        when (cfg.role) {
            Config.ROLE_SENDER -> buildSender(root)
            Config.ROLE_RECEIVER -> buildReceiver(root)
            else -> buildChooser(root)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Palette.BG)
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        refresh()
    }

    private fun header(): View {
        val wrap = ui.column()
        val brand = ui.row()
        brand.addView(ui.dot(Palette.ACCENT, 12))
        brand.addView(ui.text("Relay", 28f, Palette.TEXT, ui.bold), ui.wrap(startDp = 10))
        wrap.addView(brand)
        val subtitle = when (cfg.role) {
            Config.ROLE_SENDER -> "This is your main phone. Its calls, texts and chats go to your second phone."
            Config.ROLE_RECEIVER -> "This is your second phone. Alerts from your main phone show up here."
            else -> "Calls, texts and WhatsApp from one phone, shown on another."
        }
        wrap.addView(ui.text(subtitle, 15f, Palette.MUTED), ui.matchWrap(8))
        return wrap
    }

    private fun buildChooser(root: LinearLayout) {
        root.addView(ui.eyebrow("Which phone is this?"), ui.matchWrap(32))
        root.addView(
            choice(
                "Main phone",
                "The one with your SIM card and WhatsApp. Relay sends its alerts.",
                "Send from this phone"
            ) {
                cfg.role = Config.ROLE_SENDER
                if (cfg.code == null) cfg.code = Pairing.newCode()
                cfg.clearLog()
                render()
            },
            ui.matchWrap(12)
        )
        root.addView(
            choice(
                "Second phone",
                "Your other phone. Relay shows the main phone's alerts here.",
                "Receive on this phone"
            ) {
                cfg.role = Config.ROLE_RECEIVER
                cfg.clearLog()
                render()
            },
            ui.matchWrap(12)
        )
        root.addView(
            ui.text(
                "Install Relay on both phones. Everything passing between them is encrypted with a private pairing code.",
                14f,
                Palette.MUTED
            ),
            ui.matchWrap(20)
        )
    }

    private fun choice(title: String, detail: String, action: String, onPick: () -> Unit): View {
        val card = ui.card()
        card.addView(ui.text(title, 20f, Palette.TEXT, ui.medium))
        card.addView(ui.text(detail, 15f, Palette.MUTED), ui.matchWrap(6))
        card.addView(ui.button(action) { onPick() }, ui.matchWrap(16))
        return card
    }

    private fun buildSender(root: LinearLayout) {
        root.addView(
            statusCard {
                when {
                    !hasNotificationAccess() -> Status(
                        "Finish setup below",
                        "Relay needs notification access before it can send anything.",
                        Palette.WARN
                    )
                    !MirrorListenerService.connected -> Status(
                        "Starting…",
                        "Android is connecting Relay. This takes a few seconds.",
                        Palette.WARN
                    )
                    else -> Status("Sending to your second phone", sendingDetail(), Palette.OK)
                }
            },
            ui.matchWrap(24)
        )

        // Setup checklist
        val setup = ui.card()
        setup.addView(ui.eyebrow("Setup"))
        step(
            setup, "Notification access", "Lets Relay see alerts from the apps you choose.", "Turn on",
            first = true, isDone = { hasNotificationAccess() }
        ) { openNotificationAccess() }
        if (Build.VERSION.SDK_INT >= 33) {
            val hint = ui.text(
                "Switch greyed out? Open App info, tap the ⋮ menu, choose Allow restricted settings, then try again.",
                13f,
                Palette.MUTED
            )
            val appInfo = ui.link("Open App info") { openAppInfo() }
            setup.addView(hint, ui.matchWrap(10).apply { leftMargin = ui.dp(22) })
            setup.addView(appInfo, ui.wrap(startDp = 18))
            updaters += {
                val v = if (hasNotificationAccess()) View.GONE else View.VISIBLE
                hint.visibility = v
                appInfo.visibility = v
            }
        }
        step(
            setup, "Calls and contacts", "Shows who is calling, using your saved contact names.", "Allow",
            isDone = { CallWatcher.canWatch(this) && CallWatcher.granted(this, Manifest.permission.READ_CONTACTS) }
        ) { askCallPermissions() }
        step(
            setup, "Battery", "Stops your phone from pausing Relay in the background.", "Fix",
            isDone = { batteryOk() }
        ) { fixBattery() }
        root.addView(setup, ui.matchWrap(14))

        // Pairing code
        val pair = ui.card()
        pair.addView(ui.eyebrow("Pairing code"))
        val codeView = ui.text(cfg.code ?: "", 26f, Palette.ACCENT, ui.medium).apply { letterSpacing = 0.06f }
        pair.addView(codeView, ui.matchWrap(10))
        pair.addView(
            ui.text(
                "Enter this on your second phone. Keep it private: anyone who has it can read your alerts.",
                14f,
                Palette.MUTED
            ),
            ui.matchWrap(6)
        )
        val actions = ui.row()
        actions.addView(ui.button("Copy", primary = false, small = true) { copyCode() })
        actions.addView(ui.button("Share", primary = false, small = true) { shareCode() }, ui.wrap(startDp = 10))
        actions.addView(View(this), ui.fill())
        actions.addView(ui.link("New code") { confirmNewCode() })
        pair.addView(actions, ui.matchWrap(14))
        updaters += { codeView.text = cfg.code ?: "" }
        root.addView(pair, ui.matchWrap(14))

        // What to send
        val send = ui.card()
        send.addView(ui.eyebrow("What to send"))
        toggleRow(
            send, "Calls with caller names",
            "Rings your second phone, then shows missed or declined calls.",
            cfg.callAlerts, first = true
        ) { on ->
            cfg.callAlerts = on
            if (on && !CallWatcher.canWatch(this)) askCallPermissions()
            MirrorListenerService.instance?.refreshCallWatcher()
        }

        val appsRow = ui.row()
        val appsText = ui.column()
        appsText.addView(ui.text("Apps", 16f, Palette.TEXT, ui.medium))
        val appsSummary = ui.text("", 14f, Palette.MUTED)
        appsText.addView(appsSummary, ui.matchWrap(2))
        appsRow.addView(appsText, ui.fill())
        appsRow.addView(ui.button("Choose", primary = false, small = true) { chooseApps() }, ui.wrap(startDp = 12))
        send.addView(appsRow, ui.matchWrap(18))
        updaters += { appsSummary.text = appSummary() }

        toggleRow(
            send, "Clear on both phones",
            "Reading or swiping away an alert here removes it from the second phone too.",
            cfg.syncDismiss
        ) { on -> cfg.syncDismiss = on }

        send.addView(
            ui.text(
                "Using WhatsApp as a linked device on the second phone? Leave WhatsApp off the list to avoid double alerts.",
                13f,
                Palette.MUTED
            ),
            ui.matchWrap(16)
        )
        root.addView(send, ui.matchWrap(14))

        root.addView(ui.button("Send a test alert") { sendTest() }, ui.matchWrap(18))
        addActivity(root)
        addFooter(root)
    }

    private fun buildReceiver(root: LinearLayout) {
        root.addView(
            statusCard {
                when {
                    cfg.code == null -> Status(
                        "Add your pairing code",
                        "Copy it from Relay on your main phone.",
                        Palette.WARN
                    )
                    !cfg.receiving -> Status("Paused", "Tap Connect to start receiving alerts.", Palette.MUTED)
                    ReceiverService.connected -> Status(
                        "Connected",
                        "Alerts from your main phone will appear here.",
                        Palette.OK
                    )
                    else -> Status(
                        ReceiverService.status.takeIf { it != "Stopped" } ?: "Starting…",
                        "Relay keeps retrying on its own. If it stays here, check this phone's internet.",
                        Palette.WARN
                    )
                }
            },
            ui.matchWrap(24)
        )

        root.addView(
            ui.button("Open inbox", primary = false) {
                startActivity(Intent(this, InboxActivity::class.java))
            },
            ui.matchWrap(16)
        )

        // Pairing code
        val pair = ui.card()
        pair.addView(ui.eyebrow("Pairing code"))
        val input = ui.field("XXXXX-XXXXX-XXXXX-XXXXX").apply { setText(cfg.code ?: "") }
        pair.addView(input, ui.matchWrap(12))
        pair.addView(
            ui.text("Shown in Relay on your main phone. Tap Share there to send it here.", 14f, Palette.MUTED),
            ui.matchWrap(8)
        )
        val actions = ui.row()
        val paste = ui.button("Paste", primary = false, small = true) { pasteInto(input) }
        actions.addView(paste)
        actions.addView(View(this), ui.fill())
        val connect = ui.button("Connect", small = true) { connect(input) }
        val disconnect = ui.button("Disconnect", primary = false, small = true) { disconnect() }
        actions.addView(connect)
        actions.addView(disconnect)
        pair.addView(actions, ui.matchWrap(14))
        updaters += {
            val on = cfg.receiving
            connect.visibility = if (on) View.GONE else View.VISIBLE
            disconnect.visibility = if (on) View.VISIBLE else View.GONE
            paste.visibility = if (on) View.GONE else View.VISIBLE
            input.isEnabled = !on
            input.alpha = if (on) 0.6f else 1f
        }
        root.addView(pair, ui.matchWrap(14))

        // Setup checklist
        val setup = ui.card()
        setup.addView(ui.eyebrow("Setup"))
        var first = true
        if (Build.VERSION.SDK_INT >= 33) {
            step(
                setup, "Notifications", "Lets Relay show alerts and ring for calls.", "Allow",
                first = true, isDone = { notificationsAllowed() }
            ) { askNotifications() }
            first = false
        }
        step(
            setup, "Battery", "Keeps the connection alive while the screen is off.", "Fix",
            first = first, isDone = { batteryOk() }
        ) { fixBattery() }
        root.addView(setup, ui.matchWrap(14))

        addActivity(root)
        addFooter(root)
    }

    // ---------------------------------------------------------------- building blocks

    private data class Status(val title: String, val detail: String, val color: Int)

    private fun statusCard(compute: () -> Status): View {
        val card = ui.card()
        val top = ui.row()
        val dot = ui.dot(Palette.MUTED, 12)
        val title = ui.text("", 20f, Palette.TEXT, ui.medium)
        top.addView(dot)
        top.addView(title, ui.fill(startDp = 12))
        card.addView(top)
        val detail = ui.text("", 15f, Palette.MUTED)
        card.addView(detail, ui.matchWrap(6).apply { leftMargin = ui.dp(24) })
        updaters += {
            val s = compute()
            title.text = s.title
            detail.text = s.detail
            dot.background = ui.circle(s.color)
        }
        return card
    }

    private fun step(
        parent: LinearLayout,
        title: String,
        detail: String,
        action: String,
        first: Boolean = false,
        isDone: () -> Boolean,
        onAction: () -> Unit
    ) {
        val row = ui.row()
        val dot = ui.dot(Palette.WARN)
        row.addView(dot)
        val texts = ui.column()
        texts.addView(ui.text(title, 16f, Palette.TEXT, ui.medium))
        texts.addView(ui.text(detail, 14f, Palette.MUTED), ui.matchWrap(2))
        row.addView(texts, ui.fill(startDp = 12))
        val button = ui.button(action, primary = true, small = true) { onAction() }
        val done = ui.text("Done", 14f, Palette.OK, ui.medium)
        row.addView(button, ui.wrap(startDp = 12))
        row.addView(done, ui.wrap(startDp = 12))
        parent.addView(row, ui.matchWrap(if (first) 14 else 18))
        updaters += {
            val ok = isDone()
            dot.background = ui.circle(if (ok) Palette.OK else Palette.WARN)
            button.visibility = if (ok) View.GONE else View.VISIBLE
            done.visibility = if (ok) View.VISIBLE else View.GONE
        }
    }

    private fun toggleRow(
        parent: LinearLayout,
        title: String,
        detail: String,
        checked: Boolean,
        first: Boolean = false,
        onChange: (Boolean) -> Unit
    ) {
        val row = ui.row()
        val texts = ui.column()
        texts.addView(ui.text(title, 16f, Palette.TEXT, ui.medium))
        texts.addView(ui.text(detail, 14f, Palette.MUTED), ui.matchWrap(2))
        row.addView(texts, ui.fill())
        row.addView(ui.switch(checked, onChange), ui.wrap(startDp = 12))
        parent.addView(row, ui.matchWrap(if (first) 14 else 18))
    }

    private fun addActivity(root: LinearLayout) {
        val card = ui.card()
        val head = ui.row()
        head.addView(ui.eyebrow("Recent activity"), ui.fill())
        head.addView(ui.link("Clear") {
            cfg.clearLog()
            refresh()
        })
        card.addView(head)
        val box = ui.column()
        card.addView(box, ui.matchWrap(2))
        logBox = box
        root.addView(card, ui.matchWrap(14))
    }

    private fun addFooter(root: LinearLayout) {
        val row = ui.row()
        row.addView(ui.link("Relay server") { editServer() })
        row.addView(View(this), ui.fill())
        row.addView(ui.link("Change phone role") { confirmRoleReset() })
        root.addView(row, ui.matchWrap(16))
    }

    private fun refresh() {
        updaters.forEach { it() }
        val box = logBox ?: return
        if (shownLogVersion == Config.logVersion) return
        shownLogVersion = Config.logVersion
        fillLog(box)
    }

    private fun fillLog(box: LinearLayout) {
        box.removeAllViews()
        val entries = cfg.readLog()
        if (entries.isEmpty()) {
            val empty = if (cfg.role == Config.ROLE_SENDER) {
                "Nothing sent yet. Tap Send a test alert to try it."
            } else {
                "Nothing received yet. Alerts appear here as they arrive."
            }
            box.addView(ui.text(empty, 14f, Palette.MUTED), ui.matchWrap(10))
            return
        }
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
        entries.take(12).forEach { e ->
            val row = ui.row().apply { gravity = Gravity.TOP }
            val (glyph, tint) = when (e.direction) {
                Config.SENT -> "↑" to Palette.ACCENT
                Config.RECEIVED -> "↓" to Palette.OK
                else -> "!" to Palette.BAD
            }
            row.addView(ui.text(glyph, 16f, tint, ui.bold).apply { minWidth = ui.dp(18) })
            val col = ui.column()
            col.addView(ui.clamp(ui.text(e.title.ifEmpty { e.app }, 15f), 2))
            col.addView(ui.text("${e.app} · ${clock.format(Date(e.time))}", 13f, Palette.MUTED), ui.matchWrap(2))
            row.addView(col, ui.fill(startDp = 8))
            box.addView(row, ui.matchWrap(12))
        }
    }

    // ---------------------------------------------------------------- sender actions

    private fun hasNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(':').any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }

    private fun openNotificationAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            val component = ComponentName(this, MirrorListenerService::class.java)
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
            if (launch(detail)) return
        }
        if (!launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
            toast("Open Settings, search for Notification access and allow Relay.")
        }
    }

    private fun openAppInfo() {
        launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun askCallPermissions() {
        val needed = CALL_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) {
            MirrorListenerService.instance?.refreshCallWatcher()
            return
        }
        requestPermissions(needed.toTypedArray(), RC_CALLS)
    }

    private fun sendingDetail(): String {
        val parts = mutableListOf<String>()
        if (cfg.callAlerts) parts += if (CallWatcher.canWatch(this)) "calls with caller names" else "calls"
        parts += cfg.apps.mapNotNull { appLabel(it) }.distinct().sorted()
        if (parts.isEmpty()) return "Nothing is chosen yet. Pick apps under What to send."
        return "Sending " + humanJoin(parts) + "."
    }

    private fun appSummary(): String {
        val names = cfg.apps.mapNotNull { appLabel(it) }.distinct().sorted()
        return when {
            names.isEmpty() -> "None chosen yet"
            names.size <= 3 -> humanJoin(names)
            else -> names.take(3).joinToString(", ") + " and ${names.size - 3} more"
        }
    }

    private fun humanJoin(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    private fun appLabel(pkg: String): String? = labelCache.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun chooseApps() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val found = packageManager.queryIntentActivities(launcher, 0)
        val apps = found
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .filter { it != packageName }
            .mapNotNull { pkg -> appLabel(pkg)?.let { pkg to it } }
        val selected = cfg.apps.toMutableSet()
        val sorted = apps.sortedWith(
            compareBy<Pair<String, String>>(
                { it.first !in selected },
                { it.first !in Config.DEFAULT_APPS },
                { it.second.lowercase() }
            )
        )
        val labels = sorted.map { it.second }.toTypedArray()
        val checked = BooleanArray(sorted.size) { sorted[it].first in selected }
        dialog()
            .setTitle("Apps to send")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) selected += sorted[which].first else selected -= sorted[which].first
            }
            .setPositiveButton("Save") { _, _ ->
                cfg.apps = selected
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyCode() {
        val code = cfg.code ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Relay pairing code", code))
        if (Build.VERSION.SDK_INT < 33) toast("Code copied")
    }

    private fun shareCode() {
        val code = cfg.code ?: return
        val text = "Relay pairing code: $code\n\nOpen Relay on your second phone, choose Second phone, then paste this code."
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        launch(Intent.createChooser(send, "Send the code to your second phone"))
    }

    private fun confirmNewCode() {
        dialog()
            .setTitle("Make a new pairing code?")
            .setMessage(
                "Your second phone stops receiving until you enter the new code there. " +
                    "Do this if the old code was shared with someone else."
            )
            .setPositiveButton("New code") { _, _ ->
                cfg.code = Pairing.newCode()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendTest() {
        if (cfg.code == null) return
        Relay.send(
            this,
            Relayed(
                kind = Relayed.TEST,
                app = "Relay",
                pkg = Relayed.SELF_PKG,
                title = "Relay is working",
                text = "Test alert from your ${deviceName()}",
                key = "test"
            )
        )
        toast("Test sent. Check your second phone.")
    }

    private fun deviceName(): String {
        val model = Build.MODEL ?: return "main phone"
        val brand = Build.MANUFACTURER ?: ""
        return if (model.startsWith(brand, ignoreCase = true)) {
            model
        } else {
            "${brand.replaceFirstChar { it.uppercase() }} $model".trim()
        }
    }

    // ---------------------------------------------------------------- receiver actions

    private fun pasteInto(input: EditText) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0)?.coerceToText(this)?.toString() else null
        val code = text?.let { Pairing.normalize(it) }
        if (code == null) {
            toast("No pairing code on the clipboard. Copy it on your main phone first.")
            return
        }
        input.setText(code)
    }

    private fun connect(input: EditText) {
        val code = Pairing.normalize(input.text?.toString() ?: "")
        if (code == null) {
            toast("That code doesn't look right. It has 20 letters and numbers.")
            return
        }
        if (code != cfg.code) {
            cfg.code = code
            cfg.lastId = null
            cfg.clearLog()
        }
        input.setText(code)
        cfg.receiving = true
        if (!notificationsAllowed()) askNotifications()
        ReceiverService.start(this)
        refresh()
    }

    private fun disconnect() {
        cfg.receiving = false
        ReceiverService.stop(this)
        refresh()
    }

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_NOTIFY)
        }
    }

    // ---------------------------------------------------------------- shared actions

    private fun batteryOk(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun fixBattery() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        if (!launch(direct)) launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun editServer() {
        val box = ui.column().apply { setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0) }
        box.addView(
            ui.text(
                "Both phones must use the same server. The free ntfy.sh service works out of the box. " +
                    "You can also run your own ntfy server and enter its address here.",
                14f,
                Palette.MUTED
            )
        )
        val server = ui.plainField("https://ntfy.sh", cfg.server)
        val token = ui.plainField("Access token (optional)", cfg.token)
        box.addView(server, ui.matchWrap(14))
        box.addView(token, ui.matchWrap(10))
        dialog()
            .setTitle("Relay server")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                cfg.server = server.text?.toString() ?: ""
                cfg.token = token.text?.toString() ?: ""
                serverChanged()
            }
            .setNeutralButton("Reset") { _, _ ->
                cfg.server = Config.DEFAULT_SERVER
                cfg.token = ""
                serverChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun serverChanged() {
        cfg.lastId = null
        if (cfg.role == Config.ROLE_RECEIVER && cfg.receiving) {
            ReceiverService.stop(this)
            ReceiverService.start(this)
        }
        toast("Saved. Use the same server on both phones.")
    }

    private fun confirmRoleReset() {
        dialog()
            .setTitle("Change this phone's role?")
            .setMessage("Relay stops on this phone and you can set it up again as the main or second phone. Your pairing code is kept.")
            .setPositiveButton("Change") { _, _ ->
                if (cfg.role == Config.ROLE_RECEIVER) {
                    cfg.receiving = false
                    ReceiverService.stop(this)
                }
                cfg.role = ""
                MirrorListenerService.instance?.refreshCallWatcher()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val denied = permissions.filterIndexed { i, _ ->
            grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED
        }
        // Denied with no rationale means "don't ask again": the only way forward is Settings.
        val blocked = denied.isNotEmpty() && denied.none { shouldShowRequestPermissionRationale(it) }
        when (requestCode) {
            RC_CALLS -> {
                MirrorListenerService.instance?.refreshCallWatcher()
                if (blocked) {
                    toast("Allow Phone, Call logs and Contacts under Permissions.")
                    openAppInfo()
                } else if (denied.isNotEmpty()) {
                    toast("Without these, Relay sends your phone app's own call alerts instead.")
                }
            }
            RC_NOTIFY -> if (blocked) {
                toast("Allow notifications so Relay can show alerts.")
                launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
        refresh()
    }

    private fun dialog(): AlertDialog.Builder =
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun launch(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val RC_CALLS = 11
        const val RC_NOTIFY = 12
        val CALL_PERMISSIONS = listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS
        )
    }
}
