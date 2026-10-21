package com.vito.relay

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The inbox: every conversation mirrored to this phone, from any app, in one place.
 * Shows the list of conversations; tapping one opens its messages.
 */
class InboxActivity : Activity() {

    private lateinit var ui: Ui
    private var openKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val key = openKey
        if (key == null) renderList() else renderThread(key)
    }

    private fun renderList() {
        val root = ui.column().apply { setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(28)) }

        val top = ui.row()
        top.addView(ui.text("Inbox", 28f, Palette.TEXT, ui.bold), ui.fill())
        val threads = Inbox.threads(this)
        if (threads.isNotEmpty()) top.addView(ui.link("Clear") { confirmClear() })
        root.addView(top)
        root.addView(
            ui.text("Every conversation mirrored to this phone, kept in one place.", 15f, Palette.MUTED),
            ui.matchWrap(6)
        )

        if (threads.isEmpty()) {
            val empty = ui.card()
            empty.addView(ui.text("No conversations yet", 17f, Palette.TEXT, ui.medium))
            empty.addView(
                ui.text("Messages and calls mirrored from your main phone will collect here.", 14f, Palette.MUTED),
                ui.matchWrap(6)
            )
            root.addView(empty, ui.matchWrap(20))
            setScroll(root)
            return
        }

        val clock = SimpleDateFormat("d MMM · HH:mm", Locale.getDefault())
        threads.forEach { t ->
            root.addView(threadRow(t, clock), ui.matchWrap(12))
        }
        setScroll(root)
    }

    private fun threadRow(t: Inbox.Thread, clock: SimpleDateFormat): View {
        val card = ui.card().apply {
            isClickable = true
            setOnClickListener {
                openKey = t.key
                render()
            }
        }
        val head = ui.row()
        head.addView(ui.dot(colorFor(t.pkg), 10))
        head.addView(ui.text(t.name.ifEmpty { t.app }, 17f, Palette.TEXT, ui.medium), ui.fill(startDp = 10))
        head.addView(ui.text(t.app, 12f, Palette.MUTED, ui.medium))
        card.addView(head)
        if (t.preview.isNotEmpty()) {
            card.addView(ui.clamp(ui.text(t.preview, 14f, Palette.MUTED), 2), ui.matchWrap(8).apply {
                leftMargin = ui.dp(20)
            })
        }
        card.addView(
            ui.text(clock.format(Date(t.updated)), 12f, Palette.MUTED),
            ui.matchWrap(6).apply { leftMargin = ui.dp(20) }
        )
        return card
    }

    private fun renderThread(key: String) {
        val threads = Inbox.threads(this)
        val meta = threads.firstOrNull { it.key == key }
        val root = ui.column().apply { setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(28)) }

        val top = ui.row()
        top.addView(ui.button("Back", primary = false, small = true) {
            openKey = null
            render()
        })
        top.addView(View(this), ui.fill())
        root.addView(top)

        root.addView(
            ui.text(meta?.name ?: "Conversation", 24f, Palette.TEXT, ui.bold),
            ui.matchWrap(14)
        )
        if (meta != null) {
            root.addView(ui.text(meta.app, 14f, Palette.MUTED), ui.matchWrap(2))
        }

        val messages = Inbox.messages(this, key)
        val clock = SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault())
        if (messages.isEmpty()) {
            root.addView(ui.text("No messages.", 14f, Palette.MUTED), ui.matchWrap(18))
        } else {
            messages.forEach { m ->
                root.addView(bubble(m, clock), ui.matchWrap(12))
            }
        }
        setScroll(root)
    }

    private fun bubble(m: Inbox.Message, clock: SimpleDateFormat): View {
        val card = ui.column().apply {
            background = ui.rounded(Palette.CARD, 16, Palette.LINE)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        }
        val isCall = m.kind == Relayed.RING || m.kind == Relayed.ANSWERED ||
            m.kind == Relayed.MISSED || m.kind == Relayed.DECLINED
        if (isCall) {
            val row = ui.row()
            row.addView(ui.text("Phone", 12f, Palette.MUTED, ui.medium))
            card.addView(row)
        }
        card.addView(ui.text(m.text.ifEmpty { "(no text)" }, 15f, Palette.TEXT))
        card.addView(ui.text(clock.format(Date(m.time)), 12f, Palette.MUTED), ui.matchWrap(6))
        return card
    }

    private fun confirmClear() {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Clear the inbox?")
            .setMessage("Removes the mirrored conversation history from this phone. New messages still collect afterwards.")
            .setPositiveButton("Clear") { _, _ ->
                Inbox.clear(this)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun colorFor(pkg: String): Int = when {
        pkg.startsWith("com.whatsapp") -> 0xFF25D366.toInt()
        pkg.contains("messag") || pkg == "com.android.mms" -> 0xFF4C8DF6.toInt()
        pkg == Relayed.CALL_PKG -> 0xFF34C46A.toInt()
        else -> Palette.ACCENT
    }

    private fun setScroll(root: LinearLayout) {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Palette.BG)
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
    }

    override fun onBackPressed() {
        if (openKey != null) {
            openKey = null
            render()
        } else {
            super.onBackPressed()
        }
    }
}
