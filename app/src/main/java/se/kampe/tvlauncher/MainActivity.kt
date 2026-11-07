package se.kampe.tvlauncher

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast

/**
 * Home screen, layout D ("center stage") from design/mockups.html: muted wallpaper, a large
 * clock and the apps in one glass pill.
 */
class MainActivity : Activity() {

    private data class App(val pkg: String, val name: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val wallpaper = Wallpaper.load(resources, R.drawable.wallpaper)
        findViewById<ImageView>(R.id.wallpaper).setImageBitmap(wallpaper.background)

        val dock = findViewById<GlassView>(R.id.dock)
        dock.glass = wallpaper.glass
        APPS.forEach { app -> makeTile(dock, app)?.let { dock.addView(it) } }
        dock.addView(makeMoreButton(dock))
        dock.getChildAt(0)?.requestFocus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // keep isHome() current when Home brings us back
    }

    /** Started by the Home button (not from another launcher's app list). */
    private fun isHome() = intent?.hasCategory(Intent.CATEGORY_HOME) == true

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // A home screen has nothing to go back to.
        if (!isHome()) super.onBackPressed()
    }

    /** A tile with the app's TV banner, or null if the app isn't installed. */
    private fun makeTile(parent: ViewGroup, app: App): View? {
        val info = try {
            packageManager.getApplicationInfo(app.pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        val tile = layoutInflater.inflate(R.layout.tile, parent, false) as ImageView
        tile.setImageDrawable(
            packageManager.getApplicationBanner(info) ?: packageManager.getApplicationIcon(info)
        )
        tile.contentDescription = packageManager.getApplicationLabel(info)
        tile.clipToOutline = true
        tile.setOnClickListener { launch(app) }
        tile.onFocusChangeListener = focusEffect
        return tile
    }

    private fun makeMoreButton(parent: ViewGroup): View {
        val more = layoutInflater.inflate(R.layout.more_button, parent, false)
        more.setOnClickListener {
            Toast.makeText(this, R.string.more_apps_soon, Toast.LENGTH_SHORT).show()
        }
        more.onFocusChangeListener = focusEffect
        return more
    }

    private val focusEffect = View.OnFocusChangeListener { v, hasFocus ->
        val scale = if (hasFocus) 1.07f else 1f
        val lift = if (hasFocus) 12 * resources.displayMetrics.density else 0f
        v.animate().scaleX(scale).scaleY(scale).translationZ(lift).setDuration(150).start()
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
            App("se.svt.android.svtplay", "SVT Play"),
        )
    }
}
