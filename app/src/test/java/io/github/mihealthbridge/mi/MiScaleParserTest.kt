package io.github.mihealthbridge.mi

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 样例字段取自 SmartScaleConnect 记录的真实响应结构（数值为示例）。 */
class MiScaleParserTest {

    private fun item(fromSource: Int, createTime: Long, data: JSONObject) = JSONObject()
        .put("model", "yunmai.scales.ms103").put("fromSource", fromSource)
        .put("createTime", createTime).put("data", data.toString())

    @Test fun fromSource1() {
        val data = JSONObject()
            .put("weight", 87.8).put("bfp", 22.9).put("bwp", 58.8).put("bmc", 3.7)
            .put("bmr", 1832).put("ffm", 67.6).put("bwm", 51.5)
            .put("user", JSONObject().put("name", "爸爸"))
        val (list, count, oldest) = MiScaleParser.parsePage(JSONArray().put(item(1, 1_755_927_448_000, data)))
        assertEquals(1, count)
        assertEquals(1_755_927_448_000, oldest)
        val w = list.single()
        assertEquals(1_755_927_448L, w.timeSec)
        assertEquals(87.8, w.weightKg, 1e-9)
        assertEquals(22.9, w.bodyFatPct!!, 1e-9)
        assertEquals(51.5, w.bodyWaterKg!!, 1e-9)
        assertEquals(3.7, w.boneMassKg!!, 1e-9)
        assertEquals(67.6, w.leanMassKg!!, 1e-9)
        assertEquals(1832.0, w.bmrKcal!!, 1e-9)
        assertEquals("爸爸", w.member)
    }

    @Test fun fromSource3WithStringFields() {
        val body = JSONObject().put("bfp", "12.1").put("bmc", "1.6").put("bmr", "1143").put("ffm", "35.8")
        val data = JSONObject()
            .put("weight", "40.7").put("time", "1700000000000").put("bodyResData", body.toString())
            .put("user", JSONObject().put("name", "女儿"))
        val w = MiScaleParser.parsePage(JSONArray().put(item(3, 1_700_000_001_000, data))).first.single()
        assertEquals(1_700_000_000L, w.timeSec)
        assertEquals(40.7, w.weightKg, 1e-9)
        assertEquals(12.1, w.bodyFatPct!!, 1e-9)
        assertEquals(35.8, w.leanMassKg!!, 1e-9)
        assertNull(w.bodyWaterKg)
        assertEquals("女儿", w.member)
    }

    @Test fun skipsZeroWeight() {
        val data = JSONObject().put("weight", 0)
        val (list, count, _) = MiScaleParser.parsePage(JSONArray().put(item(1, 1, data)))
        assertEquals(1, count)
        assertEquals(0, list.size)
    }

    @Test fun fitnessWeightItem() {
        val raw = JSONObject().put("time", 1732550224).put("key", "weight").put("value",
            JSONObject().put("weight", 69.8).put("body_fat_rate", 19.6).put("bone_mass", 2.8)
                .put("basal_metabolism", 1582).put("body_moisture_mass", 35.5).put("fat_free_body", 56.1)
                .put("time", 1732550224).toString())
        val w = MiScaleParser.fromFitnessItem(MiCloudClient.parseItem(raw))!!
        assertEquals(69.8, w.weightKg, 1e-9)
        assertEquals(19.6, w.bodyFatPct!!, 1e-9)
        assertEquals(35.5, w.bodyWaterKg!!, 1e-9)
        assertEquals(56.1, w.leanMassKg!!, 1e-9)
        assertNull(w.member)
    }
}
