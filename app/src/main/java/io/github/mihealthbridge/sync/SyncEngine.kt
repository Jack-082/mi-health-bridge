package io.github.mihealthbridge.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import io.github.mihealthbridge.hc.HealthConnectWriter
import io.github.mihealthbridge.hc.RecordMapper
import io.github.mihealthbridge.mi.MiAuthException
import io.github.mihealthbridge.mi.MiCloudClient
import io.github.mihealthbridge.mi.MiSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

sealed interface SyncOutcome {
    data class Success(val written: Int) : SyncOutcome
    data object NotLoggedIn : SyncOutcome
    data object NeedsRelogin : SyncOutcome
    data object HealthConnectUnavailable : SyncOutcome
    data class Failed(val error: Throwable) : SyncOutcome
}

/**
 * 一次同步：小米云端 → Health Connect。
 *
 * 增量窗口 = [ (上次成功 - 6 小时) 所在自然日的 00:00 , 现在 + 1 小时 ]。
 * 从自然日零点起拉取，保证“每日步数”这类汇总记录不会被半天数据覆盖；
 * 回看 6 小时用于兜住手环延迟上传到云端的数据。重复写入靠 clientRecordId 去重。
 */
class SyncEngine(private val context: Context) {

    private val settings = Settings(context)
    private val cloud = MiCloudClient()

    suspend fun run(): SyncOutcome = lock.withLock {
        withContext(Dispatchers.IO) {
            val outcome = try {
                doSync()
            } catch (e: Exception) {
                SyncOutcome.Failed(e)
            }
            settings.lastResult = describe(outcome)
            log(settings.lastResult)
            outcome
        }
    }

    private suspend fun doSync(): SyncOutcome {
        if (!settings.isLoggedIn) return SyncOutcome.NotLoggedIn
        if (HealthConnectWriter.sdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return SyncOutcome.HealthConnectUnavailable
        }

        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        val last = settings.lastSuccessMillis
        val anchorSec = if (last > 0) last / 1000 - 6 * 3600 else nowSec - settings.initialDays * 86_400L
        val zone = ZoneId.systemDefault()
        val startSec = Instant.ofEpochSecond(anchorSec).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toEpochSecond()
        val endSec = nowSec + 3600

        val (records, failures) = try {
            fetchAll(sessionOrLogin(), startSec, endSec, nowMs)
        } catch (e: MiAuthException) {
            // 会话过期：用 passToken 重新换一次会话再试
            log("会话失效，尝试重新换取：${e.message}")
            settings.session = null
            try {
                fetchAll(sessionOrLogin(), startSec, endSec, nowMs)
            } catch (e2: MiAuthException) {
                settings.needsRelogin = true
                return SyncOutcome.NeedsRelogin
            }
        }

        val written = HealthConnectWriter(context).write(records) { log(it) }
        settings.needsRelogin = false
        // 有数据项拉取失败时不推进窗口，下次会重拉（重复写入由 clientRecordId 去重）
        if (failures == 0) settings.lastSuccessMillis = nowMs else log("$failures 项拉取失败，保留同步窗口")
        return SyncOutcome.Success(written)
    }

    private fun sessionOrLogin(): MiSession {
        settings.session?.let { return it }
        val session = cloud.login(settings.userId!!, settings.passToken!!)
        settings.passToken = session.passToken // passToken 会轮换，必须保存新的
        settings.userId = session.userId
        settings.session = session
        return session
    }

    private fun fetchAll(s: MiSession, start: Long, end: Long, nowMs: Long): Pair<List<Record>, Int> {
        val mapper = RecordMapper(nowMillis = nowMs)
        val out = ArrayList<Record>()
        var failures = 0
        fun step(name: String, block: () -> List<Record>) {
            try {
                val r = block()
                out += r
                log("$name: ${r.size} 条")
            } catch (e: MiAuthException) {
                throw e
            } catch (e: Exception) {
                failures++
                log("$name 拉取失败：${e.message}") // 单项失败不影响其他数据
            }
        }
        step("步数/距离/热量") {
            mapper.dailyActivity(cloud.fetchData(s, "steps", start, end), cloud.fetchData(s, "calories", start, end))
        }
        step("心率") { mapper.heartRate(cloud.fetchData(s, "heart_rate", start, end)) }
        step("静息心率") { mapper.restingHeartRate(cloud.fetchData(s, "resting_heart_rate", start, end)) }
        step("血氧") { mapper.spo2(cloud.fetchData(s, "spo2", start, end)) }
        // 睡眠记录的时间戳通常在醒来时，多回看一天
        step("睡眠") { mapper.sleep(cloud.fetchData(s, "sleep", start - 86_400, end)) }
        step("运动") { mapper.workouts(cloud.fetchSportRecords(s, start - 86_400, end)) }
        step("体重") { mapper.body(cloud.fetchData(s, "weight", start, end)) }
        return out to failures
    }

    private fun describe(o: SyncOutcome): String = when (o) {
        is SyncOutcome.Success -> "同步成功，写入/更新 ${o.written} 条"
        SyncOutcome.NotLoggedIn -> "未登录小米账号"
        SyncOutcome.NeedsRelogin -> "小米登录已过期，请重新登录"
        SyncOutcome.HealthConnectUnavailable -> "Health Connect 不可用"
        is SyncOutcome.Failed -> "同步失败：${o.error.javaClass.simpleName} ${o.error.message}"
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        settings.appendLog("$ts  $msg")
    }

    companion object {
        private val lock = Mutex()
    }
}
