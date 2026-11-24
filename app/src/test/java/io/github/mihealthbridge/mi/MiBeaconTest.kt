package io.github.mihealthbridge.mi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiBeaconTest {
    private fun h(s: String) = MiBeacon.unhex(s.replace(" ", "").lowercase())

    /** NIST SP 800-38C 附录 C.1：M=4（与 MiBeacon 相同的标签长度）。 */
    @Test fun ccmNistExample1() {
        val plain = AesCcm.decrypt(
            key = h("404142434445464748494a4b4c4d4e4f"),
            nonce = h("10111213141516"),
            cipherText = h("7162015b"),
            tag = h("4dac255d"),
            aad = h("0001020304050607"),
        )
        assertArrayEquals(h("20212223"), plain)
    }

    /** RFC 3610 Packet Vector #1：M=8, L=2。 */
    @Test fun ccmRfc3610Vector1() {
        val plain = AesCcm.decrypt(
            key = h("C0C1C2C3C4C5C6C7C8C9CACBCCCDCECF"),
            nonce = h("00000003020100A0A1A2A3A4A5"),
            cipherText = h("588C979A61C663D2F066D0C2C0F989806D5F6B61DAC384"),
            tag = h("17E8D12CFDF926E0"),
            aad = h("0001020304050607"),
        )
        assertArrayEquals(h("08090A0B0C0D0E0F101112131415161718191A1B1C1D1E"), plain)
    }

    @Test fun ccmRejectsBadTag() {
        assertNull(
            AesCcm.decrypt(
                h("404142434445464748494a4b4c4d4e4f"), h("10111213141516"),
                h("7162015b"), h("4dac255e"), h("0001020304050607"),
            ),
        )
    }

    @Test fun parsesUnencryptedFrameWithMac() {
        // fc=0x5050：v5，含 MAC、含对象、不加密；pid=0x1234；cnt=7；MAC 小端；对象 0x1006 len 2
        val frame = h("5050 3412 07 563412c1a4a4 061002 e803")
        val f = MiBeacon.parse(frame, null, null)!!
        assertEquals(5, f.version)
        assertEquals(0x1234, f.productId)
        assertEquals("A4:A4:C1:12:34:56", f.mac)
        assertEquals(false, f.encrypted)
        val (type, value) = f.objects!!.single()
        assertEquals(0x1006, type)
        assertArrayEquals(h("e803"), value)
    }
}
