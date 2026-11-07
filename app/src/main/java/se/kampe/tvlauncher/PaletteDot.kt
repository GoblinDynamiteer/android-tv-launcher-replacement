package se.kampe.tvlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A small disc showing a Hue scene's colours as pie slices, with a thin ring. An empty
 * [colors] list draws the dark "lights off" disc. Darkens its ring on a focused (white) parent
 * when used with duplicateParentState.
 */
class PaletteDot @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var colors: List<Int> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val oval = RectF()

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = ring.strokeWidth / 2
        oval.set(inset, inset, width - inset, height - inset)
        if (colors.isEmpty()) {
            fill.color = Color.rgb(0x22, 0x22, 0x22)
            canvas.drawOval(oval, fill)
        } else {
            val sweep = 360f / colors.size
            colors.forEachIndexed { i, c ->
                fill.color = c
                canvas.drawArc(oval, -90 + i * sweep, sweep, true, fill)
            }
        }
        val focused = android.R.attr.state_focused in drawableState
        ring.color = if (focused) Color.argb(40, 0, 0, 0) else Color.argb(if (colors.isEmpty()) 90 else 64, 255, 255, 255)
        canvas.drawOval(oval, ring)
    }
}
