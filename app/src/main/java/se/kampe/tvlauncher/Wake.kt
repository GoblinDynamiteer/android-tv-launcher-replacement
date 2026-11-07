package se.kampe.tvlauncher

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Wake-on-LAN for HDMI inputs whose device sleeps (the Steam Machine). The MAC address per
 * input name is set with `./tv.sh wake-mac "<input name>" <mac>`, kept in files/wake_macs as
 * `<name>\t<mac>` lines.
 */
object Wake {

    private const val TAG = "Wake"

    /** The MAC address configured for the input named [label], if any. */
    fun macFor(context: Context, label: String): String? {
        val file = File(context.filesDir, "wake_macs").takeIf { it.exists() } ?: return null
        return file.readLines()
            .map { it.split('\t') }
            .firstOrNull { it.size == 2 && it[0].trim().equals(label.trim(), ignoreCase = true) }
            ?.get(1)?.trim()
    }

    /** Sends a magic packet for [mac] to the local network's broadcast addresses. Blocks briefly. */
    fun send(context: Context, mac: String) {
        val bytes = mac.split(':', '-').map { it.toInt(16).toByte() }.toByteArray()
        require(bytes.size == 6) { "Bad MAC address: $mac" }
        val packet = ByteArray(6) { 0xFF.toByte() } + (1..16).flatMap { bytes.toList() }
        val targets = (broadcastAddresses(context) + InetAddress.getByName("255.255.255.255")).distinct()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (address in targets) {
                try {
                    socket.send(DatagramPacket(packet, packet.size, address, 9))
                } catch (e: Exception) {
                    Log.w(TAG, "Magic packet to $address: $e")
                }
            }
        }
        Log.i(TAG, "Magic packet for $mac sent to $targets")
    }

    /** The IPv4 broadcast address of the active network (e.g. 192.168.50.255). */
    private fun broadcastAddresses(context: Context): List<InetAddress> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val links = cm.getLinkProperties(cm.activeNetwork)?.linkAddresses ?: return emptyList()
        return links.filter { it.address is Inet4Address }.map { link ->
            val ip = link.address.address
            val hostBits = 32 - link.prefixLength
            val mask = if (hostBits >= 32) 0 else (-1 shl hostBits)
            val addr = ((ip[0].toInt() and 0xFF) shl 24) or ((ip[1].toInt() and 0xFF) shl 16) or
                ((ip[2].toInt() and 0xFF) shl 8) or (ip[3].toInt() and 0xFF)
            val broadcast = addr or mask.inv()
            InetAddress.getByAddress(ByteArray(4) { i -> (broadcast shr (24 - 8 * i)).toByte() })
        }
    }
}
