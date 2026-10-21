package com.vito.relay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

object Palette {
    val BG = 0xFF0D1015.toInt()
    val CARD = 0xFF161A21.toInt()
    val FIELD = 0xFF0F1318.toInt()
    val LINE = 0xFF252B36.toInt()
    val TEXT = 0xFFF2F4F7.toInt()
    val MUTED = 0xFF9AA3B2.toInt()
    val ACCENT = 0xFFB6F35C.toInt()
    val ON_ACCENT = 0xFF0D1015.toInt()
    val SOFT = 0xFF222833.toInt()
    val OK = 0xFF4ADE80.toInt()
    val WARN = 0xFFFBBF24.toInt()
    val BAD = 0xFFF87171.toInt()
}

/** Small helpers so the screens read like a layout rather than plumbing. */
class Ui(private val ctx: Context) {
    private val density = ctx.resources.displayMetrics.density

    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    fun column(): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun row(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun text(value: CharSequence, size: Float = 15f, color: Int = Palette.TEXT, face: Typeface = regular): TextView =
        TextView(ctx).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = face
            setLineSpacing(0f, 1.15f)
        }

    /** Small uppercase label above a group of controls. */
    fun eyebrow(value: String): TextView = text(value.uppercase(), 13f, Palette.MUTED, medium).apply {
        letterSpacing = 0.08f
    }

    fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    fun circle(fill: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    fun card(): LinearLayout = column().apply {
        background = rounded(Palette.CARD, 22, Palette.LINE)
        setPadding(dp(18), dp(18), dp(18), dp(18))
    }

    fun button(label: String, primary: Boolean = true, small: Boolean = false, onClick: () -> Unit): TextView =
        text(label, if (small) 14f else 16f, if (primary) Palette.ON_ACCENT else Palette.TEXT, medium).apply {
            gravity = Gravity.CENTER
            val fill = if (primary) Palette.ACCENT else Palette.SOFT
            val ripple = if (primary) 0x33000000 else 0x33FFFFFF
            background = RippleDrawable(ColorStateList.valueOf(ripple), rounded(fill, 999), null)
            if (small) setPadding(dp(16), dp(9), dp(16), dp(9)) else setPadding(dp(20), dp(15), dp(20), dp(15))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    fun link(label: String, onClick: () -> Unit): TextView = text(label, 14f, Palette.MUTED, medium).apply {
        setPadding(dp(4), dp(10), dp(4), dp(10))
        isClickable = true
        setOnClickListener { onClick() }
    }

    fun dot(color: Int, sizeDp: Int = 10): View = View(ctx).apply {
        background = circle(color)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    fun field(hint: String): EditText = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(0xFF5D6675.toInt())
        setTextColor(Palette.TEXT)
        textSize = 19f
        typeface = medium
        letterSpacing = 0.04f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine(true)
        background = rounded(Palette.FIELD, 14, Palette.LINE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    fun plainField(hint: String, value: String): EditText = EditText(ctx).apply {
        this.hint = hint
        setText(value)
        setHintTextColor(0xFF5D6675.toInt())
        setTextColor(Palette.TEXT)
        textSize = 16f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setSingleLine(true)
        background = rounded(Palette.FIELD, 14, Palette.LINE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    fun switch(checked: Boolean, onChange: (Boolean) -> Unit): Switch = Switch(ctx).apply {
        isChecked = checked
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(Palette.ACCENT, 0xFFC9CFD8.toInt()))
        trackTintList = ColorStateList(states, intArrayOf(0x66B6F35C, 0x33FFFFFF))
        setOnCheckedChangeListener { _, isOn -> onChange(isOn) }
    }

    fun clamp(view: TextView, lines: Int): TextView = view.apply {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }

    fun matchWrap(topDp: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(topDp)
        }

    fun wrap(startDp: Int = 0, topDp: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(startDp)
            topMargin = dp(topDp)
        }

    fun fill(startDp: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(startDp)
        }
}
