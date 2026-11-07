package se.kampe.tvlauncher

import android.content.Context
import android.graphics.Color
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** A Hue scene; [colors] are its lights' colours (deduplicated), for the palette dot. */
data class HueScene(val id: String, val name: String, val colors: List<Int>, val active: Boolean)

/**
 * A room or zone with scenes. [lightsId] is its grouped_light, used for brightness and to
 * switch it off; [brightness] is in percent (0-100).
 */
data class HueGroup(
    val id: String,
    val name: String,
    val lightsId: String?,
    val on: Boolean,
    val brightness: Int,
    val scenes: List<HueScene>,
)

/**
 * Philips Hue bridge over the local CLIP v2 API. The application key is copied onto the TV
 * with `./tv.sh hue-key` (files/hue_key); the bridge is found over mDNS and its address cached.
 * All calls block: run them off the main thread.
 */
class Hue(private val context: Context) {

    private val prefs = context.getSharedPreferences("hue", Context.MODE_PRIVATE)

    val key: String? get() = File(context.filesDir, "hue_key").takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    /** Rooms and zones that have scenes, the most scenes first. */
    fun groups(): List<HueGroup> {
        val rooms = get("room") + get("zone")
        val scenes = get("scene")
        val lights = get("grouped_light").associateBy { it.getJSONObject("owner").getString("rid") }
        return rooms.map { room ->
            val id = room.getString("id")
            val lightsId = room.optJSONArray("services")?.objects()
                ?.firstOrNull { it.getString("rtype") == "grouped_light" }?.getString("rid")
            HueGroup(
                id = id,
                name = room.getJSONObject("metadata").getString("name"),
                lightsId = lightsId,
                on = lights[id]?.optJSONObject("on")?.optBoolean("on") == true,
                brightness = lights[id]?.optJSONObject("dimming")?.optDouble("brightness", 0.0)?.roundToInt() ?: 0,
                scenes = scenes.filter { it.getJSONObject("group").getString("rid") == id }.map(::scene),
            )
        }.filter { it.scenes.isNotEmpty() }.sortedWith(compareByDescending<HueGroup> { it.scenes.size }.thenBy { it.name })
    }

    fun recall(sceneId: String) = put("scene/$sceneId", JSONObject().put("recall", JSONObject().put("action", "active")))

    fun off(lightsId: String) = put("grouped_light/$lightsId", JSONObject().put("on", JSONObject().put("on", false)))

    /** Sets a room's brightness in percent; 0 switches it off, anything else also switches it on. */
    fun brightness(lightsId: String, percent: Int) {
        if (percent <= 0) return off(lightsId)
        put(
            "grouped_light/$lightsId",
            JSONObject().put("on", JSONObject().put("on", true)).put("dimming", JSONObject().put("brightness", percent)),
        )
    }

    /**
     * Favourites starred in the list (held OK), shown as buttons in the bar: scene ids, or
     * `off:<room id>` for a room's "Lights off". Kept on the TV.
     */
    var favourites: List<String>
        get() = prefs.getString("favourites", "").orEmpty().split(",").filter { it.isNotEmpty() }
        set(value) = prefs.edit().putString("favourites", value.joinToString(",")).apply()

    /** Stars or unstars [key]. Returns true if it's now a favourite. */
    fun toggleFavourite(key: String): Boolean {
        val favs = favourites.toMutableList()
        val added = !favs.remove(key)
        if (added) favs.add(key)
        favourites = favs
        return added
    }

    /** The room/zone last picked in the menu, so it opens there again. */
    var lastGroup: String?
        get() = prefs.getString("group", null)
        set(value) = prefs.edit().putString("group", value).apply()

    private fun scene(json: JSONObject): HueScene {
        val colors = json.optJSONArray("actions")?.objects().orEmpty().mapNotNull { it.optJSONObject("action")?.let(::color) }.distinct()
        return HueScene(
            id = json.getString("id"),
            name = json.getJSONObject("metadata").getString("name"),
            colors = colors,
            active = json.optJSONObject("status")?.optString("active", "inactive").let { it != null && it != "inactive" },
        )
    }

    private fun color(action: JSONObject): Int? {
        action.optJSONObject("color")?.optJSONObject("xy")?.let { return xyColor(it.getDouble("x"), it.getDouble("y")) }
        action.optJSONObject("color_temperature")?.optInt("mirek", 0)?.takeIf { it > 0 }?.let { return mirekColor(it) }
        return null
    }

    // --- HTTP -------------------------------------------------------------------------------

    private fun get(type: String): List<JSONObject> =
        JSONObject(request("GET", "resource/$type", null)).getJSONArray("data").objects()

    private fun put(path: String, body: JSONObject) {
        request("PUT", "resource/$path", body.toString())
    }

    /** Retries once with a freshly discovered address if the cached one fails. */
    private fun request(method: String, path: String, body: String?): String {
        val key = key ?: throw IOException("No Hue key; run ./tv.sh hue-key")
        val cached = prefs.getString("host", null)
        if (cached != null) {
            try {
                return request(cached, key, method, path, body)
            } catch (e: IOException) {
                Log.w(TAG, "Bridge at $cached failed, rediscovering: $e")
            }
        }
        val host = discover() ?: throw IOException("No Hue bridge found")
        prefs.edit().putString("host", host).apply()
        return request(host, key, method, path, body)
    }

    private fun request(host: String, key: String, method: String, path: String, body: String?): String {
        val conn = URL("https://$host/clip/v2/$path").openConnection() as HttpsURLConnection
        conn.sslSocketFactory = bridgeSsl
        conn.hostnameVerifier = HostnameVerifier { name, _ -> name == host }
        conn.connectTimeout = 3000
        conn.readTimeout = 5000
        conn.requestMethod = method
        conn.setRequestProperty("hue-application-key", key)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            if (conn.responseCode !in 200..299) throw IOException("Hue $method $path: HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Finds the bridge on the local network (mDNS `_hue._tcp`). */
    private fun discover(timeoutSeconds: Long = 6): String? {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        val done = CountDownLatch(1)
        var host: String? = null
        val resolver = object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                host = info.host?.hostAddress
                done.countDown()
            }
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                Log.w(TAG, "Resolve failed: $error")
                done.countDown()
            }
        }
        var resolving = false
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                // One resolve at a time is all NsdManager allows; a home has one bridge.
                if (!resolving) {
                    resolving = true
                    nsd.resolveService(info, resolver)
                }
            }
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, error: Int) = done.countDown()
            override fun onStopDiscoveryFailed(serviceType: String, error: Int) = Unit
        }
        nsd.discoverServices("_hue._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        done.await(timeoutSeconds, TimeUnit.SECONDS)
        try {
            nsd.stopServiceDiscovery(listener)
        } catch (e: IllegalArgumentException) {
            // Discovery never started.
        }
        Log.i(TAG, "Discovered bridge: $host")
        return host
    }

    companion object {
        private const val TAG = "Hue"

        /**
         * The bridge serves HTTPS with a certificate from Signify's own CA, issued to its bridge
         * id rather than its IP. These connections only ever go to the bridge on the local
         * network, so its certificate is accepted as is.
         */
        private val bridgeSsl: SSLSocketFactory by lazy {
            val trustBridge = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustBridge), SecureRandom()) }.socketFactory
        }

        /** CIE xy to an sRGB colour at full brightness (Philips' wide-gamut matrix). */
        fun xyColor(x: Double, y: Double): Int {
            val bigX = x / y
            val bigZ = (1 - x - y) / y
            val rgb = doubleArrayOf(
                bigX * 1.656492 - 0.354851 - bigZ * 0.255038,
                -bigX * 0.707196 + 1.655397 + bigZ * 0.036152,
                bigX * 0.051713 - 0.121364 + bigZ * 1.011530,
            ).map { max(0.0, it) }
            val m = rgb.max().takeIf { it > 0 } ?: 1.0
            val c = rgb.map { v -> (255 * min(1.0, gamma(v / m))).roundToInt() }
            return Color.rgb(c[0], c[1], c[2])
        }

        private fun gamma(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055

        /** Colour temperature (mirek) to RGB, Tanner Helland's approximation. */
        fun mirekColor(mirek: Int): Int {
            val t = 1e6 / mirek / 100
            val r = if (t <= 66) 255.0 else 329.7 * (t - 60).pow(-0.1332)
            val g = if (t <= 66) 99.47 * ln(t) - 161.12 else 288.1 * (t - 60).pow(-0.0755)
            val b = if (t >= 66) 255.0 else if (t <= 19) 0.0 else 138.5 * ln(t - 10) - 305.04
            fun c(v: Double) = v.roundToInt().coerceIn(0, 255)
            return Color.rgb(c(r), c(g), c(b))
        }
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
