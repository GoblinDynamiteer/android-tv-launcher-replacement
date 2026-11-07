package se.kampe.tvlauncher

import android.content.Context

/** The apps pinned to the home screen dock, in order (package names, kept on the TV). */
object HomeApps {

    private const val PREFS = "home"
    private const val KEY = "apps"
    private val DEFAULT = listOf(
        "com.plexapp.android",
        "com.google.android.youtube.tv",
        "se.svt.android.svtplay",
    )

    fun load(context: Context): List<String> {
        val stored = prefs(context).getString(KEY, null) ?: return DEFAULT
        return if (stored.isEmpty()) emptyList() else stored.split(",")
    }

    /** Adds [key] at the end, or removes it. Returns true if it is now on the home screen. */
    fun toggle(context: Context, key: String): Boolean {
        val keys = load(context).toMutableList()
        val added = !keys.remove(key)
        if (added) keys.add(key)
        save(context, keys)
        return added
    }

    /**
     * Saves a new order for [keys]. Pinned apps that aren't in [keys] (e.g. uninstalled, so
     * not shown on the dock) keep their place at the end.
     */
    fun setOrder(context: Context, keys: List<String>) {
        save(context, keys + load(context).filter { it !in keys })
    }

    private fun save(context: Context, keys: List<String>) {
        prefs(context).edit().putString(KEY, keys.joinToString(",")).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
