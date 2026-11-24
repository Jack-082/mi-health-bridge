package io.github.mihealthbridge.sync

import android.content.Context
import io.github.mihealthbridge.mi.MiCloudClient
import io.github.mihealthbridge.mi.MiSession
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 米家账号下的一台设备。 */
data class MiHomeDevice(val did: String, val name: String, val model: String, val specType: String?)

/**
 * 米家云（api.io.mi.com，sid=xiaomiio）：列设备、查设备历史数据。
 * 与小米运动健康共用同一个小米账号的 passToken，不需要另外登录。
 */
class MiHomeProbe(context: Context) {

    private val settings = Settings(context)
    private val home = MiCloudClient(
        baseUrl = "https://api.io.mi.com/app",
        sid = "xiaomiio",
        extraHeaders = mapOf(
            "MIOT-ENCRYPT-ALGORITHM" to "ENCRYPT-RC4",
            "x-xiaomi-protocal-flag-cli" to "PROTOCAL-HTTP2",
        ),
    )
    private val health = MiCloudClient()
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    fun login(): MiSession {
        val s = home.login(settings.userId!!, settings.passToken!!)
        settings.passToken = s.passToken
        return s
    }

    fun listDevices(s: MiSession): List<MiHomeDevice> {
        val data = """{"getVirtualModel":true,"getHuamiDevices":1,"get_split_device":false,"support_smart_home":true}"""
        val list = home.call(s, "/home/device_list", data).optJSONArray("list") ?: JSONArray()
        return (0 until list.length()).mapNotNull { i ->
            val d = list.optJSONObject(i) ?: return@mapNotNull null
            MiHomeDevice(
                did = d.optString("did"),
                name = d.optString("name"),
                model = d.optString("model"),
                specType = d.optString("spec_type").ifEmpty { null },
            )
        }
    }

    /** 名称或型号像体脂秤的设备。 */
    fun scales(devices: List<MiHomeDevice>) = devices.filter {
        "scale" in it.model.lowercase() || "秤" in it.name || "scale" in (it.specType ?: "")
    }

    /** user/get_user_device_data：type = prop / event，key 如 prop.2.1、event.2.1。 */
    fun deviceData(s: MiSession, did: String, key: String, type: String, sinceSec: Long, limit: Int): JSONArray {
        val now = System.currentTimeMillis() / 1000
        val data = JSONObject()
            .put("did", did).put("key", key).put("type", type)
            .put("time_start", sinceSec).put("time_end", now + 60).put("limit", limit)
            .toString()
        return home.call(s, "/user/get_user_device_data", data).optJSONArray("list") ?: JSONArray()
    }

    /** 公开的 MIoT 设备规范：返回 (key, type, 描述) 列表。 */
    private fun specKeys(specType: String): List<Triple<String, String, String>> {
        val req = Request.Builder().url("https://miot-spec.org/miot-spec-v2/instance?type=$specType").build()
        val spec = http.newCall(req).execute().use { JSONObject(it.body?.string().orEmpty()) }
        val out = ArrayList<Triple<String, String, String>>()
        val services = spec.optJSONArray("services") ?: return out
        for (i in 0 until services.length()) {
            val svc = services.optJSONObject(i) ?: continue
            val siid = svc.optInt("iid")
            val sDesc = svc.optString("description")
            svc.optJSONArray("properties")?.let { ps ->
                for (j in 0 until ps.length()) ps.optJSONObject(j)?.let {
                    out += Triple("prop.$siid.${it.optInt("iid")}", "prop", "$sDesc/${it.optString("description")}")
                }
            }
            svc.optJSONArray("events")?.let { es ->
                for (j in 0 until es.length()) es.optJSONObject(j)?.let {
                    out += Triple("event.$siid.${it.optInt("iid")}", "event", "$sDesc/${it.optString("description")}")
                }
            }
        }
        return out
    }

    /**
     * 诊断：列出账号下的秤，逐一尝试体脂秤接口与设备历史接口，把能拿到的原始数据写进日志。
     * 返回探测到可用的型号（eco 接口有数据时）。
     */
    fun diagnose(log: (String) -> Unit): String? {
        log("—— 米家体脂秤诊断开始 ——")
        val hs = login()
        val devices = listDevices(hs)
        val scales = scales(devices)
        log("米家设备 ${devices.size} 台；疑似体脂秤 ${scales.size} 台")
        if (scales.isEmpty()) {
            devices.take(30).forEach { log("  设备：${it.name} | ${it.model}") }
            log("没找到体脂秤：请把上面的设备列表发给开发者")
            return null
        }
        val healthSession = health.login(settings.userId!!, settings.passToken!!)
        settings.passToken = healthSession.passToken
        settings.session = healthSession
        val since30d = System.currentTimeMillis() / 1000 - 30 * 86_400L
        var usable: String? = null

        for (d in scales) {
            log("秤：${d.name} | model=${d.model} | spec=${d.specType ?: "无"}")

            // 1) 体脂秤专用接口（云麦/小米秤走这里）
            try {
                val list = health.fetchScaleData(healthSession, d.model, Long.MAX_VALUE)
                log("  [体脂秤接口] ${list.size} 条" + (list.firstOrNull()?.let { "，示例：$it" } ?: ""))
                if (list.isNotEmpty() && usable == null) usable = d.model
            } catch (e: Exception) {
                log("  [体脂秤接口] 失败：${e.message ?: e.javaClass.simpleName}")
            }

            // 2) 米家设备历史：按设备规范逐个属性 / 事件查询
            val keys = try {
                d.specType?.let { specKeys(it) } ?: emptyList()
            } catch (e: Exception) {
                log("  读取设备规范失败：${e.message}")
                emptyList()
            }
            if (keys.isNotEmpty()) log("  设备规范共 ${keys.size} 项属性/事件")
            val guesses = listOf(
                Triple("weight", "prop", "旧版字段 weight"),
                Triple("weight", "event", "旧版事件 weight"),
                Triple("4100", "prop", "BLE 体重 0x1004"),
                Triple("19992", "prop", "BLE 体脂秤 0x4E18"),
            )
            var hits = 0
            var errors = 0
            for ((key, type, desc) in (keys + guesses).take(60)) {
                val rows = try {
                    deviceData(hs, d.did, key, type, since30d, 3)
                } catch (e: Exception) {
                    if (errors++ == 0) log("  [设备历史] $type $key 查询失败：${e.message ?: e.javaClass.simpleName}")
                    continue
                }
                if (rows.length() == 0) continue
                hits++
                log("  [设备历史] $type $key（$desc）${rows.length()} 条，最新：${rows.optJSONObject(0)?.toString()?.take(400)}")
                Thread.sleep(150)
            }
            if (hits == 0) log("  [设备历史] 最近 30 天没有查到任何记录（$errors 项查询失败）")
        }
        log("—— 诊断结束：请点「复制全部日志」发给开发者 ——")
        return usable
    }
}
