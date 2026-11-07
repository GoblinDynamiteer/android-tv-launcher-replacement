package se.kampe.tvlauncher

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Proof of concept: a dark screen with one tile per app in [APPS]. */
class MainActivity : Activity() {

    private data class App(val pkg: String, val name: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val row = findViewById<LinearLayout>(R.id.tiles)
        APPS.forEach { row.addView(makeTile(row, it)) }
        row.getChildAt(0)?.requestFocus()
    }

    private fun makeTile(parent: ViewGroup, app: App): View {
        val tile = layoutInflater.inflate(R.layout.tile, parent, false)
        val image = tile.findViewById<ImageView>(R.id.tile_image)
        val label = tile.findViewById<TextView>(R.id.tile_label)

        try {
            val info = packageManager.getApplicationInfo(app.pkg, 0)
            // TV apps ship a 16:9 banner; fall back to the square icon.
            image.setImageDrawable(
                packageManager.getApplicationBanner(info)
                    ?: packageManager.getApplicationIcon(info)
            )
            label.text = packageManager.getApplicationLabel(info)
        } catch (e: PackageManager.NameNotFoundException) {
            label.text = getString(R.string.not_installed, app.name)
        }

        tile.setOnClickListener { launch(app) }
        tile.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.08f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }
        return tile
    }

    private fun launch(app: App) {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(app.pkg)
            ?: packageManager.getLaunchIntentForPackage(app.pkg)
        if (intent == null) {
            Toast.makeText(this, getString(R.string.not_installed, app.name), Toast.LENGTH_SHORT)
                .show()
            return
        }
        startActivity(intent)
    }

    private companion object {
        val APPS = listOf(
            App("com.plexapp.android", "Plex"),
            App("com.google.android.youtube.tv", "YouTube"),
        )
    }
}
