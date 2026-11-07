package se.kampe.tvlauncher

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.ImageView

object Tiles {

    /** Shows the app's 16:9 banner, or its icon centred on a light glass background. */
    fun bindImage(image: ImageView, app: AppEntry) {
        image.contentDescription = app.label
        image.clipToOutline = true
        if (app.banner != null) {
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.setPadding(0, 0, 0, 0)
            image.backgroundTintList = null
            image.setImageDrawable(app.banner)
        } else {
            val pad = (14 * image.resources.displayMetrics.density).toInt()
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            image.setPadding(pad, pad, pad, pad)
            image.backgroundTintList = ColorStateList.valueOf(Color.argb(40, 255, 255, 255))
            image.setImageDrawable(app.icon)
        }
    }

    /** Grow and lift [target] while [view] has focus. */
    fun focusEffect(target: View, hasFocus: Boolean, scale: Float = 1.07f) {
        val s = if (hasFocus) scale else 1f
        val lift = if (hasFocus) 12 * target.resources.displayMetrics.density else 0f
        target.animate().scaleX(s).scaleY(s).translationZ(lift).setDuration(150).start()
    }
}
