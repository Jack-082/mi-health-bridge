package io.github.mihealthbridge.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.mihealthbridge.mi.MiSession

/** 凭据（加密存储）与同步状态。 */
class Settings(context: Context) {

    private val secure: SharedPreferences = run {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "secure_prefs", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
    private val prefs: SharedPreferences = context.getSharedPreferences("state", Context.MODE_PRIVATE)

    var userId: String?
        get() = secure.getString("userId", null)
        set(v) = secure.edit().putString("userId", v).apply()

    var passToken: String?
        get() = secure.getString("passToken", null)
        set(v) = secure.edit().putString("passToken", v).apply()

    var session: MiSession?
        get() = secure.getString("session", null)?.let { MiSession.fromJson(it) }
        set(v) = secure.edit().putString("session", v?.toJson()).apply()

    val isLoggedIn: Boolean get() = !userId.isNullOrEmpty() && !passToken.isNullOrEmpty()

    fun logout() = secure.edit().clear().apply()

    /** 上次成功同步的时刻（毫秒），用于计算增量窗口。 */
    var lastSuccessMillis: Long
        get() = prefs.getLong("lastSuccess", 0L)
        set(v) = prefs.edit().putLong("lastSuccess", v).apply()

    var lastResult: String
        get() = prefs.getString("lastResult", "") ?: ""
        set(v) = prefs.edit().putString("lastResult", v).apply()

    /** 首次同步回溯天数。 */
    var initialDays: Int
        get() = prefs.getInt("initialDays", 7)
        set(v) = prefs.edit().putInt("initialDays", v).apply()

    /** 快速模式轮询间隔（分钟），0 = 关闭，只用 15 分钟周期任务。 */
    var fastIntervalMinutes: Int
        get() = prefs.getInt("fastInterval", 0)
        set(v) = prefs.edit().putInt("fastInterval", v).apply()

    /** 最近一次同步的分类型结果（[SyncReport] 的 JSON）。 */
    var lastReport: String
        get() = prefs.getString("lastReport", "") ?: ""
        set(v) = prefs.edit().putString("lastReport", v).apply()

    /** 用户是否已阅读并同意免责声明（首次启动时弹窗）。 */
    var disclaimerAccepted: Boolean
        get() = prefs.getBoolean("disclaimerAccepted", false)
        set(v) = prefs.edit().putBoolean("disclaimerAccepted", v).apply()

    /** 是否同步米家 App 绑定的体脂秤。 */
    var scaleEnabled: Boolean
        get() = prefs.getBoolean("scaleEnabled", false)
        set(v) = prefs.edit().putBoolean("scaleEnabled", v).apply()

    /** 自动探测到的体脂秤型号（如 yunmai.scales.ms103）；空 = 尚未探测到。 */
    var scaleModel: String
        get() = prefs.getString("scaleModel", "") ?: ""
        set(v) = prefs.edit().putString("scaleModel", v).apply()

    /** true：体脂秤数据走米家云 /eco/scale/getData；false：走小米运动健康的 eco 代理。 */
    var scaleDirect: Boolean
        get() = prefs.getBoolean("scaleDirect", true)
        set(v) = prefs.edit().putBoolean("scaleDirect", v).apply()

    /** 米家体脂秤上次成功拉取并写入的时刻（独立于全局同步窗口）。 */
    var scaleLastSuccessMillis: Long
        get() = prefs.getLong("scaleLastSuccess", 0L)
        set(v) = prefs.edit().putLong("scaleLastSuccess", v).apply()

    /** 上次探测型号的时刻；没探测到时每 6 小时最多重试一次。 */
    var scaleProbeAt: Long
        get() = prefs.getLong("scaleProbeAt", 0L)
        set(v) = prefs.edit().putLong("scaleProbeAt", v).apply()

    /** 只同步这个秤上成员的数据；空 = 秤上只有一位成员时自动同步。 */
    var scaleMember: String
        get() = prefs.getString("scaleMember", "") ?: ""
        set(v) = prefs.edit().putString("scaleMember", v).apply()

    /** 在称重数据里见过的成员名称（供界面选择）。 */
    var scaleMembersSeen: List<String>
        get() = (prefs.getString("scaleMembers", "") ?: "").split('\n').filter { it.isNotEmpty() }
        set(v) = prefs.edit().putString("scaleMembers", v.distinct().joinToString("\n")).apply()

    var needsRelogin: Boolean
        get() = prefs.getBoolean("needsRelogin", false)
        set(v) = prefs.edit().putBoolean("needsRelogin", v).apply()

    fun appendLog(line: String) {
        val lines = (prefs.getString("log", "") ?: "").lines().filter { it.isNotBlank() }
        val updated = (lines + line).takeLast(200).joinToString("\n")
        prefs.edit().putString("log", updated).apply()
    }

    val log: String get() = prefs.getString("log", "") ?: ""

    /** 记录已输出过的睡眠诊断签名；返回 true 表示是新内容，需要写日志。 */
    fun markSleepDiagSeen(signature: String): Boolean {
        val seen = (prefs.getString("sleepDiag", "") ?: "").split('\n').filter { it.isNotEmpty() }
        if (signature in seen) return false
        prefs.edit().putString("sleepDiag", (seen + signature).takeLast(120).joinToString("\n")).apply()
        return true
    }

    /** 全量重同步时调用，让睡眠诊断重新输出。 */
    fun clearSleepDiag() = prefs.edit().remove("sleepDiag").apply()
}
