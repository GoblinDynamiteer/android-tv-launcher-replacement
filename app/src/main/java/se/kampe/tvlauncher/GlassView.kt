package se.kampe.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout

/**
 * A pill-shaped (or, with [cornerRadius], rounded) "frosted glass" panel: draws the part of the
 * pre-blurred [glass] wallpaper that lies behind it, a light white fill and a thin border.
 * [glass] must cover the whole window (same aspect ratio), as the wallpaper does.
 */
class GlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var glass: Bitmap? = null
        set(value) {
            field = value
            glassPaint.shader = value?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            invalidate()
        }

    /** Corner radius in px; negative (the default) makes a pill with fully rounded ends. */
    var cornerRadius = -1f
        set(value) {
            field = value
            invalidateOutline()
            invalidate()
        }

    private fun radius() = if (cornerRadius < 0) height / 2f else cornerRadius

    private val density = resources.displayMetrics.density
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(26, 255, 255, 255) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = Color.argb(41, 255, 255, 255)
    }
    private val rect = RectF()
    private val shaderMatrix = Matrix()
    private val location = IntArray(2)

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius())
                outline.alpha = 0.25f
            }
        }
        elevation = 10 * density
    }

    override fun onDraw(canvas: Canvas) {
        val radius = radius()
        rect.set(0f, 0f, width.toFloat(), height.toFloat())

        glass?.let { bmp ->
            // Map the bitmap onto the window, then shift it to this view's position.
            getLocationInWindow(location)
            val root = rootView
            shaderMatrix.setScale(root.width.toFloat() / bmp.width, root.height.toFloat() / bmp.height)
            shaderMatrix.postTranslate(-location[0].toFloat(), -location[1].toFloat())
            glassPaint.shader.setLocalMatrix(shaderMatrix)
            canvas.drawRoundRect(rect, radius, radius, glassPaint)
        }
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        val half = borderPaint.strokeWidth / 2
        rect.inset(half, half)
        canvas.drawRoundRect(rect, radius - half, radius - half, borderPaint)
    }
}
