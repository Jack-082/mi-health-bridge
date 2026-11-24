package io.github.mihealthbridge.mi

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次称重（已统一字段与单位）。来源两种：
 * - 米家 App 绑定的秤：小米运动健康云的 eco/scale/getData 代理接口（[MiCloudClient.fetchScaleData]）
 * - 小米运动健康绑定的秤：get_fitness_data_by_time 的 key=weight
 */
data class ScaleWeighIn(
    val timeSec: Long,
    val weightKg: Double,
    val bodyFatPct: Double? = null,
    val bodyWaterPct: Double? = null,
    val bodyWaterKg: Double? = null,
    val boneMassKg: Double? = null,
    val leanMassKg: Double? = null,
    val bmrKcal: Double? = null,
    /** 秤上的家庭成员名称；小米运动健康来源为 null（只有本人数据）。 */
    val member: String? = null,
)

/**
 * 解析 eco/scale/getData 返回的条目（字段对照 SmartScaleConnect，MIT）。
 * fromSource 1/2：data 里直接是体成分缩写字段；fromSource 3：体成分嵌在 bodyResData 字符串里。
 */
object MiScaleParser {

    /** 型号候选：先试国行 S400，再试其他已知米家体脂秤型号。 */
    val CANDIDATE_MODELS = listOf(
        "yunmai.scales.ms103", // 小米体脂秤 S400（国行）
        "yunmai.scales.ms107",
        "xiaomi.scales.ms110",
        "yunmai.scales.ms104", // S400 欧版（一般在国外区）
    )

    /** 解析一页；返回 (称重列表, 本页原始条数, 本页最早一条的 createTime 毫秒)。 */
    fun parsePage(items: JSONArray): Triple<List<ScaleWeighIn>, Int, Long?> {
        val out = ArrayList<ScaleWeighIn>()
        var oldest: Long? = null
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val createMs = item.optLongOrNull("createTime")
            if (createMs != null) oldest = minOf(oldest ?: createMs, createMs)
            val data = item.optString("data").let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
            parseItem(item.optInt("fromSource"), createMs, data)?.let { out += it }
        }
        return Triple(out, items.length(), oldest)
    }

    private fun parseItem(fromSource: Int, createMs: Long?, d: JSONObject): ScaleWeighIn? {
        val member = d.optJSONObject("user")?.optString("name")?.ifEmpty { null }
        return when (fromSource) {
            1, 2 -> {
                val t = (createMs ?: return null) / 1000
                build(t, d.optDoubleOrNull("weight"), d, member)
            }
            3 -> {
                // time 字段是毫秒字符串；体成分在 bodyResData 中
                val t = (d.optLongOrNull("time") ?: createMs ?: return null) / 1000
                val body = d.optString("bodyResData").let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
                build(t, d.optDoubleOrNull("weight"), body, member)
            }
            else -> null
        }
    }

    private fun build(t: Long, weight: Double?, b: JSONObject, member: String?): ScaleWeighIn? {
        val w = weight?.takeIf { it > 0 && it < 500 } ?: return null
        return ScaleWeighIn(
            timeSec = t,
            weightKg = w,
            bodyFatPct = b.positive("bfp"),
            bodyWaterPct = b.positive("bwp"),
            bodyWaterKg = b.positive("bwm"),
            boneMassKg = b.positive("bmc"),
            leanMassKg = b.positive("ffm"),
            bmrKcal = b.positive("bmr"),
            member = member,
        )
    }

    /** 小米运动健康 key=weight 的 value（字段名为全称）。 */
    fun fromFitnessItem(item: MiItem): ScaleWeighIn? {
        val v = item.value
        val t = v.optLongOrNull("time")?.takeIf { it > 0 } ?: item.time ?: return null
        val w = v.optDoubleOrNull("weight")?.takeIf { it > 0 && it < 500 } ?: return null
        return ScaleWeighIn(
            timeSec = t,
            weightKg = w,
            bodyFatPct = v.positive("body_fat_rate"),
            bodyWaterPct = v.positive("moisture_rate"),
            bodyWaterKg = v.positive("body_moisture_mass"),
            boneMassKg = v.positive("bone_mass"),
            leanMassKg = v.positive("fat_free_body"),
            bmrKcal = v.positive("basal_metabolism"),
        )
    }

    private fun JSONObject.positive(name: String): Double? = optDoubleOrNull(name)?.takeIf { it > 0 }
}
