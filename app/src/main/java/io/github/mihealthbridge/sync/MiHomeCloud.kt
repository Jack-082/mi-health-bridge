package io.github.mihealthbridge.sync

import android.content.Context
import io.github.mihealthbridge.mi.MiCloudClient
import io.github.mihealthbridge.mi.MiSession
import org.json.JSONArray

/** 米家账号下的一台设备。 */
data class MiHomeDevice(
    val did: String, val name: String, val model: String, val specType: String?, val mac: String,
    /** 蓝牙设备的米家登录 token（24 位 hex）。 */
    val token: String? = null,
)

/**
 * 米家云（api.io.mi.com，sid=xiaomiio）：列设备（含蓝牙设备 token）、查体脂秤称重记录。
 * 与小米运动健康共用同一个小米账号的 passToken，不需要另外登录。
 */
class MiHomeCloud(context: Context) {

    private val settings = Settings(context)
    private val home = MiCloudClient(
        baseUrl = "https://api.io.mi.com/app",
        sid = "xiaomiio",
        extraHeaders = mapOf(
            "MIOT-ENCRYPT-ALGORITHM" to "ENCRYPT-RC4",
            "x-xiaomi-protocal-flag-cli" to "PROTOCAL-HTTP2",
        ),
    )

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
                mac = d.optString("mac").uppercase(),
                token = d.optString("token").ifEmpty { null },
            )
        }
    }

    /** 米家云体脂秤接口（米家 App 读取称重记录用的同一个接口）。 */
    fun fetchScale(s: MiSession, model: String, sinceMs: Long) =
        home.fetchScaleData(s, model, sinceMs, direct = true)

    /** 名称或型号像体脂秤的设备。 */
    fun scales(devices: List<MiHomeDevice>) = devices.filter {
        "scale" in it.model.lowercase() || "秤" in it.name || "scale" in (it.specType ?: "")
    }
}
