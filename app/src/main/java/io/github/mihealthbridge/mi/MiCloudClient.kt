package io.github.mihealthbridge.mi

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** passToken 失效或会话过期，需要重新登录/重新换取会话。 */
class MiAuthException(message: String) : Exception(message)

class MiApiException(val code: Int, message: String) : Exception("小米 API 错误 code=$code: $message")

/** 换取到的服务会话。 */
data class MiSession(
    val userId: String,
    val passToken: String,
    val ssecurity: String, // base64
    val cookies: String,
) {
    fun toJson(): String = JSONObject()
        .put("userId", userId).put("passToken", passToken)
        .put("ssecurity", ssecurity).put("cookies", cookies).toString()

    companion object {
        fun fromJson(text: String): MiSession? = runCatching {
            val o = JSONObject(text)
            MiSession(o.getString("userId"), o.getString("passToken"), o.getString("ssecurity"), o.getString("cookies"))
        }.getOrNull()
    }
}

/** 云端原始条目：{time, zone_offset, sid, key, category, value}。 */
data class MiItem(
    val time: Long?,
    val zoneOffsetSec: Int?,
    val sid: String?,
    val key: String?,
    val category: String?,
    val value: JSONObject,
)

/**
 * 小米运动健康（com.mi.health）云端非官方接口客户端。
 * 中国大陆账号使用 https://hlth.io.mi.com。
 */
class MiCloudClient(private val baseUrl: String = CN_BASE_URL) {

    private val http = OkHttpClient.Builder()
        .followRedirects(false) // 需要读取 location 响应自身的 Set-Cookie
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 用账号 Cookie（userId + passToken）换取 miothealth 服务会话。 */
    fun login(userId: String, passToken: String): MiSession {
        val req = Request.Builder()
            .url("https://account.xiaomi.com/pass/serviceLogin?_json=true&sid=miothealth")
            .header("Cookie", "userId=$userId; passToken=$passToken")
            .build()
        val text = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("登录 HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        if (!text.startsWith(LOGIN_PREFIX)) throw IOException("登录响应格式异常")
        val obj = JSONObject(text.removePrefix(LOGIN_PREFIX))
        val newPassToken = obj.optString("passToken")
        val ssecurity = obj.optString("ssecurity")
        val location = obj.optString("location")
        if (newPassToken.isEmpty() || ssecurity.isEmpty() || location.isEmpty()) {
            throw MiAuthException("passToken 已失效（code=${obj.optInt("code")} ${obj.optString("description")}），请重新登录")
        }
        val resolvedUserId = obj.optString("userId").ifEmpty { userId }

        val cookies = http.newCall(Request.Builder().url(location).build()).execute().use { resp ->
            resp.headers("Set-Cookie")
                .map { it.substringBefore(';') }
                .filter { it.contains('=') }
                .joinToString("; ")
        }
        if (!cookies.contains("serviceToken")) throw IOException("未拿到 serviceToken")
        return MiSession(resolvedUserId, newPassToken, ssecurity, cookies)
    }

    /** 拉取 get_fitness_data_by_time 某个 key 的全部分页数据。 */
    fun fetchData(session: MiSession, key: String, startSec: Long, endSec: Long): List<MiItem> =
        paginate(session, DATA_PATH, key, startSec, endSec, limit = null, listField = "data_list")

    /** 运动记录走单独接口。 */
    fun fetchSportRecords(session: MiSession, startSec: Long, endSec: Long): List<MiItem> =
        paginate(session, SPORT_PATH, "sport", startSec, endSec, limit = 50, listField = "sport_records")

    private fun paginate(
        session: MiSession, path: String, key: String,
        startSec: Long, endSec: Long, limit: Int?, listField: String,
    ): List<MiItem> {
        val out = ArrayList<MiItem>()
        var nextKey: String? = null
        val seen = HashSet<String>()
        for (page in 1..MAX_PAGES) {
            val result = request(session, path, dataString(startSec, endSec, key, limit, nextKey))
            result.optJSONArray(listField)?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out += parseItem(it) }
            }
            val cursor = result.optString("next_key").takeIf { it.isNotEmpty() && it != "null" }
            if (!result.optBoolean("has_more", false) || cursor == null) return out
            if (!seen.add(cursor)) throw IOException("分页游标循环")
            nextKey = cursor
            Thread.sleep(200) // 避免触发限流
        }
        throw IOException("分页超过上限")
    }

    private fun request(session: MiSession, path: String, data: String): JSONObject {
        var last: Exception? = null
        for (attempt in 0..2) {
            if (attempt > 0) Thread.sleep(1000L shl (attempt - 1))
            try {
                return requestOnce(session, path, data)
            } catch (e: IOException) {
                last = e // 网络/5xx 重试；认证与业务错误直接抛出
            }
        }
        throw last ?: IOException("请求失败")
    }

    private fun requestOnce(session: MiSession, path: String, data: String): JSONObject {
        val nonce = MiCrypto.genNonce()
        val signedNonce = MiCrypto.signedNonce(MiCrypto.unb64(session.ssecurity), nonce)
        val form = FormBody.Builder().apply {
            MiCrypto.buildForm(path, data, signedNonce, nonce).forEach { (k, v) -> add(k, v) }
        }.build()
        val req = Request.Builder()
            .url(baseUrl + path)
            .header("Cookie", session.cookies)
            .post(form)
            .build()
        val body = http.newCall(req).execute().use { resp ->
            when {
                resp.code == 401 || resp.code == 403 -> throw MiAuthException("会话失效 HTTP ${resp.code}")
                resp.code >= 500 || resp.code == 429 -> throw IOException("HTTP ${resp.code}")
                !resp.isSuccessful -> throw MiApiException(resp.code, "HTTP ${resp.code}")
            }
            resp.body?.string().orEmpty()
        }
        val plain = try {
            String(MiCrypto.rc4(signedNonce, MiCrypto.unb64(body)), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw MiApiException(-1, "响应不是 Base64：${body.take(120)}")
        }
        val obj = JSONObject(plain)
        val code = obj.optInt("code", -1)
        if (code == AUTH_ERROR_CODE) throw MiAuthException("会话失效 code=$code")
        if (code != 0) throw MiApiException(code, obj.optString("message"))
        return obj.optJSONObject("result") ?: JSONObject()
    }

    companion object {
        const val CN_BASE_URL = "https://hlth.io.mi.com"
        private const val LOGIN_PREFIX = "&&&START&&&"
        private const val DATA_PATH = "/app/v1/data/get_fitness_data_by_time"
        private const val SPORT_PATH = "/app/v1/data/get_sport_records_by_time"
        private const val MAX_PAGES = 200
        private const val AUTH_ERROR_CODE = 70016

        /** 字段顺序必须固定（与已验证的 Python 实现逐字节一致）。 */
        fun dataString(start: Long, end: Long, key: String, limit: Int? = null, nextKey: String? = null): String {
            val sb = StringBuilder("{\"start_time\":$start,\"end_time\":$end,\"key\":\"$key\"")
            if (limit != null) sb.append(",\"limit\":$limit")
            if (nextKey != null) sb.append(",\"next_key\":").append(JSONObject.quote(nextKey))
            return sb.append('}').toString()
        }

        fun parseItem(raw: JSONObject): MiItem {
            val value = when (val v = raw.opt("value")) {
                is JSONObject -> v
                is String -> runCatching { JSONObject(v) }.getOrDefault(JSONObject())
                else -> JSONObject()
            }
            return MiItem(
                time = raw.optLongOrNull("time"),
                zoneOffsetSec = raw.optLongOrNull("zone_offset")?.toInt(),
                sid = raw.optString("sid").ifEmpty { null },
                key = raw.optString("key").ifEmpty { null },
                category = raw.optString("category").ifEmpty { null },
                value = value,
            )
        }
    }
}

/** 宽容取数：兼容数字与数字字符串，0/缺失语义交给调用方。 */
fun JSONObject.optLongOrNull(name: String): Long? = when (val v = opt(name)) {
    is Number -> v.toLong()
    is String -> v.toDoubleOrNull()?.toLong()
    else -> null
}

fun JSONObject.optDoubleOrNull(name: String): Double? = when (val v = opt(name)) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull()
    else -> null
}

fun JSONObject.firstLong(vararg names: String): Long? = names.firstNotNullOfOrNull { optLongOrNull(it) }
