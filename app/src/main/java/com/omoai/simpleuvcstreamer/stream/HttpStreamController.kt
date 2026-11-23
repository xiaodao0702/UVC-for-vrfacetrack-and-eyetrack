package com.omoai.simpleuvcstreamer.stream

import android.content.Context
import android.content.SharedPreferences
import com.omoai.simpleuvcstreamer.util.FileLogger
import com.omoai.simpleuvcstreamer.uvc.UvcNative

/**
 * Owns the HTTP MJPEG server lifecycle for ONE camera slot (native libhv).
 *
 * Each camera gets its own server instance and its own persisted port, so both
 * cameras can be opened from different URLs at the same time.
 * Default: auto-start on [ensureStarted] with the persisted port.
 */
class HttpStreamController(context: Context, private val index: Int) {

    data class AccessEndpoint(
        val label: String,
        val url: String,
        val host: String,
        val tier: NetworkAddresses.AccessTier,
    )

    companion object {
        /** Port used by camera 1 when nothing else was configured. */
        const val DEFAULT_PORT = 8080
        const val STREAM_PATH = "/stream.mjpg"

        /** Per-camera default port: 8080 for camera 1, 8081 for camera 2, ... */
        fun defaultPort(index: Int): Int = (DEFAULT_PORT + index).coerceIn(1, 65535)

        private const val PREFS = "http_stream"

        private fun keyPort(index: Int) = "port_$index"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt(keyPort(index), defaultPort(index)).coerceIn(1, 65535)
        private set(value) {
            prefs.edit().putInt(keyPort(index), value.coerceIn(1, 65535)).apply()
        }

    val isRunning: Boolean
        get() = UvcNative.isLibLoaded && UvcNative.nativeIsHttpServerRunning(index)

    val clientCount: Int
        get() = if (UvcNative.isLibLoaded) UvcNative.nativeGetHttpClientCount(index) else 0

    fun ensureStarted(portOverride: Int? = null): Boolean {
        if (!UvcNative.isLibLoaded) {
            FileLogger.log("[$index] HTTP: native lib not loaded")
            return false
        }
        val p = (portOverride ?: port).coerceIn(1, 65535)
        port = p
        val res = UvcNative.nativeStartHttpServer(index, p)
        FileLogger.log("[$index] HTTP start port=$p res=$res")
        return res == 0 && UvcNative.nativeIsHttpServerRunning(index)
    }

    fun applyPort(newPort: Int): Boolean {
        val p = newPort.coerceIn(1, 65535)
        if (isRunning && UvcNative.nativeGetHttpServerPort(index) == p) {
            port = p
            return true
        }
        stop()
        return ensureStarted(p)
    }

    fun stop() {
        if (!UvcNative.isLibLoaded) return
        UvcNative.nativeStopHttpServer(index)
        FileLogger.log("[$index] HTTP stopped")
    }

    /** Stream URLs only, sorted by access tier. */
    fun accessEndpoints(): List<AccessEndpoint> {
        val p = if (isRunning) UvcNative.nativeGetHttpServerPort(index) else port
        if (p !in 1..65535) return emptyList()
        return NetworkAddresses.ipv4Endpoints().map { ep ->
            AccessEndpoint(
                label = when (ep.tier) {
                    NetworkAddresses.AccessTier.LOCAL -> "本机回路"
                    NetworkAddresses.AccessTier.LAN -> ep.interfaceName
                    NetworkAddresses.AccessTier.OTHER -> ep.interfaceName
                },
                url = "http://${ep.host}:$p$STREAM_PATH",
                host = ep.host,
                tier = ep.tier,
            )
        }
    }
}
