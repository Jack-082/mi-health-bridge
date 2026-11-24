package io.github.mihealthbridge.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import io.github.mihealthbridge.hc.HealthConnectWriter
import io.github.mihealthbridge.hc.RecordMapper
import io.github.mihealthbridge.mi.MiAuthException
import io.github.mihealthbridge.mi.MiCloudClient
import io.github.mihealthbridge.mi.MiScaleParser
import io.github.mihealthbridge.mi.MiSession
import io.github.mihealthbridge.mi.ScaleWeighIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass
import java.io.IOException
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

    /** [full] = true：忽略上次同步时间，按回溯天数重新拉取（不清空状态，避免与并发的自动同步互相覆盖）。 */
    suspend fun run(full: Boolean = false): SyncOutcome = lock.withLock {
        withContext(Dispatchers.IO) {
            val outcome = try {
                doSync(full)
            } catch (e: CancellationException) {
                throw e // 任务被取消：交给协程框架，不当作数据错误
            } catch (e: Exception) {
                SyncOutcome.Failed(e)
            }
            settings.lastResult = describe(outcome)
            log(settings.lastResult)
            if (outcome !is SyncOutcome.Success) {
                // 整次同步没有完成：所有类型都标为失败，并给出原因
                val reason = settings.lastResult
                settings.lastReport = SyncReport(
                    atMillis = System.currentTimeMillis(), windowStartSec = 0, windowEndSec = 0,
                    items = HealthConnectWriter.RECORD_TYPES.map {
                        SyncReport.TypeResult(label(it), SyncReport.State.Failed, reason = reason)
                    },
                ).toJson()
            }
            outcome
        }
    }

    private suspend fun doSync(full: Boolean): SyncOutcome {
        if (!settings.isLoggedIn) return SyncOutcome.NotLoggedIn
        if (HealthConnectWriter.sdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return SyncOutcome.HealthConnectUnavailable
        }

        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        val last = settings.lastSuccessMillis
        val anchorSec = if (!full && last > 0) last / 1000 - 6 * 3600 else nowSec - settings.initialDays * 86_400L
        if (full) {
            settings.clearSleepDiag()
            log("开始全量重同步：回溯 ${settings.initialDays} 天")
        }
        val zone = ZoneId.systemDefault()
        val startSec = Instant.ofEpochSecond(anchorSec).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toEpochSecond()
        val endSec = nowSec + 3600

        val fetched = try {
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

        val result = HealthConnectWriter(context).write(fetched.records, fetched.replace) { log(it) }
        settings.needsRelogin = false
        settings.lastReport = buildReport(nowMs, startSec, endSec, fetched.fetchErrors, result).toJson()
        // 有数据项拉取失败时不推进窗口，下次会重拉（重复写入由 clientRecordId 去重）
        if (fetched.windowErrors == 0) settings.lastSuccessMillis = nowMs
        else log("${fetched.windowErrors} 项数据拉取失败，保留同步窗口")
        // 米家体脂秤：拉取成功、体重已授权且没有写入失败，才推进它自己的窗口
        if (fetched.scaleOk &&
            HealthPermission.getWritePermission(WeightRecord::class) in result.granted &&
            BODY_TYPES.none { (result.counts[it]?.failed ?: 0) > 0 }
        ) settings.scaleLastSuccessMillis = nowMs
        return SyncOutcome.Success(result.written)
    }

    private fun label(type: KClass<out Record>) = HealthConnectWriter.LABELS[type] ?: type.simpleName.orEmpty()

    /** 按各类数据汇总：未授权 > 拉取失败 > 写入结果 > 无数据。 */
    private fun buildReport(
        nowMs: Long, startSec: Long, endSec: Long,
        fetchErrors: Map<KClass<out Record>, String>,
        result: HealthConnectWriter.WriteResult,
    ) = SyncReport(
        atMillis = nowMs, windowStartSec = startSec, windowEndSec = endSec,
        items = HealthConnectWriter.RECORD_TYPES.map { type ->
            val name = label(type)
            val c = result.counts[type]
            val fetchError = fetchErrors[type]
            when {
                HealthPermission.getWritePermission(type) !in result.granted ->
                    SyncReport.TypeResult(name, SyncReport.State.Unauthorized, reason = "未授予 Health Connect 写入权限")
                fetchError != null ->
                    SyncReport.TypeResult(name, SyncReport.State.Failed, reason = "拉取失败：$fetchError")
                c == null || (c.written == 0 && c.failed == 0) ->
                    SyncReport.TypeResult(name, SyncReport.State.NoData)
                c.failed > 0 && c.written == 0 ->
                    SyncReport.TypeResult(name, SyncReport.State.Failed, failed = c.failed, reason = "写入失败 ${c.failed} 条，详见日志")
                c.failed > 0 ->
                    SyncReport.TypeResult(name, SyncReport.State.Partial, c.written, c.failed, "写入失败 ${c.failed} 条，详见日志")
                else -> SyncReport.TypeResult(name, SyncReport.State.Synced, c.written)
            }
        },
    )

    /** 导出用：拉取最近 [days] 天的数据并映射成记录（不写 Health Connect、不推进同步窗口）。 */
    class ExportData(val records: List<Record>, val startSec: Long, val errors: List<String>)

    suspend fun collectForExport(days: Int): ExportData = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!settings.isLoggedIn) throw IllegalStateException("未登录小米账号")
            val nowMs = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val startSec = Instant.ofEpochSecond(nowMs / 1000 - days * 86_400L).atZone(zone).toLocalDate()
                .atStartOfDay(zone).toEpochSecond()
            val endSec = nowMs / 1000 + 3600
            val f = try {
                fetchAll(sessionOrLogin(), startSec, endSec, nowMs)
            } catch (e: MiAuthException) {
                settings.session = null
                fetchAll(sessionOrLogin(), startSec, endSec, nowMs)
            }
            ExportData(f.records, startSec, f.fetchErrors.map { (t, msg) -> "${label(t)}：$msg" }.distinct())
        }
    }

    private fun sessionOrLogin(): MiSession {
        settings.session?.let { return it }
        val session = cloud.login(settings.userId!!, settings.passToken!!)
        settings.passToken = session.passToken // passToken 会轮换，必须保存新的
        settings.userId = session.userId
        settings.session = session
        return session
    }

    private data class Fetched(
        val records: List<Record>,
        val replace: List<HealthConnectWriter.Replace>,
        /** 拉取失败的数据类型 → 错误信息。 */
        val fetchErrors: Map<KClass<out Record>, String>,
        /** 会阻止推进同步窗口的失败步数（米家体脂秤有独立窗口，不计入）。 */
        val windowErrors: Int,
        /** 米家体脂秤这次拉取成功。 */
        val scaleOk: Boolean,
    )

    private fun fetchAll(s: MiSession, start: Long, end: Long, nowMs: Long): Fetched {
        val mapper = RecordMapper(nowMillis = nowMs)
        val out = ArrayList<Record>()
        val replace = ArrayList<HealthConnectWriter.Replace>()
        val fetchErrors = HashMap<KClass<out Record>, String>()
        var windowErrors = 0
        /**
         * [types]：这一步会产出的记录类型，拉取失败时它们都记为失败。
         * [holdsWindow]：失败时是否保留全局同步窗口。返回是否成功。
         */
        fun step(name: String, types: List<KClass<out Record>>, holdsWindow: Boolean = true, block: () -> List<Record>): Boolean {
            try {
                val r = block()
                out += r
                log("$name: ${r.size} 条")
                return true
            } catch (e: MiAuthException) {
                throw e
            } catch (e: CancellationException) {
                throw e // 任务被取消：交给协程框架，不当作数据错误
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                types.forEach { fetchErrors[it] = msg }
                if (holdsWindow) windowErrors++
                log("$name 拉取失败：$msg") // 单项失败不影响其他数据
                return false
            }
        }
        step("步数/距离/热量", listOf(StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class)) {
            mapper.dailyActivity(cloud.fetchData(s, "steps", start, end), cloud.fetchData(s, "calories", start, end))
        }
        step("心率", listOf(HeartRateRecord::class)) { mapper.heartRate(cloud.fetchData(s, "heart_rate", start, end)) }
        step("静息心率", listOf(RestingHeartRateRecord::class)) { mapper.restingHeartRate(cloud.fetchData(s, "resting_heart_rate", start, end)) }
        step("血氧", listOf(OxygenSaturationRecord::class)) { mapper.spo2(cloud.fetchData(s, "spo2", start, end)) }
        step("睡眠", listOf(SleepSessionRecord::class, RespiratoryRateRecord::class)) {
            // 前一晚的入睡时间通常早于 start（当天零点），因此清理从前一天开始；
            // 拉取再多回看一天，保证清理范围内的每一晚都能重新拉到，不会误删。
            val replaceFrom = start - 86_400
            val items = cloud.fetchData(s, "sleep", replaceFrom - 86_400, end)
            for ((sig, line) in mapper.sleepDiagnostics(items)) {
                if (settings.markSleepDiagSeen(sig)) log(line)
            }
            val records = mapper.sleep(items) { if (settings.markSleepDiagSeen(it)) log(it) }
            // 同一晚的记录 ID 可能随云端更新而变化，旧记录会残留成重复睡眠。
            // 拉取成功且有数据时，先清空本应用在清理范围内写过的睡眠/呼吸记录再重写。
            if (records.any { it is SleepSessionRecord }) {
                val from = Instant.ofEpochSecond(replaceFrom)
                val to = Instant.ofEpochSecond(end)
                replace += HealthConnectWriter.Replace(SleepSessionRecord::class, from, to)
                replace += HealthConnectWriter.Replace(RespiratoryRateRecord::class, from, to)
            }
            records
        }
        step("运动", listOf(ExerciseSessionRecord::class)) { mapper.workouts(cloud.fetchSportRecords(s, start - 86_400, end)) }
        val bodyTypes = BODY_TYPES
        step("体重（小米运动健康）", bodyTypes) {
            mapper.weighIns(cloud.fetchData(s, "weight", start, end).mapNotNull(MiScaleParser::fromFitnessItem))
        }
        // 米家体脂秤用独立窗口：从上次成功拉取前 2 天起；从未成功过则按回溯天数
        val scaleOk = settings.scaleEnabled && step("米家体脂秤", bodyTypes, holdsWindow = false) {
            val last = settings.scaleLastSuccessMillis
            val from = if (last > 0) minOf(start, last / 1000 - 2 * 86_400L)
            else minOf(start, nowMs / 1000 - settings.initialDays * 86_400L)
            mapper.weighIns(fetchMijiaScale(s, from))
        }
        return Fetched(out, replace, fetchErrors, windowErrors, scaleOk)
    }

    /**
     * 米家 App 绑定的体脂秤。型号未知时按候选列表自动探测（只看第一页）。
     * 秤上有多位家庭成员且未选择本人时抛错：不写入，也不推进同步窗口，选好成员后下次同步会补上。
     */
    private fun fetchMijiaScale(s: MiSession, startSec: Long): List<ScaleWeighIn> {
        val probe = MiHomeCloud(context)
        var homeSession: MiSession? = null
        // 米家云的认证错误只影响体脂秤这一项，转成普通错误，避免把整个账号标记为需要重新登录
        fun home(): MiSession = homeSession ?: try {
            probe.login().also { homeSession = it }
        } catch (e: MiAuthException) {
            throw IOException("米家云登录失败：${e.message}")
        }
        /** direct = true 走米家云 /eco/scale/getData，否则走小米运动健康的 eco 代理。 */
        fun fetch(model: String, direct: Boolean, sinceMs: Long): List<ScaleWeighIn> =
            if (direct) {
                try {
                    probe.fetchScale(home(), model, sinceMs)
                } catch (e: MiAuthException) {
                    throw IOException("米家云会话失效：${e.message}")
                }
            } else cloud.fetchScaleData(s, model, sinceMs)

        var model = settings.scaleModel
        if (model.isEmpty()) {
            val now = System.currentTimeMillis()
            if (now - settings.scaleProbeAt < 6 * 3_600_000L) {
                throw IOException("尚未找到米家体脂秤，6 小时内不再重复探测")
            }
            settings.scaleProbeAt = now
            // 优先用米家账号里真实绑定的秤的型号，再试已知型号
            val accountModels = try {
                probe.scales(probe.listDevices(home())).map { it.model }.also {
                    log("米家账号中的体脂秤型号：${it.ifEmpty { listOf("无") }.joinToString()}")
                }
            } catch (e: CancellationException) {
                throw e // 任务被取消：交给协程框架，不当作数据错误
            } catch (e: Exception) {
                log("读取米家设备列表失败：${e.message ?: e.javaClass.simpleName}")
                emptyList()
            }
            probing@ for (m in (accountModels + MiScaleParser.CANDIDATE_MODELS).distinct()) {
                for (direct in listOf(true, false)) {
                    val found = try {
                        fetch(m, direct, Long.MAX_VALUE) // 只看第一页
                    } catch (e: MiAuthException) {
                        throw e
                    } catch (e: CancellationException) {
                        throw e // 任务被取消：交给协程框架，不当作数据错误
                    } catch (e: Exception) {
                        log("探测 $m（${if (direct) "米家云" else "运动健康代理"}）：${e.message ?: e.javaClass.simpleName}")
                        emptyList()
                    }
                    if (found.isNotEmpty()) {
                        model = m
                        settings.scaleDirect = direct
                        break@probing
                    }
                }
            }
            if (model.isEmpty()) {
                throw IOException("没有找到米家体脂秤数据：请确认秤已绑定在米家 App，且至少称过一次")
            }
            settings.scaleModel = model
            log("米家体脂秤型号：$model（${if (settings.scaleDirect) "米家云" else "运动健康代理"}）")
        }

        val sinceMs = startSec * 1000
        val all = fetch(model, settings.scaleDirect, sinceMs)
        val members = (settings.scaleMembersSeen + all.mapNotNull { it.member }).distinct()
        settings.scaleMembersSeen = members
        val inWindow = all.filter { it.timeSec * 1000 >= sinceMs }

        val chosen = settings.scaleMember
        return when {
            chosen.isNotEmpty() -> inWindow.filter { it.member == chosen }
            members.size <= 1 -> inWindow
            else -> throw IOException("秤上有 ${members.size} 位成员（${members.joinToString("、")}），请在「米家体脂秤」中选择你自己")
        }
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
        private val BODY_TYPES = listOf(
            WeightRecord::class, BodyFatRecord::class, BodyWaterMassRecord::class,
            BoneMassRecord::class, LeanBodyMassRecord::class, BasalMetabolicRateRecord::class,
        )
    }
}
