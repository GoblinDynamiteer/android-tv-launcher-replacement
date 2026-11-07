package se.kampe.tvlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * The track, fill and knob of the Hue brightness slider; [percent] 0-100. White on the glass,
 * dark when its (white) row is focused, via duplicateParentState.
 */
class BrightnessBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var percent = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val focused = android.R.attr.state_focused in drawableState
        val knob = height / 2f
        val cy = height / 2f
        val half = 2 * density
        val left = knob
        val right = width - knob
        val x = left + (right - left) * percent / 100f

        paint.color = if (focused) Color.argb(38, 0, 0, 0) else Color.argb(46, 255, 255, 255)
        canvas.drawRoundRect(left, cy - half, right, cy + half, half, half, paint)
        paint.color = if (focused) Color.rgb(0x11, 0x11, 0x11) else Color.argb(230, 255, 255, 255)
        canvas.drawRoundRect(left, cy - half, x, cy + half, half, half, paint)
        canvas.drawCircle(x, cy, knob, paint)
    }
}
