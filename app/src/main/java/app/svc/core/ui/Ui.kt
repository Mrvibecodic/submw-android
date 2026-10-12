package app.svc.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.svc.core.R

class Ui(val ctx: Context) {
    val bg = ctx.getColor(R.color.bg)
    val card = ctx.getColor(R.color.card)
    val line = ctx.getColor(R.color.line)
    val text = ctx.getColor(R.color.text)
    val muted = ctx.getColor(R.color.muted)
    val accent = ctx.getColor(R.color.accent)
    val onAccent = ctx.getColor(R.color.on_accent)
    val bad = ctx.getColor(R.color.bad)

    fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun round(color: Int, stroke: Int = 0, r: Int = 12): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat()
        setColor(color)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    fun column(pad: Int = 20): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    fun scroll(child: View): ScrollView = ScrollView(ctx).apply {
        isFillViewport = true
        setBackgroundColor(bg)
        addView(child)
    }

    fun title(s: String, size: Float = 24f): TextView = TextView(ctx).apply {
        text = s
        setTextColor(this@Ui.text)
        textSize = size
        typeface = Typeface.DEFAULT_BOLD
    }

    fun label(s: String, size: Float = 14f, color: Int = muted): TextView = TextView(ctx).apply {
        text = s
        setTextColor(color)
        textSize = size
        setLineSpacing(0f, 1.15f)
    }

    fun input(hint: String, kind: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(ctx).apply {
        this.hint = hint
        inputType = kind
        setSingleLine(true)
        setTextColor(this@Ui.text)
        setHintTextColor(muted)
        textSize = 16f
        background = round(card, line, 10)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        minHeight = dp(50)
    }

    fun button(s: String, primary: Boolean = true, onClick: () -> Unit): Button = Button(ctx).apply {
        text = s
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        setTextColor(if (primary) onAccent else this@Ui.text)
        val base = if (primary) round(accent, 0, 12) else round(card, line, 12)
        background = RippleDrawable(ColorStateList.valueOf(0x33000000), base, null)
        minHeight = dp(52)
        setOnClickListener { onClick() }
    }

    fun check(s: String, on: Boolean, onChange: (Boolean) -> Unit = {}): CheckBox = CheckBox(ctx).apply {
        text = s
        isChecked = on
        setTextColor(this@Ui.text)
        textSize = 15f
        buttonTintList = ColorStateList.valueOf(accent)
        setPadding(dp(6), dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

    fun section(s: String): TextView = TextView(ctx).apply {
        text = s.uppercase()
        setTextColor(muted)
        textSize = 12f
        letterSpacing = 0.08f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(22), 0, dp(8))
    }

    fun box(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = round(card, line, 14)
        setPadding(dp(14), dp(10), dp(14), dp(12))
    }

    fun gap(h: Int): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    fun lp(top: Int = 10): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    fun row(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
}
