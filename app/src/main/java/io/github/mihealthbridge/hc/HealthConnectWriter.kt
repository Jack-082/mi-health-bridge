package io.github.mihealthbridge.hc

import android.content.Context
import android.util.Log
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
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

class HealthConnectWriter(context: Context) {

    private val client = HealthConnectClient.getOrCreate(context)

    suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    /** 写入前先删除本应用在 [start, end) 内写过的 [type] 记录（Health Connect 只删除调用方自己的数据）。 */
    data class Replace(val type: KClass<out Record>, val start: Instant, val end: Instant)

    /** 某一类记录的写入结果。 */
    data class TypeCount(var written: Int = 0, var failed: Int = 0)

    /** 一次写入的结果：各类型计数 + 当时已授权的写入权限。 */
    class WriteResult(val counts: Map<KClass<out Record>, TypeCount>, val granted: Set<String>) {
        val written: Int get() = counts.values.sumOf { it.written }
    }

    /**
     * 写入记录：只写已授权的类型；整批失败时退回逐条写入，避免一条脏数据拖垮整批。
     * [replace] 中的时间段会先清空再写入，用于清理 ID 变化后残留的旧记录。
     */
    suspend fun write(records: List<Record>, replace: List<Replace> = emptyList(), log: (String) -> Unit): WriteResult {
        val granted = grantedPermissions()
        val counts = HashMap<KClass<out Record>, TypeCount>()
        fun tally(r: Record) = counts.getOrPut(r::class) { TypeCount() }

        for (r in replace) {
            if (HealthPermission.getWritePermission(r.type) !in granted) continue
            try {
                client.deleteRecords(r.type, TimeRangeFilter.between(r.start, r.end))
            } catch (e: CancellationException) {
                throw e // 任务被取消：交给协程框架，不当作数据错误
            } catch (e: Exception) {
                log("清理旧 ${r.type.simpleName} 失败：${e.message}")
            }
        }
        val allowed = records.filter { HealthPermission.getWritePermission(it::class) in granted }
        val skipped = records.size - allowed.size
        if (skipped > 0) log("跳过 $skipped 条未授权类型的记录")

        for (chunk in allowed.chunked(CHUNK)) {
            try {
                client.insertRecords(chunk)
                chunk.forEach { r -> tally(r).let { it.written = it.written + 1 } }
            } catch (e: CancellationException) {
                throw e // 任务被取消：交给协程框架，不当作数据错误
            } catch (e: Exception) {
                Log.w(TAG, "batch insert failed, retry one by one", e)
                for (r in chunk) {
                    try {
                        client.insertRecords(listOf(r))
                        tally(r).let { it.written = it.written + 1 }
                    } catch (e: CancellationException) {
                        throw e // 任务被取消：交给协程框架，不当作数据错误
                    } catch (e2: Exception) {
                        tally(r).let { it.failed = it.failed + 1 }
                        log("写入失败 ${r::class.simpleName} ${r.metadata.clientRecordId}: ${e2.message}")
                    }
                }
            }
        }
        return WriteResult(counts, granted)
    }

    companion object {
        private const val TAG = "HCWriter"
        private const val CHUNK = 500

        val RECORD_TYPES: List<KClass<out Record>> = listOf(
            StepsRecord::class,
            DistanceRecord::class,
            ActiveCaloriesBurnedRecord::class,
            HeartRateRecord::class,
            RestingHeartRateRecord::class,
            OxygenSaturationRecord::class,
            SleepSessionRecord::class,
            RespiratoryRateRecord::class,
            WeightRecord::class,
            BodyFatRecord::class,
            BodyWaterMassRecord::class,
            BoneMassRecord::class,
            LeanBodyMassRecord::class,
            BasalMetabolicRateRecord::class,
            ExerciseSessionRecord::class,
        )

        val PERMISSIONS: Set<String> = RECORD_TYPES.map { HealthPermission.getWritePermission(it) }.toSet()

        /** 界面上显示的中文名称，顺序与 [RECORD_TYPES] 一致。 */
        val LABELS: Map<KClass<out Record>, String> = mapOf(
            StepsRecord::class to "步数",
            DistanceRecord::class to "距离",
            ActiveCaloriesBurnedRecord::class to "活动热量",
            HeartRateRecord::class to "心率",
            RestingHeartRateRecord::class to "静息心率",
            OxygenSaturationRecord::class to "血氧",
            SleepSessionRecord::class to "睡眠",
            RespiratoryRateRecord::class to "呼吸频率",
            WeightRecord::class to "体重",
            BodyFatRecord::class to "体脂率",
            BodyWaterMassRecord::class to "体内水分",
            BoneMassRecord::class to "骨量",
            LeanBodyMassRecord::class to "去脂体重",
            BasalMetabolicRateRecord::class to "基础代谢",
            ExerciseSessionRecord::class to "运动记录",
        )

        fun sdkStatus(context: Context): Int = HealthConnectClient.getSdkStatus(context)
    }
}
