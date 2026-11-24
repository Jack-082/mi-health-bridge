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

    var needsRelogin: Boolean
        get() = prefs.getBoolean("needsRelogin", false)
        set(v) = prefs.edit().putBoolean("needsRelogin", v).apply()

    fun appendLog(line: String) {
        val lines = (prefs.getString("log", "") ?: "").lines().filter { it.isNotBlank() }
        val updated = (lines + line).takeLast(200).joinToString("\n")
        prefs.edit().putString("log", updated).apply()
    }

    val log: String get() = prefs.getString("log", "") ?: ""

    fun resetSyncState() = prefs.edit().remove("lastSuccess").apply()
}
