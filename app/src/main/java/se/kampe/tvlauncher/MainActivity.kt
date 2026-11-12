package se.kampe.tvlauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/**
 * Home screen, layout D ("center stage") from design/mockups.html: muted wallpaper, a large
 * clock and the home apps (see [HomeApps]) in one glass pill, ending in a More apps button.
 * Top right, a glass bar lists the HDMI inputs (see [Inputs]); top left, [HueCorner] sets Hue
 * scenes. ▲ from the dock reaches them.
 *
 * Holding OK on an app lifts it ("move mode"): ◀ ▶ slide it along the dock, OK or Back puts
 * it down and saves the order.
 */
class MainActivity : Activity() {

    private lateinit var dock: GlassView
    private lateinit var moveHint: TextView
    private var dockKeys: List<String>? = null
    private lateinit var inputBar: GlassView
    private lateinit var inputCollapsing: CollapsingBar
    private lateinit var hue: HueCorner
    private val dockApps = HashMap<String, AppEntry>()

    /** Wallpapers per input or app, faded in while it has focus (design/wallpaper-mockups.html, P2). */
    private lateinit var itemWallpapers: ItemWallpapers
    private lateinit var homeWallpaper: Wallpaper.Result
    private lateinit var wallpaperView: ImageView
    private lateinit var wallpaperFadeView: ImageView
    private lateinit var glassViews: List<GlassView>
    /** Shown in full under the fade layer, and the one being faded in (or null). */
    private lateinit var wallpaperBase: Wallpaper.Result
    private var wallpaperTarget: Wallpaper.Result? = null
    private var wallpaperFade: ValueAnimator? = null
    /** The name of the focused item whose wallpaper is (being) shown, or null for the default. */
    private var wallpaperName: String? = null
    private val lastPick = HashMap<String, String>()
    private val wallpaperHandler = Handler(Looper.getMainLooper())
    /** The inputs and the waking one the bar was built from, to rebuild only on changes. */
    private var shownInputs: Pair<List<HdmiInput>, String?>? = null

    /** The input being woken over the network (design/wake-mockups.html W2), or null. */
    private var wakingId: String? = null
    private var wakingLabel = ""
    private var wakePulse: ObjectAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val wakeTimeout = Runnable {
        Toast.makeText(this, getString(R.string.input_wake_failed, wakingLabel), Toast.LENGTH_LONG).show()
        stopWaking()
    }

    /** Consoles turning on or off, or devices appearing over HDMI-CEC, update the input bar. */
    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(inputId: String, state: Int) = refreshInputs()
        override fun onInputAdded(inputId: String) = refreshInputs()
        override fun onInputRemoved(inputId: String) = refreshInputs()
        override fun onInputUpdated(inputId: String) = refreshInputs()
        override fun onTvInputInfoUpdated(inputInfo: TvInputInfo) = refreshInputs()
    }

    /** The tile being moved, or null when not in move mode. */
    private var moving: View? = null
    private var wobble: ObjectAnimator? = null
    /** OK was pressed again after move mode started (so its release ends the move). */
    private var okArmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val wallpaper = Wallpaper.load(resources, R.drawable.wallpaper, Wallpaper.HOME)
        homeWallpaper = wallpaper
        wallpaperBase = wallpaper
        wallpaperView = findViewById(R.id.wallpaper)
        wallpaperView.setImageBitmap(wallpaper.background)
        wallpaperFadeView = findViewById(R.id.wallpaper_fade)
        itemWallpapers = ItemWallpapers(this)
        dock = findViewById(R.id.dock)
        dock.glass = wallpaper.glass
        inputBar = findViewById(R.id.inputs)
        inputBar.glass = wallpaper.glass
        inputCollapsing = CollapsingBar(inputBar, findViewById(R.id.inputs_icon), { inputBar.getChildAt(1) })
        moveHint = findViewById(R.id.move_hint)

        val hueBar = findViewById<GlassView>(R.id.hue).apply { glass = wallpaper.glass }
        val huePanel = findViewById<GlassView>(R.id.hue_panel).apply { glass = wallpaper.glass }
        hue = HueCorner(this, hueBar, huePanel, findViewById(R.id.hue_dim), ::linkCorners)
        glassViews = listOf(dock, inputBar, hueBar, huePanel)

        // The top corners fold to their icons when focus leaves them.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
            hue.collapsing.onFocusChanged(focused)
            inputCollapsing.onFocusChanged(focused)
            showWallpaperFor(focused)
        }
    }

    override fun onStart() {
        super.onStart()
        Inputs.manager(this)?.registerCallback(inputCallback, Handler(Looper.getMainLooper()))
    }

    override fun onStop() {
        Inputs.manager(this)?.unregisterCallback(inputCallback)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Home apps may have changed in More apps, or an app was uninstalled.
        val apps = Apps.forKeys(this, HomeApps.load(this))
        if (apps.map { it.key } != dockKeys) buildDock(apps)
        refreshInputs()
        hue.start()
        itemWallpapers.prefetch()
    }

    override fun onPause() {
        endMove()
        stopWaking()
        hue.stop()
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (hue.onKey(event)) return true
        if (wakingId != null && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) stopWaking()
            return true
        }
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
        dockApps.clear()
        apps.forEach { dockApps[it.key] = it }
        apps.forEach { dock.addView(makeTile(dock, it)) }
        dock.addView(makeMoreButton(dock))
        dockKeys = apps.map { it.key }
        linkCorners()

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

    /**
     * Updates the input bar (top right) if the HDMI inputs or their on/standby state changed.
     * The segments are rebuilt only when inputs come or go; a state change (on, waking) updates
     * them in place, because removing the focused segment hands focus to the Hue bulb.
     */
    private fun refreshInputs() {
        val inputs = Inputs.hdmi(this)
        // The device being woken has come up: switch to it.
        wakingId?.let { id -> inputs.firstOrNull { it.id == id && it.on } }?.let { awake ->
            stopWaking()
            openInput(awake)
        }
        val state = inputs to wakingId
        if (state == shownInputs) return
        val sameInputs = shownInputs?.first?.map { it.id } == inputs.map { it.id }
        shownInputs = state
        wakePulse?.cancel()
        wakePulse = null

        if (sameInputs) {
            inputSegments().zip(inputs).forEach { (segment, input) -> bindInputSegment(segment, input) }
            return
        }
        val focusedId = inputBar.focusedChild?.tag
        inputBar.removeViews(1, inputBar.childCount - 1) // keep the gamepad icon
        val segments = inputs.mapIndexed { i, input ->
            if (i > 0) layoutInflater.inflate(R.layout.input_divider, inputBar, true)
            makeInputSegment(input).also(inputBar::addView)
        }
        // ▶ on the last input stays in the bar instead of dropping to the dock.
        segments.lastOrNull()?.let { it.nextFocusRightId = it.id }
        inputBar.visibility = if (inputs.isEmpty()) View.GONE else View.VISIBLE
        inputCollapsing.sync()
        linkCorners()
        segments.firstOrNull { it.tag == focusedId }?.requestFocus()
    }

    /** The input segments in the bar, without the gamepad icon and the dividers. */
    private fun inputSegments() = (1 until inputBar.childCount).map(inputBar::getChildAt).filter { it.tag is String }

    /**
     * Links the two top corners and the dock to them: ◀ ▶ move from the Hue bar's last button to
     * the (folded) input bar's icon and from the first input to the Hue bulb, which unfold their
     * bars. At the outer ends focus stays put instead of dropping down to the dock. Called when
     * the dock or either bar changes.
     */
    private fun linkCorners() {
        val inputsIcon = findViewById<View>(R.id.inputs_icon).takeIf { inputBar.visibility == View.VISIBLE }
        val firstInput = inputBar.getChildAt(1)?.takeIf { inputBar.visibility == View.VISIBLE }
        val hueLast = hue.lastSegment
        hue.segment.nextFocusLeftId = hue.segment.id
        hue.segment.nextFocusRightId = View.NO_ID // to the first favourite, if any
        hueLast.nextFocusRightId = inputsIcon?.id ?: hueLast.id
        firstInput?.nextFocusLeftId = if (hue.isVisible) R.id.hue_icon else firstInput?.id ?: View.NO_ID

        // The dock: ◀ from the first tile to the bulb, ▶ from More apps to the gamepad, and ▲
        // from the left half of the dock to the bulb, from the right half to the gamepad (the
        // other corner if one is hidden).
        val hueIcon = R.id.hue_icon.takeIf { hue.isVisible }
        val gamepad = inputsIcon?.id
        val tiles = (0 until dock.childCount).map(dock::getChildAt)
        tiles.forEachIndexed { i, tile ->
            val leftHalf = i < (tiles.size + 1) / 2
            tile.nextFocusUpId = (if (leftHalf) hueIcon ?: gamepad else gamepad ?: hueIcon) ?: View.NO_ID
        }
        tiles.firstOrNull()?.nextFocusLeftId = hueIcon ?: View.NO_ID
        tiles.lastOrNull()?.let { it.nextFocusRightId = gamepad ?: it.id }
    }

    private fun makeInputSegment(input: HdmiInput): View {
        val segment = layoutInflater.inflate(R.layout.input_segment, inputBar, false)
        segment.id = View.generateViewId()
        bindInputSegment(segment, input)
        return segment
    }

    /** Shows [input]'s name and state (on, standby, waking) on [segment]. */
    private fun bindInputSegment(segment: View, input: HdmiInput) {
        segment.tag = input.id
        segment.findViewById<TextView>(R.id.label).text = input.label
        val dot = segment.findViewById<View>(R.id.dot)
        val waking = input.id == wakingId
        val paintDot = { focused: Boolean ->
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(when {
                    waking -> Color.rgb(0xFB, 0xBF, 0x24)
                    input.on -> Color.rgb(0x4A, 0xDE, 0x80)
                    focused -> Color.argb(77, 0, 0, 0)
                    else -> Color.argb(90, 255, 255, 255)
                })
            }
        }
        paintDot(segment.isFocused)
        dot.alpha = 1f
        segment.findViewById<View>(R.id.sub).visibility = if (waking) View.VISIBLE else View.GONE
        if (waking) {
            wakePulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.35f).apply {
                duration = 500
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
        segment.setOnFocusChangeListener { _, hasFocus -> paintDot(hasFocus) }
        segment.setOnClickListener {
            val mac = if (input.on) null else Wake.macFor(this, input.label)
            if (mac != null) startWaking(input, mac) else openInput(input)
        }
    }

    /** Names an item's wallpapers can be added under: the input's or app's label, or the package. */
    private fun wallpaperNames(view: View?): List<String> {
        val tag = view?.tag as? String ?: return emptyList()
        if (view.parent === dock) dockApps[tag]?.let { return listOf(it.label.toString(), it.key) }
        if (view.parent === inputBar) {
            shownInputs?.first?.firstOrNull { it.id == tag }?.let { return listOf(it.label) }
        }
        return emptyList()
    }

    /**
     * Fades to a random one of [focused]'s own wallpapers, or back to the default if it has
     * none. Waits briefly, so sweeping along the dock doesn't decode every tile's images.
     */
    private fun showWallpaperFor(focused: View?) {
        val names = wallpaperNames(focused)
        val files = itemWallpapers.filesFor(names)
        val name = if (files.isEmpty()) null else names.first()
        if (name == wallpaperName) return
        wallpaperName = name
        wallpaperHandler.removeCallbacksAndMessages(null)
        if (name == null) {
            fadeWallpaperTo(homeWallpaper)
            return
        }
        // A new pick each time, if there is more than one.
        val pick = files.filter { it.name != lastPick[name] }.ifEmpty { files }.random()
        lastPick[name] = pick.name
        wallpaperHandler.postDelayed({
            itemWallpapers.load(pick) { result ->
                if (wallpaperName == name) fadeWallpaperTo(result ?: homeWallpaper)
            }
        }, 120)
    }

    /** Cross-fades the background and the glass panels to [target] in 0.5 s. */
    private fun fadeWallpaperTo(target: Wallpaper.Result) {
        val current = wallpaperTarget
        wallpaperFade?.cancel()
        // Whichever of the two layers shows most becomes the base for the next fade.
        if (current != null && wallpaperFadeView.alpha >= 0.5f) setWallpaperBase(current)
        wallpaperTarget = null
        setWallpaperFade(0f)
        if (target === wallpaperBase) return

        wallpaperTarget = target
        wallpaperFadeView.setImageBitmap(target.background)
        glassViews.forEach { it.glassFade = target.glass }
        wallpaperFade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            addUpdateListener { setWallpaperFade(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    setWallpaperBase(target)
                    wallpaperTarget = null
                    setWallpaperFade(0f)
                }
            })
            start()
        }
    }

    private fun setWallpaperBase(result: Wallpaper.Result) {
        wallpaperBase = result
        wallpaperView.setImageBitmap(result.background)
        glassViews.forEach { it.glass = result.glass }
    }

    private fun setWallpaperFade(fade: Float) {
        wallpaperFadeView.alpha = fade
        glassViews.forEach { it.fade = fade }
    }

    private fun openInput(input: HdmiInput) {
        try {
            startActivity(input.intent())
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.input_failed, input.label), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Wakes a sleeping input's device over the network and waits on Home until its HDMI port
     * reports a signal ([refreshInputs] then switches), for at most 45 s. Back cancels.
     */
    private fun startWaking(input: HdmiInput, mac: String) {
        if (wakingId == input.id) return
        stopWaking()
        wakingId = input.id
        wakingLabel = input.label
        // A few packets, in case one gets lost while the network card dozes.
        for (delay in longArrayOf(0, 1500, 4000)) {
            handler.postDelayed({
                if (wakingId == input.id) Thread { runCatching { Wake.send(this, mac) } }.start()
            }, delay)
        }
        handler.postDelayed(wakeTimeout, 45_000)
        refreshInputs()
    }

    private fun stopWaking() {
        if (wakingId == null) return
        wakingId = null
        handler.removeCallbacksAndMessages(null)
        wakePulse?.cancel()
        wakePulse = null
        refreshInputs()
    }

    @Suppress("DEPRECATION")
    private fun makeMoreButton(parent: ViewGroup): View {
        val more = layoutInflater.inflate(R.layout.more_button, parent, false)
        more.id = View.generateViewId() // ▶ from it is linked in linkCorners()
        more.setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        more.setOnFocusChangeListener { v, hasFocus -> Tiles.focusEffect(v, hasFocus) }
        return more
    }
}
