package com.omoai.simpleuvcstreamer.cymple

/**
 * Wire protocol of Cymple's "DIY camera" (Project_Cymple / esp源代码).
 *
 * Discovery / heartbeat: Cymple (PC) -> UDP 23302, TLV {u16 type, u16 len}
 *   type 0 = broadcast heartbeat, 4 = unicast heartbeat.
 * Images: camera -> Cymple IP:23303, TLV type 5 (MSG_IMAGE_V2_E), 12-byte header
 *   + JPEG chunk, chunk <= 1444 bytes (uiLength = 8 + chunk, excludes the TLV tag).
 * Device flag: 0 = left eye, 1 = right eye, 2 = mouth.
 */
object CympleProtocol {

    const val CAM_PORT = 23302
    const val SERVER_PORT = 23303

    /** Second local source port so Cymple can see two cameras as two endpoints. */
    const val SECOND_LOCAL_PORT = 23304

    const val MSG_SERVER_HEARTBEAT = 0
    const val MSG_SERVER_UNICAST_HEARTBEAT = 4
    const val MSG_IMAGE_V2 = 5

    const val FLAG_LEFT_EYE = 0
    const val FLAG_RIGHT_EYE = 1
    const val FLAG_MOUTH = 2

    /** Our own "do not send this camera" value (never put on the wire). */
    const val FLAG_OFF = -1

    /** JPEG bytes per datagram (ESP: CONFIG_TCP_MSS 1460 - 16). */
    const val MAX_CHUNK = 1444

    /** uiTotalLen is u16; keep margin below 65535. */
    const val MAX_JPEG_BYTES = 60000

    const val HEARTBEAT_TIMEOUT_MS = 5000L

    val positionLabels = arrayOf("左眼", "右眼", "嘴部", "不发送")
    val positionValues = intArrayOf(FLAG_LEFT_EYE, FLAG_RIGHT_EYE, FLAG_MOUTH, FLAG_OFF)

    fun labelFor(flag: Int): String = when (flag) {
        FLAG_LEFT_EYE -> positionLabels[0]
        FLAG_RIGHT_EYE -> positionLabels[1]
        FLAG_MOUTH -> positionLabels[2]
        else -> positionLabels[3]
    }

    fun valueOfLabel(label: String?): Int {
        val idx = positionLabels.indexOf(label)
        return if (idx >= 0) positionValues[idx] else FLAG_OFF
    }

    /**
     * Build one MSG_IMAGE_V2_E datagram (little-endian):
     * TLV{type=5, len=8+chunk} | offset u16 | total u16 | dataLen u16 |
     * flag u8 | frameIndex u8 | chunk bytes.
     */
    fun buildImagePacket(
        frameIndex: Int,
        flag: Int,
        offset: Int,
        total: Int,
        chunkLen: Int,
        data: ByteArray,
        dataOffset: Int,
    ): ByteArray {
        val buf = ByteArray(12 + chunkLen)
        buf[0] = MSG_IMAGE_V2.toByte()
        buf[1] = (MSG_IMAGE_V2 shr 8).toByte()
        val tlvLen = 8 + chunkLen
        buf[2] = tlvLen.toByte()
        buf[3] = (tlvLen shr 8).toByte()
        buf[4] = offset.toByte()
        buf[5] = (offset shr 8).toByte()
        buf[6] = total.toByte()
        buf[7] = (total shr 8).toByte()
        buf[8] = chunkLen.toByte()
        buf[9] = (chunkLen shr 8).toByte()
        buf[10] = flag.toByte()
        buf[11] = frameIndex.toByte()
        System.arraycopy(data, dataOffset, buf, 12, chunkLen)
        return buf
    }
}
