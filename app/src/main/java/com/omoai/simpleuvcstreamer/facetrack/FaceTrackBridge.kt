package com.omoai.simpleuvcstreamer.facetrack

import android.content.Context
import android.os.SystemClock
import com.omoai.simpleuvcstreamer.util.FileLogger
import com.omoai.simpleuvcstreamer.uvc.UvcNative
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * 面捕眼追软件直连（"DIY camera" 摄像头模拟，依据其开源 ESP32 示例逆向）。
 *
 * - 面捕眼追软件（PC）向 UDP 23302 广播心跳；本端记录来源地址，并把图像推送到 <PC>:23303。
 * - 每路摄像头一个发送线程：取原生帧槽里最新的 JPEG，转 320×240 灰度并按
 *   MSG_IMAGE_V2_E 分片发送。
 * - 心跳停止 5 秒后自动停发，与 ESP32 固件行为一致，避免追踪端保留过期设备。
 */
class FaceTrackBridge(
    private val context: Context,
    private val onStateChanged: () -> Unit = {},
) {
    data class State(
        val enabled: Boolean,
        val connected: Boolean,
        val serverIp: String?,
        val fps: Int,
        val flags: IntArray,
    )

    private val slotCount = UvcNative.CAMERA_COUNT
    private val flags = AtomicIntegerArray(slotCount)
    private val senders = arrayOfNulls<Sender>(slotCount)
    private val sentCounter = AtomicInteger()

    @Volatile
    private var enabled = false

    @Volatile
    private var serverIp: String? = null

    @Volatile
    private var serverInet: InetAddress? = null

    @Volatile
    private var lastHeartbeatAt = 0L

    @Volatile
    private var heartbeatSocket: DatagramSocket? = null

    private var heartbeatThread: Thread? = null

    private var fps = 0
    private var lastSampleAt = 0L
    private var lastSampleCount = 0L

    init {
        for (i in 0 until slotCount) {
            flags.set(i, FaceTrackPrefs.flagFor(context, i))
        }
    }

    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (value) {
            startHeartbeat()
            FileLogger.log("FaceTrack: enabled")
        } else {
            for (i in 0 until slotCount) {
                stopSlot(i)
            }
            FileLogger.log("FaceTrack: disabled")
        }
        fireStateChanged()
    }

    fun setFlag(index: Int, flag: Int) {
        if (index !in 0 until slotCount) return
        flags.set(index, flag)
        FaceTrackPrefs.setFlag(context, index, flag)
        FileLogger.log("FaceTrack: slot $index position=$flag")
        if (flag < 0) {
            stopSlot(index)
        }
    }

    fun flagFor(index: Int): Int {
        return if (index in 0 until slotCount) flags.get(index) else FaceTrackProtocol.FLAG_OFF
    }

    /** Called when a camera slot starts streaming. */
    fun startSlot(index: Int) {
        if (!enabled || index !in 0 until slotCount) return
        if (flags.get(index) < 0) return
        val sender = senders[index] ?: Sender(index).also { senders[index] = it }
        sender.start()
        FileLogger.log("FaceTrack: slot $index sender started")
    }

    /** Called when a camera slot stops streaming (or the bridge turns off). */
    fun stopSlot(index: Int) {
        if (index !in 0 until slotCount) return
        senders[index]?.stop()
        senders[index] = null
    }

    fun snapshot(): State {
        val now = SystemClock.elapsedRealtime()
        val total = sentCounter.get().toLong()
        if (now - lastSampleAt >= 500) {
            val dt = (now - lastSampleAt).coerceAtLeast(1)
            fps = (1000.0 * (total - lastSampleCount) / dt).toInt().coerceAtLeast(0)
            lastSampleAt = now
            lastSampleCount = total
        }
        val outFlags = IntArray(slotCount) { flags.get(it) }
        val connected = enabled && serverIp != null &&
            now - lastHeartbeatAt < FaceTrackProtocol.HEARTBEAT_TIMEOUT_MS
        return State(enabled, connected, serverIp, if (enabled) fps else 0, outFlags)
    }

    private fun fireStateChanged() {
        runCatching { onStateChanged() }
    }

    // ------------------------------------------------------------------ heartbeat

    private fun startHeartbeat() {
        if (heartbeatThread?.isAlive == true) return
        heartbeatThread = Thread({ heartbeatLoop() }, "FaceTrackHeartbeat").apply {
            isDaemon = true
            start()
        }
    }

    private fun heartbeatLoop() {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(FaceTrackProtocol.CAM_PORT)
            socket.soTimeout = 500
            heartbeatSocket = socket
            val buf = ByteArray(1024)
            FileLogger.log("FaceTrack: listening on UDP ${FaceTrackProtocol.CAM_PORT}")
            while (enabled) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    handleHeartbeat(packet)
                } catch (_: SocketTimeoutException) {
                    // No heartbeat yet.
                } catch (e: SocketException) {
                    if (!enabled) break
                    Thread.sleep(100)
                }
            }
        } catch (t: Throwable) {
            FileLogger.log("FaceTrack: heartbeat listener stopped: ${t.message}")
        } finally {
            heartbeatSocket = null
            runCatching { socket?.close() }
            heartbeatThread = null
            if (enabled) {
                // A quick off/on toggle raced the listener shutdown: bring it back.
                runCatching { Thread.sleep(200) }
                startHeartbeat()
            }
        }
    }

    private fun handleHeartbeat(packet: DatagramPacket) {
        if (packet.length < 4) return
        val data = packet.data
        val offset = packet.offset
        val type = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
        if (type != FaceTrackProtocol.MSG_SERVER_HEARTBEAT &&
            type != FaceTrackProtocol.MSG_SERVER_UNICAST_HEARTBEAT
        ) {
            return
        }
        val address = packet.address
        val ip = address?.hostAddress
        val changed = ip != serverIp
        serverIp = ip
        serverInet = address
        lastHeartbeatAt = SystemClock.elapsedRealtime()
        if (changed) {
            FileLogger.log("FaceTrack: heartbeat from $ip")
            fireStateChanged()
        }
    }

    // --------------------------------------------------------------------- sender

    private inner class Sender(private val index: Int) {
        @Volatile
        private var running = false

        private var thread: Thread? = null
        private var ownSocket: DatagramSocket? = null
        private var frameIndex = 0

        fun start() {
            if (running) return
            running = true
            thread = Thread({ loop() }, "FaceTrackSender-$index").apply {
                isDaemon = true
                start()
            }
        }

        fun stop() {
            running = false
            thread?.interrupt()
            thread = null
            runCatching { ownSocket?.close() }
            ownSocket = null
        }

        private fun socketForSend(): DatagramSocket? {
            // Slot 0 sends from the heartbeat socket (source port 23302), exactly
            // like the ESP32 firmware; slot 1 uses its own port so the tracker sees a
            // second endpoint if it keys devices by address:port.
            if (index == 0) {
                heartbeatSocket?.let { if (!it.isClosed) return it }
            }
            ownSocket?.let { if (!it.isClosed) return it }
            val port = if (index == 0) 0 else FaceTrackProtocol.SECOND_LOCAL_PORT
            val socket = runCatching { DatagramSocket(port) }.getOrNull() ?: return null
            ownSocket = socket
            return socket
        }

        private fun loop() {
            var lastSendAt = 0L
            while (running) {
                try {
                    val jpeg = UvcNative.nativeTakeFaceTrackFrame(index)
                    if (jpeg == null || jpeg.isEmpty()) {
                        Thread.sleep(4)
                        continue
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSendAt < MIN_SEND_INTERVAL_MS) {
                        continue
                    }
                    val target = serverInet
                    if (target == null || now - lastHeartbeatAt >= FaceTrackProtocol.HEARTBEAT_TIMEOUT_MS) {
                        Thread.sleep(40)
                        continue
                    }
                    val flag = flags.get(index)
                    if (flag < 0) {
                        Thread.sleep(50)
                        continue
                    }
                    val out = FaceTrackImageEncoder.toFaceTrackJpeg(jpeg)
                    if (out == null || out.isEmpty() || out.size > FaceTrackProtocol.MAX_JPEG_BYTES) {
                        continue
                    }
                    val socket = socketForSend() ?: continue
                    lastSendAt = now
                    frameIndex = (frameIndex + 1) and 0xFF
                    sendFrame(socket, target, flag, out)
                    sentCounter.incrementAndGet()
                } catch (_: InterruptedException) {
                    return
                } catch (_: SocketException) {
                    if (!running) return
                    runCatching { ownSocket?.close() }
                    ownSocket = null
                    Thread.sleep(150)
                } catch (t: Throwable) {
                    FileLogger.log("FaceTrack: send[$index] failed: ${t.message}")
                    Thread.sleep(100)
                }
            }
        }

        private fun sendFrame(
            socket: DatagramSocket,
            target: InetAddress,
            flag: Int,
            jpeg: ByteArray,
        ) {
            var offset = 0
            while (offset < jpeg.size && running) {
                val len = minOf(FaceTrackProtocol.MAX_CHUNK, jpeg.size - offset)
                val packet = FaceTrackProtocol.buildImagePacket(
                    frameIndex = frameIndex,
                    flag = flag,
                    offset = offset,
                    total = jpeg.size,
                    chunkLen = len,
                    data = jpeg,
                    dataOffset = offset,
                )
                socket.send(
                    DatagramPacket(packet, packet.size, target, FaceTrackProtocol.SERVER_PORT)
                )
                offset += len
            }
        }
    }

    private companion object {
        /** ~22 fps cap (the DIY camera runs ~24 fps). */
        const val MIN_SEND_INTERVAL_MS = 45L
    }
}
