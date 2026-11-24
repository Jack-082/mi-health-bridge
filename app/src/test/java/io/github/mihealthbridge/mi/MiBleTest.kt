package io.github.mihealthbridge.mi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MiBleTest {
    private fun h(s: String) = MiBeacon.unhex(s.replace(" ", "").lowercase())

    /** RFC 5869 Test Case 1。 */
    @Test fun hkdfRfc5869Case1() {
        val okm = Hkdf.derive(
            ikm = ByteArray(22) { 0x0b },
            salt = h("000102030405060708090a0b0c"),
            info = h("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertArrayEquals(h("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm)
    }

    /** NIST SP 800-38C 附录 C.1 的加密方向。 */
    @Test fun ccmEncryptNist() {
        val out = AesCcm.encrypt(
            h("404142434445464748494a4b4c4d4e4f"), h("10111213141516"), h("20212223"), h("0001020304050607"), 4,
        )
        assertArrayEquals(h("7162015b4dac255d"), out)
    }

    @Test fun encryptDecryptRoundTrip() {
        val k = MiBle.deriveKeys(ByteArray(12) { it.toByte() }, ByteArray(16) { 1 }, ByteArray(16) { 2 })
        // 让设备方向的密钥等于 app 方向，模拟对端解密
        val mirror = MiBle.Keys(devKey = k.appKey, appKey = k.appKey, devIv = k.appIv, appIv = k.appIv)
        val msg = MiBle.getProperties(7, listOf(2 to 1, 2 to 2))
        val wire = MiBle.encrypt(k, 3, msg)
        assertArrayEquals(msg, MiBle.decrypt(mirror, wire))
    }

    @Test fun getPropertiesEncoding() {
        // total = 4 + (op, count, 2×3) = 12 → 0x200c；tid=7
        assertArrayEquals(h("0c20 0700 02 02 020100 020200"), MiBle.getProperties(7, listOf(2 to 1, 2 to 2)))
    }

    @Test fun describePropertyReply() {
        // op 0x03，1 项：siid 2 piid 1 code 0，tl=type 5 len 4，值 655 → 0x028f
        val body = h("03 01 02 0100 0000 0450 8f020000")
        val total = 4 + body.size
        val msg = byteArrayOf(total.toByte(), (0x20 or (total ushr 8)).toByte(), 0x07, 0x00) + body
        assertEquals("属性回复 tid=7 2.1=655(t5)", MiBle.describe(msg))
    }

    @Test fun parcelFrames() {
        val frames = MiBle.parcelFrames(ByteArray(20) { it.toByte() })
        assertEquals(2, frames.size)
        assertArrayEquals(h("0100") + ByteArray(18) { it.toByte() }, frames[0])
        assertArrayEquals(h("0200 1213"), frames[1])
    }
}
