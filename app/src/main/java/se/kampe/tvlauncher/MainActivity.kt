package se.kampe.tvlauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

/**
 * Home screen, layout D ("center stage") from design/mockups.html: muted wallpaper, a large
 * clock and the home apps (see [HomeApps]) in one glass pill, ending in a More apps button.
 */
class MainActivity : Activity() {

    private lateinit var dock: GlassView
    private var dockKeys: List<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val wallpaper = Wallpaper.load(resources, R.drawable.wallpaper, Wallpaper.HOME)
        findViewById<ImageView>(R.id.wallpaper).setImageBitmap(wallpaper.background)
        dock = findViewById(R.id.dock)
        dock.glass = wallpaper.glass
    }

    override fun onResume() {
        super.onResume()
        // Home apps may have changed in More apps, or an app was uninstalled.
        val apps = Apps.forKeys(this, HomeApps.load(this))
        if (apps.map { it.key } != dockKeys) buildDock(apps)
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

    private fun buildDock(apps: List<AppEntry>) {
        val focusedKey = dock.focusedChild?.tag
        dock.removeAllViews()
        apps.forEach { dock.addView(makeTile(dock, it)) }
        dock.addView(makeMoreButton(dock))
        dockKeys = apps.map { it.key }

        val focus = (0 until dock.childCount).map(dock::getChildAt).firstOrNull { it.tag == focusedKey }
        (focus ?: dock.getChildAt(0)).requestFocus()
    }

    private fun makeTile(parent: ViewGroup, app: AppEntry): View {
        val tile = layoutInflater.inflate(R.layout.tile, parent, false) as ImageView
        Tiles.bindImage(tile, app)
        tile.tag = app.key
        tile.setOnClickListener { startActivity(app.intent) }
        tile.setOnFocusChangeListener { v, hasFocus -> Tiles.focusEffect(v, hasFocus) }
        return tile
    }

    @Suppress("DEPRECATION")
    private fun makeMoreButton(parent: ViewGroup): View {
        val more = layoutInflater.inflate(R.layout.more_button, parent, false)
        more.setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        more.setOnFocusChangeListener { v, hasFocus -> Tiles.focusEffect(v, hasFocus) }
        return more
    }
}
