package io.github.mihealthbridge.mi

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 米家蓝牙广播（MiBeacon，Service UUID 0xFE95）解析。
 * 帧结构与加密方式参照 ha-xiaomi-s400 的 mibeacon.py（Apache-2.0）与 xiaomi-ble。
 *
 * v4/v5 加密帧：header(5) [mac(6)] [capability(1)[+1]] 密文 ext_counter(3) mic(4)，
 * AES-CCM（M=4，nonce = 反序 MAC + productId(2) + frameCounter(1) + ext_counter(3)，AAD = 0x11）。
 */
object MiBeacon {

    data class Frame(
        val frameControl: Int,
        val version: Int,
        val productId: Int,
        val frameCounter: Int,
        val encrypted: Boolean,
        val hasObject: Boolean,
        /** 帧里自带的 MAC（大写、冒号分隔），没有则为 null。 */
        val mac: String?,
        /** 对象区：加密帧为解密后的明文；解密失败为 null。 */
        val objects: List<Pair<Int, ByteArray>>?,
        val note: String?,
    )

    fun parse(data: ByteArray, macHint: String?, key: ByteArray?): Frame? {
        if (data.size < 5) return null
        val fc = u8(data, 0) or (u8(data, 1) shl 8)
        val version = fc ushr 12
        val encrypted = (fc ushr 3) and 1 == 1
        val hasMac = (fc ushr 4) and 1 == 1
        val hasCap = (fc ushr 5) and 1 == 1
        val hasObject = (fc ushr 6) and 1 == 1
        val productId = u8(data, 2) or (u8(data, 3) shl 8)
        val counter = u8(data, 4)

        var i = 5
        var frameMac: String? = null
        if (hasMac) {
            if (data.size < i + 6) return null
            frameMac = (0 until 6).joinToString(":") { "%02X".format(u8(data, i + 5 - it)) }
            i += 6
        }
        if (hasCap) {
            if (data.size < i + 1) return null
            val cap = u8(data, i)
            i += 1
            if (cap and 0x20 != 0) i += 1
        }
        fun frame(objs: List<Pair<Int, ByteArray>>?, note: String?) =
            Frame(fc, version, productId, counter, encrypted, hasObject, frameMac, objs, note)

        if (!hasObject) return frame(emptyList(), "无对象")
        if (!encrypted) return frame(splitObjects(data.copyOfRange(i, data.size)), null)
        if (version < 4) return frame(null, "旧版加密 v$version，暂不支持")
        if (data.size < i + 7 + 1) return frame(null, "加密帧过短")
        val mac = frameMac ?: macHint ?: return frame(null, "缺少 MAC，无法解密")
        if (key == null || key.size != 16) return frame(null, "没有 16 字节广播密钥")

        val macBytes = mac.split(":").map { it.toInt(16).toByte() }.reversed().toByteArray()
        val nonce = macBytes + data.copyOfRange(2, 5) + data.copyOfRange(data.size - 7, data.size - 4)
        val cipher = data.copyOfRange(i, data.size - 7)
        val tag = data.copyOfRange(data.size - 4, data.size)
        val plain = AesCcm.decrypt(key, nonce, cipher, tag, byteArrayOf(0x11))
            ?: return frame(null, "解密失败（密钥或 MAC 不匹配）")
        return frame(splitObjects(plain), null)
    }

    /** 对象区：type(2, 小端) + len(1) + value。 */
    fun splitObjects(payload: ByteArray): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        var p = 0
        while (payload.size - p >= 3) {
            val type = u8(payload, p) or (u8(payload, p + 1) shl 8)
            val len = u8(payload, p + 2)
            if (p + 3 + len > payload.size) {
                out += type to payload.copyOfRange(p + 3, payload.size)
                break
            }
            out += type to payload.copyOfRange(p + 3, p + 3 + len)
            p += 3 + len
        }
        return out
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
}

/** AES-CCM 解密（RFC 3610）。Android 自带 JCA 不支持 CCM，这里用 AES/ECB 自行实现。 */
object AesCcm {

    fun decrypt(key: ByteArray, nonce: ByteArray, cipherText: ByteArray, tag: ByteArray, aad: ByteArray): ByteArray? {
        val m = tag.size
        val l = 15 - nonce.size
        if (l !in 2..8 || m !in 4..16 || m % 2 != 0) return null
        val aes = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }

        fun ctrBlock(i: Int): ByteArray {
            val a = ByteArray(16)
            a[0] = (l - 1).toByte()
            nonce.copyInto(a, 1)
            var v = i
            for (k in 15 downTo 16 - l) { a[k] = (v and 0xFF).toByte(); v = v ushr 8 }
            return aes.doFinal(a)
        }

        // CTR 解密
        val plain = ByteArray(cipherText.size)
        var blk = 1
        var off = 0
        while (off < cipherText.size) {
            val s = ctrBlock(blk++)
            for (k in 0 until minOf(16, cipherText.size - off)) plain[off + k] = (cipherText[off + k].toInt() xor s[k].toInt()).toByte()
            off += 16
        }

        // CBC-MAC
        val b0 = ByteArray(16)
        b0[0] = ((if (aad.isNotEmpty()) 0x40 else 0) or (((m - 2) / 2) shl 3) or (l - 1)).toByte()
        nonce.copyInto(b0, 1)
        var len = plain.size
        for (k in 15 downTo 16 - l) { b0[k] = (len and 0xFF).toByte(); len = len ushr 8 }
        var x = aes.doFinal(b0)
        fun absorb(data: ByteArray) {
            var p = 0
            while (p < data.size) {
                val block = ByteArray(16)
                data.copyInto(block, 0, p, minOf(p + 16, data.size))
                for (k in 0 until 16) block[k] = (block[k].toInt() xor x[k].toInt()).toByte()
                x = aes.doFinal(block)
                p += 16
            }
        }
        if (aad.isNotEmpty()) {
            require(aad.size < 0xFF00)
            absorb(byteArrayOf((aad.size ushr 8).toByte(), aad.size.toByte()) + aad)
        }
        absorb(plain)

        val s0 = ctrBlock(0)
        val expected = ByteArray(m) { (x[it].toInt() xor s0[it].toInt()).toByte() }
        var diff = 0
        for (k in 0 until m) diff = diff or (expected[k].toInt() xor tag[k].toInt())
        return if (diff == 0) plain else null
    }
}
