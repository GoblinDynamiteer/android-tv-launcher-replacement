package se.kampe.tvlauncher

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * "More apps": all TV apps, with the ones on the home screen in their own section
 * (mockups M3 + S2 in design/apps-mockups.html). OK opens an app; a long press on OK adds
 * it to or removes it from the home screen.
 */
class AppsActivity : Activity() {

    private lateinit var homeGrid: GridLayout
    private lateinit var allGrid: GridLayout
    private lateinit var homeEmpty: TextView
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)
        findViewById<ImageView>(R.id.wallpaper).setImageBitmap(
            Wallpaper.load(resources, R.drawable.wallpaper, Wallpaper.DRAWER).background
        )
        homeGrid = findViewById(R.id.home_grid)
        allGrid = findViewById(R.id.all_grid)
        homeEmpty = findViewById(R.id.home_empty)
        hint = findViewById(R.id.hint)
        render(focusKey = null)
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /** Rebuilds both sections and focuses [focusKey]'s tile (or the first one). */
    private fun render(focusKey: String?) {
        val apps = Apps.installed(this)
        val home = HomeApps.load(this)
        val byKey = apps.associateBy { it.key }

        homeGrid.removeAllViews()
        allGrid.removeAllViews()
        home.mapNotNull { byKey[it] }.forEach { homeGrid.addView(makeTile(homeGrid, it)) }
        apps.filter { it.key !in home }.forEach { allGrid.addView(makeTile(allGrid, it)) }
        homeEmpty.visibility = if (homeGrid.childCount == 0) View.VISIBLE else View.GONE

        val target = focusKey?.let { key -> findTile(homeGrid, key) ?: findTile(allGrid, key) }
            ?: homeGrid.getChildAt(0) ?: allGrid.getChildAt(0)
        target?.requestFocus()
    }

    private fun findTile(grid: ViewGroup, key: String): View? =
        (0 until grid.childCount).map(grid::getChildAt).firstOrNull { it.tag == key }

    private fun makeTile(parent: ViewGroup, app: AppEntry): View {
        val tile = layoutInflater.inflate(R.layout.grid_tile, parent, false)
        val image = tile.findViewById<ImageView>(R.id.tile_image)
        Tiles.bindImage(image, app)
        tile.findViewById<TextView>(R.id.tile_label).text = app.label
        tile.tag = app.key
        tile.setOnClickListener { startActivity(app.intent) }
        tile.setOnLongClickListener {
            HomeApps.toggle(this, app.key)
            render(focusKey = app.key)
            true
        }
        tile.setOnFocusChangeListener { _, hasFocus ->
            Tiles.focusEffect(image, hasFocus, scale = 1.06f)
            if (hasFocus) showHint(app)
        }
        return tile
    }

    private fun showHint(app: AppEntry) {
        val action = if (app.key in HomeApps.load(this)) R.string.hint_remove else R.string.hint_add
        hint.text = getString(R.string.hint, app.label, getString(action))
    }
}
