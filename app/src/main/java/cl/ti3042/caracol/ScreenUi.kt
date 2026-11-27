package cl.ti3042.caracol

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputFilter
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

internal class ScreenUi(private val context: Context) {
    val background = Color.rgb(248, 246, 251)
    val ink = Color.rgb(37, 29, 48)
    val softInk = Color.rgb(88, 79, 104)
    val primary = Color.rgb(73, 60, 135)
    val lavender = Color.rgb(237, 231, 251)
    val outline = Color.rgb(208, 199, 222)
    val positive = Color.rgb(35, 103, 78)
    val warning = Color.rgb(132, 68, 39)

    fun dp(size: Int) = (size * context.resources.displayMetrics.density + .5f).toInt()

    fun column(gap: Int = 0): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (gap > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setSize(1, dp(gap))
            }
        }
    }

    fun row(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    fun label(value: String, size: Float = 15f, bold: Boolean = false, color: Int = ink): TextView =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    fun button(value: String, primaryAction: Boolean = false, action: () -> Unit): Button =
        Button(context).apply {
            text = value
            textSize = 15f
            isAllCaps = false
            minHeight = dp(48)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = RippleDrawable(
                ColorStateList.valueOf(0x227f6ac5),
                rounded(if (primaryAction) primary else lavender, 18), null
            )
            setTextColor(if (primaryAction) Color.WHITE else primary)
            setOnClickListener { action() }
        }

    fun field(hintText: String, input: Int, limit: Int = 128): EditText = EditText(context).apply {
        id = View.generateViewId()
        hint = hintText
        inputType = input
        setSingleLine(true)
        filters = arrayOf(InputFilter.LengthFilter(limit))
        minHeight = dp(52)
        textSize = 16f
        setTextColor(ink)
        setHintTextColor(softInk)
        setPadding(dp(15), dp(9), dp(15), dp(9))
        background = rounded(Color.WHITE, 13, outline)
    }

    fun card(title: String? = null): LinearLayout = column(9).apply {
        background = rounded(Color.WHITE, 22, outline)
        setPadding(dp(16), dp(15), dp(16), dp(15))
        title?.let { addView(label(it, 17f, true)) }
    }

    fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    fun item(parent: LinearLayout, view: View) {
        parent.addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    fun weighted(parent: LinearLayout, view: View, spacing: Int = 0) {
        parent.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(spacing)
        })
    }

    fun enable(button: Button?, active: Boolean) {
        button?.isEnabled = active
        button?.alpha = if (active) 1f else .47f
    }
}
