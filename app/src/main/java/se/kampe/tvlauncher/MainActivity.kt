package se.kampe.tvlauncher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView

/**
 * Home screen, layout D ("center stage") from design/mockups.html: muted wallpaper, a large
 * clock and the home apps (see [HomeApps]) in one glass pill, ending in a More apps button.
 *
 * Holding OK on an app lifts it ("move mode"): ◀ ▶ slide it along the dock, OK or Back puts
 * it down and saves the order.
 */
class MainActivity : Activity() {

    private lateinit var dock: GlassView
    private lateinit var moveHint: TextView
    private var dockKeys: List<String>? = null

    /** The tile being moved, or null when not in move mode. */
    private var moving: View? = null
    private var wobble: ObjectAnimator? = null
    /** OK was pressed again after move mode started (so its release ends the move). */
    private var okArmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val wallpaper = Wallpaper.load(resources, R.drawable.wallpaper, Wallpaper.HOME)
        findViewById<ImageView>(R.id.wallpaper).setImageBitmap(wallpaper.background)
        dock = findViewById(R.id.dock)
        dock.glass = wallpaper.glass
        moveHint = findViewById(R.id.move_hint)
    }

    override fun onResume() {
        super.onResume()
        // Home apps may have changed in More apps, or an app was uninstalled.
        val apps = Apps.forKeys(this, HomeApps.load(this))
        if (apps.map { it.key } != dockKeys) buildDock(apps)
    }

    override fun onPause() {
        endMove()
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (moving == null) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) shift(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) shift(+1)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> Unit
            KeyEvent.KEYCODE_BACK -> if (!down) endMove()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down) {
                    if (event.repeatCount == 0) okArmed = true
                } else if (okArmed) {
                    endMove()
                } else {
                    // Release of the long press that started move mode.
                    return super.dispatchKeyEvent(event)
                }
            }
            else -> return super.dispatchKeyEvent(event)
        }
        return true
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
        tile.setOnLongClickListener { startMove(it); true }
        tile.setOnFocusChangeListener { v, hasFocus -> if (moving == null) Tiles.focusEffect(v, hasFocus) }
        return tile
    }

    /** App tiles on the dock (all children except the More apps button at the end). */
    private fun appTiles() = (0 until dock.childCount - 1).map(dock::getChildAt)

    private fun startMove(tile: View) {
        if (appTiles().size < 2) return
        moving = tile
        okArmed = false
        moveHint.visibility = View.VISIBLE
        for (i in 0 until dock.childCount) {
            val v = dock.getChildAt(i)
            if (v !== tile) v.animate().alpha(0.45f).setDuration(150).start()
        }
        val density = resources.displayMetrics.density
        tile.animate().scaleX(1.12f).scaleY(1.12f).translationZ(16 * density).setDuration(150).start()
        wobble = ObjectAnimator.ofFloat(tile, View.ROTATION, -1.5f, 1.5f).apply {
            duration = 260
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun endMove() {
        val tile = moving ?: return
        moving = null
        wobble?.cancel()
        wobble = null
        tile.animate().rotation(0f).setDuration(100).start()
        moveHint.visibility = View.GONE
        for (i in 0 until dock.childCount) dock.getChildAt(i).animate().alpha(1f).setDuration(150).start()
        Tiles.focusEffect(tile, tile.hasFocus())

        val keys = appTiles().map { it.tag as String }
        HomeApps.setOrder(this, keys)
        dockKeys = keys
    }

    /** Swaps the moving tile with its neighbour in [direction] (-1 left, +1 right), animated. */
    private fun shift(direction: Int) {
        val tile = moving ?: return
        val from = dock.indexOfChild(tile)
        val to = from + direction
        if (to < 0 || to >= appTiles().size) return

        // Move the neighbour rather than the focused tile, so focus never leaves it.
        val neighbour = dock.getChildAt(to)
        val tileLeft = tile.left
        val neighbourLeft = neighbour.left
        dock.removeView(neighbour)
        dock.addView(neighbour, from)

        dock.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                dock.viewTreeObserver.removeOnPreDrawListener(this)
                tile.translationX = (tileLeft - tile.left).toFloat()
                neighbour.translationX = (neighbourLeft - neighbour.left).toFloat()
                tile.animate().translationX(0f).setDuration(150).start()
                neighbour.animate().translationX(0f).setDuration(150).start()
                return true
            }
        })
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
