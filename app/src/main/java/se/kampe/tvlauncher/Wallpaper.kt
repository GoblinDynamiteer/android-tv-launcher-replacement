package se.kampe.tvlauncher

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import java.io.File
import kotlin.math.sqrt

/**
 * Prepares the wallpaper once at start-up: a muted, blurred background and an extra-blurred
 * copy for the glass panels to draw behind themselves. Android 11 has no live blur
 * (RenderEffect is API 31) and the TV's CPU is weak, so this is done on a quarter-size
 * bitmap; it's blurred anyway, so scaling it up to the screen costs no detail.
 *
 * Values follow the mockups in design/ (CSS px on the 1920 px canvas / 4). Results are cached
 * for the life of the process.
 */
object Wallpaper {

    class Result(val background: Bitmap, val glass: Bitmap?)

    /** [blur] and [glassBlur] are box-blur radii on the quarter-size bitmap. */
    data class Style(val brightness: Float, val saturation: Float, val blur: Int, val glassBlur: Int?)

    /** Home screen: brightness(.55) saturate(.7) blur(14px); glass blur(28px). */
    val HOME = Style(brightness = 0.55f, saturation = 0.7f, blur = 3, glassBlur = 6)

    /** More apps: brightness(.38) saturate(.6) blur(24px), no glass. */
    val DRAWER = Style(brightness = 0.38f, saturation = 0.6f, blur = 6, glassBlur = null)

    /**
     * A focused item's own wallpaper (design/wallpaper-mockups.html, P2): brightness(.68)
     * saturate(.95) blur(4px). Rendered at half size, [ITEM_WIDTH], so the art stays
     * recognisable; [blur] is on that half-size bitmap, the glass is made at quarter size.
     */
    val ITEM = Style(brightness = 0.68f, saturation = 0.95f, blur = 1, glassBlur = 6)
    private const val ITEM_WIDTH = 960
    private const val GLASS_WIDTH = 480

    private const val SAMPLE = 4             // decode at 1/4 size: 480x270
    private const val GLASS_SATURATION = 1.3f
    private val VIGNETTE = Color.argb(115, 0, 0, 0) // rgba(0,0,0,.45) at the corners
    private val cache = HashMap<Pair<Int, Style>, Result>()

    fun load(res: Resources, resId: Int, style: Style): Result =
        cache.getOrPut(resId to style) { render(res, resId, style) }

    private fun render(res: Resources, resId: Int, style: Style): Result {
        val options = BitmapFactory.Options().apply {
            inSampleSize = SAMPLE
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return render(BitmapFactory.decodeResource(res, resId, options), style)
    }

    /**
     * Renders an image file in [style] at [ITEM_WIDTH] x 16:9, cropped to the centre like the
     * screen would. Null if the file can't be decoded. Slow (decode and blur): call it off the
     * main thread.
     */
    fun fromFile(file: File, style: Style): Result? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= ITEM_WIDTH) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null

        // Centre-crop to 16:9 and scale to ITEM_WIDTH (the glass maps it onto the whole window).
        val w = decoded.width
        val h = decoded.height
        val crop = if (w * 9 > h * 16) {
            val cw = h * 16 / 9
            Rect((w - cw) / 2, 0, (w - cw) / 2 + cw, h)
        } else {
            val ch = w * 9 / 16
            Rect(0, (h - ch) / 2, w, (h - ch) / 2 + ch)
        }
        val source = Bitmap.createBitmap(ITEM_WIDTH, ITEM_WIDTH * 9 / 16, Bitmap.Config.ARGB_8888)
        Canvas(source).drawBitmap(decoded, crop, Rect(0, 0, source.width, source.height), Paint(Paint.FILTER_BITMAP_FLAG))
        decoded.recycle()
        return render(source, style)
    }

    /** Mutes, blurs and vignettes [source] (recycled) and makes the glass copy. */
    private fun render(source: Bitmap, style: Style): Result {
        val mute = ColorMatrix().apply { setSaturation(style.saturation) }
        mute.postConcat(ColorMatrix().apply {
            setScale(style.brightness, style.brightness, style.brightness, 1f)
        })
        val background = filtered(source, mute)
        source.recycle()
        blur(background, style.blur)
        vignette(background)

        val glass = style.glassBlur?.let { radius ->
            val small = if (background.width > GLASS_WIDTH) {
                Bitmap.createScaledBitmap(background, GLASS_WIDTH, background.height * GLASS_WIDTH / background.width, true)
            } else {
                background
            }
            filtered(small, ColorMatrix().apply { setSaturation(GLASS_SATURATION) })
                .also { if (small !== background) small.recycle() }
                .also { blur(it, radius) }
        }
        return Result(background, glass)
    }

    private fun filtered(src: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
        return out
    }

    /** CSS radial-gradient(ellipse at center, transparent 30%, rgba(0,0,0,.45)). */
    private fun vignette(bmp: Bitmap) {
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val radius = w / 2 * sqrt(2f) // ellipse through the corners
        val shader = RadialGradient(
            w / 2, h / 2, radius,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, VIGNETTE),
            floatArrayOf(0f, 0.3f, 1f),
            Shader.TileMode.CLAMP,
        )
        shader.setLocalMatrix(Matrix().apply { setScale(1f, h / w, w / 2, h / 2) })
        Canvas(bmp).drawRect(0f, 0f, w, h, Paint().apply { this.shader = shader })
    }

    /** Three box-blur passes per direction, close to a Gaussian. Edges are clamped. */
    private fun blur(bmp: Bitmap, radius: Int) {
        val w = bmp.width
        val h = bmp.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        bmp.getPixels(a, 0, w, 0, 0, w, h)
        repeat(3) {
            boxPass(a, b, lines = h, len = w, lineStep = w, step = 1, r = radius)
            boxPass(b, a, lines = w, len = h, lineStep = 1, step = w, r = radius)
        }
        bmp.setPixels(a, 0, w, 0, 0, w, h)
    }

    private fun boxPass(
        src: IntArray, dst: IntArray, lines: Int, len: Int, lineStep: Int, step: Int, r: Int,
    ) {
        val div = 2 * r + 1
        for (line in 0 until lines) {
            val start = line * lineStep
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[start + i.coerceIn(0, len - 1) * step]
                sr += p shr 16 and 0xff
                sg += p shr 8 and 0xff
                sb += p and 0xff
            }
            for (i in 0 until len) {
                dst[start + i * step] =
                    (0xff shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val out = src[start + (i - r).coerceAtLeast(0) * step]
                val inn = src[start + (i + r + 1).coerceAtMost(len - 1) * step]
                sr += (inn shr 16 and 0xff) - (out shr 16 and 0xff)
                sg += (inn shr 8 and 0xff) - (out shr 8 and 0xff)
                sb += (inn and 0xff) - (out and 0xff)
            }
        }
    }
}
