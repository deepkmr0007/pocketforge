package dev.pocketforge.ide.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

enum class Btn { PRIMARY, SECONDARY, DANGER, TEXT }

/**
 * Tiny view toolkit over framework widgets (no AndroidX). Keeps every screen consistent:
 * 48dp touch targets, readable type, content descriptions, palette-driven colours.
 */
class Ui(val ctx: Context, val p: Palette) {
    fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun dpf(v: Float): Float = v * ctx.resources.displayMetrics.density

    val mono: Typeface = Typeface.MONOSPACE

    fun rounded(color: Int, radiusDp: Int = 12, strokeColor: Int? = null, strokeDp: Int = 1): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radiusDp.toFloat())
        if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
    }

    fun ripple(content: Drawable?, radiusDp: Int = 12): RippleDrawable {
        val rippleColor = if (p.dark) 0x33FFFFFF else 0x22000000
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, rounded(0xFF000000.toInt(), radiusDp))
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    fun LinearLayout.LayoutParams.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    fun vertical(padDp: Int = 0, block: LinearLayout.() -> Unit = {}): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
        block()
    }

    fun horizontal(padDp: Int = 0, block: LinearLayout.() -> Unit = {}): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
        block()
    }

    fun text(s: CharSequence, sizeSp: Float = 15f, color: Int = p.text, bold: Boolean = false, monoFont: Boolean = false, maxLines: Int = 0): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (monoFont) typeface = mono
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (maxLines > 0) { this.maxLines = maxLines; ellipsize = TextUtils.TruncateAt.END }
            setLineSpacing(0f, 1.15f)
        }

    fun title(s: CharSequence) = text(s, 20f, bold = true)
    fun body(s: CharSequence) = text(s, 15f)
    fun caption(s: CharSequence, color: Int = p.muted) = text(s, 13f, color)

    /** Small uppercase section label. */
    fun section(s: String) = text(s.uppercase(), 12f, p.muted, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(dp(4), dp(18), dp(4), dp(6))
        isFocusable = false
    }

    fun button(label: String, kind: Btn = Btn.SECONDARY, enabled: Boolean = true, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        minHeight = dp(48)
        minWidth = dp(48)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        val (bg, fg, stroke) = when (kind) {
            Btn.PRIMARY -> Triple(p.accent, p.onAccent, null)
            Btn.SECONDARY -> Triple(p.surfaceHi, p.text, p.border)
            Btn.DANGER -> Triple(p.surfaceHi, p.danger, p.danger)
            Btn.TEXT -> Triple(0, p.accent, null)
        }
        setTextColor(fg)
        if (kind != Btn.TEXT) setTypeface(typeface, Typeface.BOLD)
        background = ripple(if (bg == 0) null else rounded(bg, 12, stroke), 12)
        isClickable = true
        isFocusable = true
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
        setOnClickListener { if (isEnabled) onClick() }
    }

    /** Compact action for toolbars. [desc] is read by screen readers. */
    fun iconButton(glyph: String, desc: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = glyph
        contentDescription = desc
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(p.text)
        gravity = Gravity.CENTER
        minWidth = dp(48); minHeight = dp(48)
        setPadding(dp(10), 0, dp(10), 0)
        background = ripple(null, 24)
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    fun setEnabled(v: View, enabled: Boolean) { v.isEnabled = enabled; v.alpha = if (enabled) 1f else 0.45f }

    fun card(padDp: Int = 14, block: LinearLayout.() -> Unit): LinearLayout = vertical(padDp, block).apply {
        background = rounded(p.surface, 14, p.border)
        layoutParams = lp().margins(b = 10)
    }

    fun chip(s: String, color: Int = p.muted): TextView = text(s, 12f, color, bold = true).apply {
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = rounded((color and 0x00FFFFFF) or 0x22000000, 10)
    }

    fun input(hint: String, initial: String = "", singleLine: Boolean = true, monoFont: Boolean = false, password: Boolean = false): EditText = EditText(ctx).apply {
        this.hint = hint
        setText(initial)
        setTextColor(p.text)
        setHintTextColor(p.muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        if (monoFont) typeface = mono
        background = rounded(p.surfaceHi, 10, p.border)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        minHeight = dp(48)
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            singleLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        if (singleLine) { maxLines = 1; setSingleLine(true) }
        contentDescription = hint
    }

    fun labeled(label: String, field: View, help: String? = null): LinearLayout = vertical {
        addView(caption(label).apply { setPadding(dp(2), dp(10), 0, dp(4)) })
        addView(field, lp())
        if (help != null) addView(caption(help).apply { setPadding(dp(2), dp(4), 0, 0) })
    }

    fun switchRow(label: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout = horizontal {
        minimumHeight = dp(56)
        setPadding(0, dp(6), 0, dp(6))
        val texts = vertical {
            addView(body(label))
            if (desc != null) addView(caption(desc))
        }
        addView(texts, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(ctx).apply { isChecked = checked; contentDescription = label; setOnCheckedChangeListener { _, v -> onChange(v) } }
        addView(sw)
        setOnClickListener { sw.toggle() }
    }

    fun checkbox(label: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox = CheckBox(ctx).apply {
        text = label
        setTextColor(p.text)
        isChecked = checked
        minHeight = dp(48)
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    fun spinner(items: List<String>, selected: Int, onSelect: (Int) -> Unit): Spinner = Spinner(ctx).apply {
        val ad = object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply { setTextColor(p.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getDropDownView(position, convertView, parent) as TextView).apply { setTextColor(p.text); minHeight = dp(48); setPadding(dp(16), dp(8), dp(16), dp(8)) }
        }
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        adapter = ad
        setSelection(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        background = rounded(p.surfaceHi, 10, p.border)
        minimumHeight = dp(48)
        setPopupBackgroundDrawable(ColorDrawable(p.surface))
        var first = true
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { if (first) { first = false; return }; onSelect(position) }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    fun divider(): View = View(ctx).apply { setBackgroundColor(p.border); layoutParams = lp(h = dp(1)) }

    fun space(hDp: Int): View = View(ctx).apply { layoutParams = lp(h = dp(hDp)) }

    fun scroll(child: View): ScrollView = ScrollView(ctx).apply {
        isFillViewport = true
        addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun hscroll(child: View): HorizontalScrollView = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        addView(child)
    }

    /** Top app bar: back, title/subtitle, then action buttons. */
    fun topBar(title: String, subtitle: String? = null, onBack: (() -> Unit)?, actions: List<View> = emptyList()): LinearLayout = horizontal {
        setPadding(dp(4), dp(4), dp(4), dp(4))
        setBackgroundColor(p.surface)
        minimumHeight = dp(56)
        if (onBack != null) addView(iconButton("←", "Back", onBack))
        else addView(View(ctx), lp(dp(12), 1))
        val t = vertical {
            addView(text(title, 18f, bold = true, maxLines = 1))
            if (subtitle != null) addView(caption(subtitle).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
        }
        addView(t, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.forEach { addView(it) }
    }

    /** Standard screen: top bar over a padded, scrolling column. Returns (root, column). */
    fun page(bar: View, padDp: Int = 16): Pair<LinearLayout, LinearLayout> {
        val col = vertical(padDp)
        val root = vertical {
            addView(bar, lp())
            addView(divider())
            addView(scroll(col), lp(h = 0, weight = 1f))
        }
        return root to col
    }

    fun list(): ListView = ListView(ctx).apply {
        divider = ColorDrawable(p.border)
        dividerHeight = 1
        selector = ripple(null, 0)
        isFastScrollEnabled = true
        cacheColorHint = 0
    }
}

/** Minimal adapter for ListView rows (framework, virtualised). */
class RowAdapter<T>(var items: List<T>, private val bind: (T, Int, View?) -> View) : BaseAdapter() {
    override fun getCount() = items.size
    override fun getItem(position: Int): T = items[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = bind(items[position], position, convertView)
    fun set(newItems: List<T>) { items = newItems; notifyDataSetChanged() }
}
