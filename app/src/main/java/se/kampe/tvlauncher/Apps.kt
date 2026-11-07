package se.kampe.tvlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.provider.Settings

/** A launchable app; [key] is its package name. [banner] is null if the app only has an icon. */
data class AppEntry(
    val key: String,
    val label: CharSequence,
    val banner: Drawable?,
    val icon: Drawable,
    val intent: Intent,
)

/** Finds the TV apps on the device: everything with a TV launcher entry, plus Settings. */
object Apps {

    private val LAUNCHER = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)

    /** All apps except this launcher, sorted by name, with Settings last. */
    fun installed(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(LAUNCHER, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { ri -> entry(pm, ri, Intent(LAUNCHER).setClassName(ri.activityInfo.packageName, ri.activityInfo.name)) }
            .sortedBy { it.label.toString().lowercase() }
        val settings = settings(pm)?.takeIf { s -> apps.none { it.key == s.key } }
        return apps + listOfNotNull(settings)
    }

    /** The apps for [keys], in that order; keys of apps that aren't installed are skipped. */
    fun forKeys(context: Context, keys: List<String>): List<AppEntry> {
        val pm = context.packageManager
        val settings by lazy { settings(pm) }
        return keys.mapNotNull { key ->
            val intent = pm.getLeanbackLaunchIntentForPackage(key)
            val ri = intent?.let { pm.resolveActivity(it, 0) }
            when {
                intent != null && ri != null -> entry(pm, ri, intent)
                settings?.key == key -> settings
                else -> null
            }
        }
    }

    private fun settings(pm: PackageManager): AppEntry? {
        val intent = Intent(Settings.ACTION_SETTINGS)
        val ri = pm.resolveActivity(intent, 0) ?: return null
        return entry(pm, ri, intent)
    }

    private fun entry(pm: PackageManager, ri: ResolveInfo, intent: Intent) = AppEntry(
        key = ri.activityInfo.packageName,
        label = ri.loadLabel(pm),
        banner = ri.activityInfo.loadBanner(pm),
        icon = ri.loadIcon(pm),
        intent = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
    )
}
