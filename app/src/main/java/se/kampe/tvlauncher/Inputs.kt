package se.kampe.tvlauncher

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

/**
 * An HDMI input for the home screen's input bar. [on] means the port reports a powered device
 * (a console in standby reports "connected standby" instead).
 */
data class HdmiInput(val id: String, val label: String, val on: Boolean) {
    /** Opens the input full screen in the TV's own Live TV app (org.droidtv.playtv on Philips). */
    fun intent(): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(TvContract.buildChannelUriForPassthroughInput(id), TvContract.Channels.CONTENT_ITEM_TYPE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

object Inputs {

    fun manager(context: Context): TvInputManager? = context.getSystemService(TvInputManager::class.java)

    /**
     * The HDMI inputs worth showing: a device that announced itself over HDMI-CEC (listed under
     * its own name, e.g. "PlayStation 5", instead of its port), a port the user named in the TV's
     * source settings, or a port with something powered on behind it. Empty, unnamed ports and
     * inputs hidden in the TV settings are left out. Sorted by port.
     */
    fun hdmi(context: Context): List<HdmiInput> {
        val tim = manager(context) ?: return emptyList()
        val all = tim.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI && !it.isHidden(context) }
        val portsWithDevice = all.mapNotNull { it.parentId }.toSet()

        return all.filter { it.id !in portsWithDevice }
            .sortedBy { it.parentId ?: it.id }
            .mapNotNull { info ->
                val port = info.parentId ?: info.id
                val on = tim.getInputState(port) == TvInputManager.INPUT_STATE_CONNECTED
                val custom = customLabel(context, info)
                if (custom == null && info.parentId == null && !on) return@mapNotNull null
                HdmiInput(info.id, custom ?: info.loadLabel(context).toString(), on)
            }
    }

    /** The name set in the TV's source settings. Philips appends the chosen icon ("&GAME_CONSOLE"). */
    private fun customLabel(context: Context, info: TvInputInfo): String? =
        info.loadCustomLabel(context)?.toString()?.substringBefore('&')?.trim()?.ifEmpty { null }
}
