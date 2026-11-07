package se.kampe.tvlauncher

import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View
import android.view.ViewGroup

/**
 * Folds a top-corner glass bar down to its [icon] while focus is elsewhere. Focus landing on the
 * icon (▲ from the dock, or ◀ ▶ from the other corner) unfolds the bar and moves on to [entry],
 * its first button. Focus leaving the bar folds it again, unless [keepExpanded] (e.g. while the
 * Hue scene list is open).
 */
class CollapsingBar(
    private val bar: ViewGroup,
    private val icon: View,
    private val entry: () -> View?,
    private val keepExpanded: () -> Boolean = { false },
) {
    var expanded = true
        private set

    private val iconMargins = (icon.layoutParams as ViewGroup.MarginLayoutParams).let { it.marginStart to it.marginEnd }

    init {
        icon.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                expand()
                entry()?.requestFocus()
                icon.isFocusable = false
            }
        }
        expanded = false
        sync()
    }

    /** Call with the newly focused view on every focus change in the window. */
    fun onFocusChanged(focused: View?) {
        if (focused != null && isInside(focused)) return
        if (!keepExpanded()) collapse()
    }

    fun expand() {
        if (expanded) return
        animate()
        expanded = true
        sync()
    }

    fun collapse() {
        if (!expanded) return
        animate()
        expanded = false
        sync()
    }

    /** Applies the state to all children; call after adding buttons to the bar. */
    fun sync() {
        for (i in 0 until bar.childCount) {
            val child = bar.getChildAt(i)
            if (child !== icon) child.visibility = if (expanded) View.VISIBLE else View.GONE
        }
        // Folded, the bar is a circle around the icon, and the icon is what takes focus.
        (icon.layoutParams as ViewGroup.MarginLayoutParams).apply {
            marginStart = if (expanded) iconMargins.first else 0
            marginEnd = if (expanded) iconMargins.second else 0
        }
        icon.requestLayout()
        if (!expanded) icon.isFocusable = true
    }

    private fun isInside(view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === bar) return true
            v = v.parent as? View
        }
        return false
    }

    private fun animate() {
        val root = bar.parent as? ViewGroup ?: return
        if (!bar.isLaidOut) return
        TransitionManager.beginDelayedTransition(
            root,
            TransitionSet().addTransition(ChangeBounds()).addTransition(Fade()).setDuration(180),
        )
    }
}
