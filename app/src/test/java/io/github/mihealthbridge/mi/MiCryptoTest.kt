package io.github.mihealthbridge.mi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 测试向量来自 HealthMi（已与 Python 参考实现交叉验证）。 */
class MiCryptoTest {
    private val ssecurity = MiCrypto.unb64("MDEyMzQ1Njc4OWFiY2RlZg==")
    private val nonce = ByteArray(12) { it.toByte() }
    private val signed = MiCrypto.signedNonce(ssecurity, nonce)
    private val path = "/app/v1/data/get_fitness_data_by_time"

    @Test fun rc4Vector() {
        val key = MiCrypto.unb64("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
        assertEquals("3g08gF/TkZJ/x5IKBDEQAQ==", MiCrypto.b64(MiCrypto.rc4(key, "hello mi fitness".toByteArray())))
    }

    @Test fun signedNonceVector() {
        assertEquals("16/CeTzC9IqVVbiZ01Hy/Qd8rtVo5ybLo+ph/Vvh52k=", MiCrypto.b64(signed))
    }

    @Test fun requestFormVector() {
        val data = MiCloudClient.dataString(1782000000, 1782086399, "sleep")
        assertEquals("""{"start_time":1782000000,"end_time":1782086399,"key":"sleep"}""", data)
        assertEquals("dQeOexvAuinXDEkbLIQbMBouHGY=", MiCrypto.signature("POST", path, data, null, signed))

        val form = MiCrypto.buildForm(path, data, signed, nonce)
        assertEquals("5bCltiq5kl1uIEgC9s4jiIDNu+OVEGTncoC71qy+qtD4n0IkmB+onNFhbSyRCklfa0XbLc3XOY2aY08IMA==", form["data"])
        assertEquals("+sOzjS6zkENvIEs/kLF53fS22rHoYjuiFuWHhQ==", form["rc4_hash__"])
        assertEquals("5CKrj+1MdRE9d8ILAeQPUF7dl0o=", form["signature"])
        assertEquals("AAECAwQFBgcICQoL", form["_nonce"])
    }

    @Test fun responseDecryption() {
        val wire = MiCrypto.unb64("5bC1rS+uxDgqZQcVsYdn08zdsaiHRDWjP/2y0buV/IPOpx1j")
        assertEquals("""{"code":0,"result":{"data_list":[]}}""", String(MiCrypto.rc4(signed, wire)))
    }

    @Test fun dataStringWithPaging() {
        assertEquals(
            """{"start_time":1,"end_time":2,"key":"sport","limit":50,"next_key":"abc"}""",
            MiCloudClient.dataString(1, 2, "sport", 50, "abc"),
        )
    }

    @Test fun parseItemWithStringValue() {
        val item = MiCloudClient.parseItem(
            JSONObject("""{"time":1700000000,"zone_offset":28800,"sid":"s1","key":"heart_rate","value":"{\"bpm\":72}"}""")
        )
        assertEquals(1700000000L, item.time)
        assertEquals(28800, item.zoneOffsetSec)
        assertEquals(72L, item.value.optLongOrNull("bpm"))
    }
}
