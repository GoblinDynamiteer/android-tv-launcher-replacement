package se.kampe.tvlauncher

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * The Hue corner (top left; design/hue-mockups.html H1 and hue-extras-mockups.html B + F1):
 * a glass bar with the current scene, then the favourite scenes as one-press buttons.
 *
 * OK on the current scene drops a list: a brightness slider (◀ ▶ change it), then the room's or
 * zone's scenes (◀ ▶ switch room/zone, OK sets a scene, holding OK stars it as a favourite).
 * Back closes. Hidden until the bridge answers, and when there's no key (`./tv.sh hue-key`).
 */
class HueCorner(
    private val activity: Activity,
    private val bar: GlassView,
    private val panel: GlassView,
    private val dim: View,
    /** Called when the bar's buttons change, to relink focus between the corners. */
    private val onBarChanged: () -> Unit,
) {
    private val hue = Hue(activity)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val density = activity.resources.displayMetrics.density

    /** The current-scene button, first in the bar. */
    val segment: View = bar.findViewById(R.id.hue_current)
    private val barDot: PaletteDot = bar.findViewById(R.id.hue_dot)
    private val barLabel: TextView = bar.findViewById(R.id.hue_label)
    private val groupLabel: TextView = panel.findViewById(R.id.hue_group)
    private val scroll: ScrollView = panel.findViewById(R.id.hue_scroll)
    private val list: ViewGroup = panel.findViewById(R.id.hue_list)

    /** Folds the bar to its bulb while focus is elsewhere; stays open while the list is. */
    val collapsing = CollapsingBar(bar, bar.findViewById(R.id.hue_icon), { segment }, { isOpen })

    private var groups: List<HueGroup> = emptyList()
    /** Index in [groups] of the room/zone the open list shows. */
    private var shown = 0
    private var slider: View? = null
    /** The room/zone whose brightness is waiting to be sent. */
    private var brightnessGroup: String? = null
    private var polling = false
    /** What the bar's favourite buttons were built from, to rebuild only on changes. */
    private var shownFavourites: List<Favourite>? = null

    /**
     * A favourite button: a scene, or a room's "Lights off" ([key] `off:<room id>`).
     * [colors] empty draws the dark "off" dot.
     */
    private data class Favourite(val key: String, val label: String, val colors: List<Int>)

    val isOpen get() = panel.visibility == View.VISIBLE
    val isVisible get() = bar.visibility == View.VISIBLE

    /** The last button in the bar (◀ from the input bar lands here). */
    val lastSegment: View get() = bar.getChildAt(bar.childCount - 1)

    init {
        panel.cornerRadius = 18 * density
        panel.elevation = 40 * density
        // GlassView lets children overflow (for the dock's focus zoom), but here that would let
        // rows scrolled out of the list draw over the header and the bar.
        panel.clipChildren = true
        dim.elevation = 30 * density
        dim.outlineProvider = null
        segment.setOnClickListener { open() }
    }

    /** Loads the scenes now and then every 30 s while the home screen is showing. */
    fun start() {
        polling = true
        main.removeCallbacks(poll)
        poll.run()
    }

    fun stop() {
        polling = false
        main.removeCallbacks(poll)
        close(refocus = false)
    }

    private val poll: Runnable = object : Runnable {
        override fun run() {
            refresh()
            if (polling) main.postDelayed(this, 30_000)
        }
    }

    private fun refresh() {
        if (hue.key == null) {
            setBarVisible(false)
            return
        }
        io.execute {
            val result = runCatching { hue.groups() }
            main.post {
                result.onSuccess {
                    groups = it
                    updateBar()
                    setBarVisible(it.isNotEmpty())
                }.onFailure {
                    Log.w(TAG, "Hue bridge: $it")
                    if (groups.isEmpty()) setBarVisible(false)
                }
            }
        }
    }

    private fun setBarVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        if (bar.visibility == v) return
        bar.visibility = v
        onBarChanged()
    }

    /**
     * The room/zone and scene to show in the bar: the last picked room's active scene (or
     * "Lights off" if it's off), else any active scene, else the last picked room.
     */
    private fun current(): Pair<HueGroup?, HueScene?> {
        val last = groups.firstOrNull { it.id == hue.lastGroup }
        if (last != null) {
            last.scenes.firstOrNull { it.active }?.let { return last to it }
            if (!last.on) return last to null
        }
        groups.firstNotNullOfOrNull { g -> g.scenes.firstOrNull { it.active }?.let { g to it } }?.let { return it }
        return (last ?: groups.firstOrNull()) to null
    }

    private fun updateBar() {
        val (group, scene) = current()
        barLabel.text = when {
            scene != null -> scene.name
            group?.on == false -> activity.getString(R.string.hue_off)
            else -> group?.name.orEmpty()
        }
        barDot.colors = scene?.let(::colorsOf) ?: if (group?.on == true) listOf(WARM_WHITE) else emptyList()
        updateFavourites()
    }

    /** Rebuilds the favourite buttons after the current scene, if they changed. */
    private fun updateFavourites() {
        val favourites = hue.favourites.mapNotNull { key ->
            val offGroup = key.removePrefix(OFF_PREFIX).takeIf { key.startsWith(OFF_PREFIX) }
            if (offGroup != null) {
                groups.firstOrNull { it.id == offGroup }
                    ?.let { Favourite(key, activity.getString(R.string.hue_group_off, it.name), emptyList()) }
            } else {
                find(key)?.let { (_, scene) -> Favourite(key, scene.name, colorsOf(scene)) }
            }
        }
        if (favourites == shownFavourites) return
        shownFavourites = favourites

        val focusedId = bar.focusedChild?.tag
        bar.removeViews(2, bar.childCount - 2) // keep the bulb and the current scene
        for (fav in favourites) {
            activity.layoutInflater.inflate(R.layout.input_divider, bar, true)
            val button = activity.layoutInflater.inflate(R.layout.hue_favourite, bar, false)
            button.id = View.generateViewId()
            button.tag = fav.key
            button.findViewById<PaletteDot>(R.id.dot).colors = fav.colors
            button.findViewById<TextView>(R.id.label).text = fav.label
            button.setOnClickListener { chooseFavourite(fav.key) }
            bar.addView(button)
        }
        collapsing.sync()
        onBarChanged()
        if (focusedId != null) {
            ((0 until bar.childCount).map(bar::getChildAt).firstOrNull { it.tag == focusedId } ?: segment).requestFocus()
        }
    }

    private fun chooseFavourite(key: String) {
        if (key.startsWith(OFF_PREFIX)) {
            groups.firstOrNull { it.id == key.removePrefix(OFF_PREFIX) }?.let { choose(it, null) }
        } else {
            find(key)?.let { (g, s) -> choose(g, s) }
        }
    }

    private fun find(sceneId: String): Pair<HueGroup, HueScene>? =
        groups.firstNotNullOfOrNull { g -> g.scenes.firstOrNull { it.id == sceneId }?.let { g to it } }

    private fun open() {
        if (groups.isEmpty()) return
        shown = groups.indexOf(current().first).coerceAtLeast(0)
        panel.visibility = View.VISIBLE
        dim.visibility = View.VISIBLE
        showGroup()
    }

    fun close(refocus: Boolean = true) {
        if (!isOpen) return
        panel.visibility = View.GONE
        dim.visibility = View.GONE
        slider = null
        if (refocus) segment.requestFocus()
    }

    /**
     * While the list is open: ◀ ▶ change the brightness on the slider and switch room/zone
     * elsewhere; Back closes. Returns true if handled.
     */
    fun onKey(event: KeyEvent): Boolean {
        if (!isOpen) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                val step = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                if (slider?.hasFocus() == true) {
                    nudgeBrightness(step)
                } else {
                    shown = (shown + step + groups.size) % groups.size
                    showGroup()
                }
            }
            KeyEvent.KEYCODE_BACK -> if (!down) close()
            else -> return false
        }
        return true
    }

    /** Fills the list with the slider and the scenes of [shown], focusing the slider. */
    private fun showGroup() {
        val group = groups[shown]
        groupLabel.text = group.name
        list.removeAllViews()

        val rows = mutableListOf<View>()
        if (group.lightsId != null) {
            val s = activity.layoutInflater.inflate(R.layout.hue_slider, list, false)
            s.id = View.generateViewId()
            list.addView(s)
            rows += s
            slider = s
            showBrightness(group)
        } else {
            slider = null
        }
        for (scene in group.scenes) {
            val row = row(scene.name, colorsOf(scene), scene.active && group.on, scene.id in hue.favourites)
            row.setOnClickListener { choose(group, scene) }
            row.setOnLongClickListener { toggleFavourite(row, scene.id, scene.name); true }
            list.addView(row)
            rows += row
        }
        if (group.lightsId != null) {
            activity.layoutInflater.inflate(R.layout.hue_separator, list, true)
            val offKey = OFF_PREFIX + group.id
            val off = row(activity.getString(R.string.hue_off), emptyList(), !group.on, offKey in hue.favourites)
            off.setOnClickListener { choose(group, null) }
            off.setOnLongClickListener {
                toggleFavourite(off, offKey, activity.getString(R.string.hue_group_off, group.name))
                true
            }
            list.addView(off)
            rows += off
        }
        // Keep ▲ ▼ inside the list.
        rows.first().let { it.nextFocusUpId = it.id }
        rows.last().let { it.nextFocusDownId = it.id }

        // At most ~8.5 rows tall; the half row hints that the list scrolls.
        val rowHeight = 32 * density
        scroll.layoutParams.height =
            if (rows.size > 9) (8.5f * rowHeight).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT
        scroll.requestLayout()
        scroll.scrollTo(0, 0)
        rows.first().requestFocus()
    }

    private fun row(name: String, colors: List<Int>, checked: Boolean, favourite: Boolean): View {
        val row = activity.layoutInflater.inflate(R.layout.hue_row, list, false)
        row.id = View.generateViewId()
        row.findViewById<TextView>(R.id.name).text = name
        row.findViewById<PaletteDot>(R.id.dot).colors = colors
        row.findViewById<View>(R.id.check).visibility = if (checked) View.VISIBLE else View.GONE
        row.findViewById<View>(R.id.star).visibility = if (favourite) View.VISIBLE else View.GONE
        return row
    }

    /** Stars or unstars [key] (a scene id or `off:<room id>`), named [name] in the message. */
    private fun toggleFavourite(row: View, key: String, name: String) {
        val added = hue.toggleFavourite(key)
        row.findViewById<View>(R.id.star).visibility = if (added) View.VISIBLE else View.GONE
        val msg = if (added) R.string.hue_favourite_added else R.string.hue_favourite_removed
        Toast.makeText(activity, activity.getString(msg, name), Toast.LENGTH_SHORT).show()
        updateFavourites()
        row.requestFocus() // rebuilding the bar must not pull focus out of the list
    }

    // --- Brightness ---------------------------------------------------------------------------

    private fun showBrightness(group: HueGroup) {
        val s = slider ?: return
        val percent = if (group.on) group.brightness else 0
        s.findViewById<BrightnessBar>(R.id.bar).percent = percent
        s.findViewById<TextView>(R.id.percent).text =
            if (percent > 0) activity.getString(R.string.percent, percent) else activity.getString(R.string.hue_off_short)
    }

    /** One 10 % step; sent to the bridge once the key has been still for a moment. */
    private fun nudgeBrightness(step: Int) {
        val group = groups[shown]
        val from = if (group.on) group.brightness else 0
        val to = ((from + 5) / 10 * 10 + step * 10).coerceIn(0, 100)
        val updated = group.copy(on = to > 0, brightness = to)
        groups = groups.map { if (it.id == group.id) updated else it }
        showBrightness(updated)
        brightnessGroup = group.id
        main.removeCallbacks(sendBrightness)
        main.postDelayed(sendBrightness, 400)
    }

    private val sendBrightness = Runnable {
        val group = groups.firstOrNull { it.id == brightnessGroup } ?: return@Runnable
        val lightsId = group.lightsId ?: return@Runnable
        val percent = if (group.on) group.brightness else 0
        io.execute {
            val result = runCatching { hue.brightness(lightsId, percent) }
            main.post {
                result.onFailure {
                    Log.w(TAG, "Hue: $it")
                    Toast.makeText(activity, R.string.hue_failed, Toast.LENGTH_SHORT).show()
                }
                updateBar()
            }
        }
    }

    // --- Scenes -------------------------------------------------------------------------------

    /** Sets [scene] in [group], or switches the group off when [scene] is null. */
    private fun choose(group: HueGroup, scene: HueScene?) {
        hue.lastGroup = group.id
        close()
        // Show the result right away; the bridge's state follows in a moment.
        groups = groups.map { g ->
            if (g.id != group.id) g
            else g.copy(on = scene != null, scenes = g.scenes.map { it.copy(active = it.id == scene?.id) })
        }
        updateBar()
        io.execute {
            val result = runCatching { if (scene != null) hue.recall(scene.id) else group.lightsId?.let(hue::off) }
            main.post {
                result.onFailure {
                    Log.w(TAG, "Hue: $it")
                    Toast.makeText(activity, R.string.hue_failed, Toast.LENGTH_SHORT).show()
                }
                main.postDelayed(::refresh, 1500)
            }
        }
    }

    private fun colorsOf(scene: HueScene) = scene.colors.ifEmpty { listOf(WARM_WHITE) }

    companion object {
        private const val TAG = "HueCorner"
        /** Favourite key prefix for a room's "Lights off". */
        private const val OFF_PREFIX = "off:"
        /** For scenes without colour data (brightness only). */
        private val WARM_WHITE = Hue.mirekColor(370)
    }
}
