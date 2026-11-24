package io.github.mihealthbridge.mi

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * 米家蓝牙（Mi Home v2 BLE）登录、加密与 MIoT 消息编解码。
 * 移植自 ha-xiaomi-s400（Apache-2.0），其又基于 nokistin/xiaomi-s400-live 与 dnandha/miauth（Apache-2.0）。
 */
object MiBle {
    private fun u(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

    val SERVICE: UUID = u("fe95")
    val UPNP: UUID = u("0010")   // 命令
    val AVCTP: UUID = u("0017")
    val AVDTP: UUID = u("0019")  // 认证数据
    val VEND1A: UUID = u("001a") // app → 设备（加密）
    val CMTP: UUID = u("001b")   // 设备 → app（加密）
    val VEND1C: UUID = u("001c")
    val NOTIFY = listOf(UPNP, AVCTP, AVDTP, VEND1A, CMTP, VEND1C)

    val CMD_LOGIN = MiBeacon.unhex("24000000")
    val CMD_SEND_KEY = MiBeacon.unhex("0000000b0100")
    val CMD_SEND_INFO = MiBeacon.unhex("0000000a0200")
    val RCV_RDY = MiBeacon.unhex("00000101")
    val RCV_OK = MiBeacon.unhex("00000100")
    val CFM_LOGIN_OK = MiBeacon.unhex("21000000")

    data class Keys(val devKey: ByteArray, val appKey: ByteArray, val devIv: ByteArray, val appIv: ByteArray)

    fun deriveKeys(token: ByteArray, appRand: ByteArray, devRand: ByteArray): Keys {
        val d = Hkdf.derive(token, appRand + devRand, "mible-login-info".toByteArray(), 64)
        return Keys(d.copyOfRange(0, 16), d.copyOfRange(16, 32), d.copyOfRange(32, 36), d.copyOfRange(36, 40))
    }

    /** 设备发来的加密消息：iter(2, LE) + 密文 + 标签(4)。 */
    fun decrypt(keys: Keys, raw: ByteArray): ByteArray? {
        if (raw.size < 6) return null
        val nonce = keys.devIv + ByteArray(4) + raw.copyOfRange(0, 2) + ByteArray(2)
        val body = raw.copyOfRange(2, raw.size)
        return AesCcm.decrypt(keys.devKey, nonce, body.copyOfRange(0, body.size - 4), body.copyOfRange(body.size - 4, body.size), ByteArray(0))
    }

    fun encrypt(keys: Keys, counter: Int, plain: ByteArray): ByteArray {
        val it = byteArrayOf(counter.toByte(), (counter ushr 8).toByte())
        val nonce = keys.appIv + ByteArray(4) + it + ByteArray(2)
        return it + AesCcm.encrypt(keys.appKey, nonce, plain, ByteArray(0))
    }

    /** 把数据切成带 2 字节序号的帧（每帧 18 字节数据）。 */
    fun parcelFrames(data: ByteArray, chunk: Int = 18): List<ByteArray> =
        data.toList().chunked(chunk).mapIndexed { i, c ->
            val n = i + 1
            byteArrayOf(n.toByte(), (n ushr 8).toByte()) + c.toByteArray()
        }

    // ---------------- MIoT over BLE ----------------

    /** op 0x02 get_properties：count, (siid u8, piid u16)*。 */
    fun getProperties(tid: Int, props: List<Pair<Int, Int>>): ByteArray {
        val body = ByteBuffer.allocate(2 + props.size * 3).order(ByteOrder.LITTLE_ENDIAN)
        body.put(0x02).put(props.size.toByte())
        props.forEach { (s, p) -> body.put(s.toByte()).putShort(p.toShort()) }
        return header(tid, body.array())
    }

    private fun header(tid: Int, body: ByteArray): ByteArray {
        val total = 4 + body.size
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(((0x2 shl 12) or total).toShort()).putShort(tid.toShort()).put(body).array()
    }

    /** 尽量把 MIoT 消息解成可读文本；不是 MIoT 格式时返回 null。 */
    fun describe(data: ByteArray): String? {
        if (data.size < 5 || (data[1].toInt() and 0xFF) ushr 4 != 0x2) return null
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        b.short
        val tid = b.short.toInt() and 0xFFFF
        val op = b.get().toInt() and 0xFF
        return runCatching {
            when (op) {
                0x03 -> { // get_properties 回复
                    val n = b.get().toInt() and 0xFF
                    val items = (0 until n).map {
                        val siid = b.get().toInt() and 0xFF
                        val piid = b.short.toInt() and 0xFFFF
                        val code = b.short.toInt()
                        if (code != 0) "$siid.$piid=错误$code" else "$siid.$piid=${value(b)}"
                    }
                    "属性回复 tid=$tid " + items.joinToString(" ")
                }
                0x07, 0x04 -> { // 事件 / 属性变化
                    val siid = b.get().toInt() and 0xFF
                    val iid = b.short.toInt() and 0xFFFF
                    if (op == 0x04) "属性变化 $siid.$iid=${value(b)}"
                    else {
                        val argc = b.get().toInt() and 0xFF
                        val args = (0 until argc).map { "p${b.short.toInt() and 0xFFFF}=${value(b)}" }
                        "事件 $siid.$iid " + args.joinToString(" ")
                    }
                }
                0x06 -> "动作回复 tid=$tid code=${b.short}"
                else -> "op=0x%02x tid=%d".format(op, tid)
            }
        }.getOrElse { "op=0x%02x（解析不完整）".format(op) }
    }

    /** tl = type(4bit) + len(12bit)；type 0xA 为字符串，其余按小端整数显示。 */
    private fun value(b: ByteBuffer): String {
        val tl = b.short.toInt() and 0xFFFF
        val len = tl and 0x0FFF
        val type = tl ushr 12
        val raw = ByteArray(len).also { b.get(it) }
        return when {
            type == 0xA -> "\"" + String(raw, Charsets.UTF_8).trimEnd('\u0000') + "\""
            len in 1..8 -> {
                var v = 0L
                for (i in raw.indices.reversed()) v = (v shl 8) or (raw[i].toLong() and 0xFF)
                "$v(t$type)"
            }
            else -> "hex:" + MiBeacon.hex(raw)
        }
    }
}
