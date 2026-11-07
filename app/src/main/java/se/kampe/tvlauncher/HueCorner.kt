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
 * The Hue corner (top left, design/hue-mockups.html H1): a glass bar with the current scene.
 * OK drops a list of the room's or zone's scenes, ◀ ▶ switch room/zone, OK sets a scene, Back
 * closes. Hidden until the bridge answers, and when there's no key (see `./tv.sh hue-key`).
 */
class HueCorner(
    private val activity: Activity,
    private val bar: GlassView,
    private val panel: GlassView,
    private val dim: View,
    /** Called when the bar appears or disappears, to relink focus between the corners. */
    private val onBarVisibility: () -> Unit,
) {
    private val hue = Hue(activity)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val density = activity.resources.displayMetrics.density

    val segment: View = bar.findViewById(R.id.hue_current)
    private val barDot: PaletteDot = bar.findViewById(R.id.hue_dot)
    private val barLabel: TextView = bar.findViewById(R.id.hue_label)
    private val groupLabel: TextView = panel.findViewById(R.id.hue_group)
    private val scroll: ScrollView = panel.findViewById(R.id.hue_scroll)
    private val list: ViewGroup = panel.findViewById(R.id.hue_list)

    private var groups: List<HueGroup> = emptyList()
    /** Index in [groups] of the room/zone the open list shows. */
    private var shown = 0
    private var polling = false

    val isOpen get() = panel.visibility == View.VISIBLE

    init {
        panel.cornerRadius = 18 * density
        panel.elevation = 40 * density
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
        onBarVisibility()
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
        barDot.colors = scene?.colors?.ifEmpty { listOf(WARM_WHITE) } ?: if (group?.on == true) listOf(WARM_WHITE) else emptyList()
    }

    private fun open() {
        if (groups.isEmpty()) return
        val (group, scene) = current()
        shown = groups.indexOf(group).coerceAtLeast(0)
        panel.visibility = View.VISIBLE
        dim.visibility = View.VISIBLE
        showGroup(scene?.id)
    }

    fun close(refocus: Boolean = true) {
        if (!isOpen) return
        panel.visibility = View.GONE
        dim.visibility = View.GONE
        if (refocus) segment.requestFocus()
    }

    /** While the list is open: ◀ ▶ switch room/zone, Back closes. Returns true if handled. */
    fun onKey(event: KeyEvent): Boolean {
        if (!isOpen) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                val step = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                shown = (shown + step + groups.size) % groups.size
                showGroup(null)
            }
            KeyEvent.KEYCODE_BACK -> if (!down) close()
            else -> return false
        }
        return true
    }

    /** Fills the list with the scenes of [shown], focusing [focusId] (else the first). */
    private fun showGroup(focusId: String?) {
        val group = groups[shown]
        groupLabel.text = group.name
        list.removeAllViews()
        val rows = group.scenes.map { scene ->
            row(scene.name, scene.colors.ifEmpty { listOf(WARM_WHITE) }, scene.active && group.on) { choose(group, scene) }
                .also { it.tag = scene.id }
        }.toMutableList()
        rows.forEach(list::addView)
        if (group.lightsId != null) {
            activity.layoutInflater.inflate(R.layout.hue_separator, list, true)
            rows += row(activity.getString(R.string.hue_off), emptyList(), !group.on) { choose(group, null) }
                .also(list::addView)
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
        (rows.firstOrNull { it.tag == focusId } ?: rows.first()).requestFocus()
    }

    private fun row(name: String, colors: List<Int>, checked: Boolean, onClick: () -> Unit): View {
        val row = activity.layoutInflater.inflate(R.layout.hue_row, list, false)
        row.id = View.generateViewId()
        row.findViewById<TextView>(R.id.name).text = name
        row.findViewById<PaletteDot>(R.id.dot).colors = colors
        row.findViewById<View>(R.id.check).visibility = if (checked) View.VISIBLE else View.GONE
        row.setOnClickListener { onClick() }
        return row
    }

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

    companion object {
        private const val TAG = "HueCorner"
        /** For scenes without colour data (brightness only). */
        private val WARM_WHITE = Hue.mirekColor(370)
    }
}
