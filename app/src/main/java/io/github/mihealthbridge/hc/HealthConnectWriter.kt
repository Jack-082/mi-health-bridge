package io.github.mihealthbridge.hc

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import kotlin.reflect.KClass

class HealthConnectWriter(context: Context) {

    private val client = HealthConnectClient.getOrCreate(context)

    suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    /**
     * 写入记录：只写已授权的类型；整批失败时退回逐条写入，避免一条脏数据拖垮整批。
     * @return 成功写入（或更新）的条数
     */
    suspend fun write(records: List<Record>, log: (String) -> Unit): Int {
        if (records.isEmpty()) return 0
        val granted = grantedPermissions()
        val allowed = records.filter { HealthPermission.getWritePermission(it::class) in granted }
        val skipped = records.size - allowed.size
        if (skipped > 0) log("跳过 $skipped 条未授权类型的记录")

        var ok = 0
        for (chunk in allowed.chunked(CHUNK)) {
            try {
                client.insertRecords(chunk)
                ok += chunk.size
            } catch (e: Exception) {
                Log.w(TAG, "batch insert failed, retry one by one", e)
                for (r in chunk) {
                    try {
                        client.insertRecords(listOf(r))
                        ok++
                    } catch (e2: Exception) {
                        log("写入失败 ${r::class.simpleName} ${r.metadata.clientRecordId}: ${e2.message}")
                    }
                }
            }
        }
        return ok
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
            ExerciseSessionRecord::class,
        )

        val PERMISSIONS: Set<String> = RECORD_TYPES.map { HealthPermission.getWritePermission(it) }.toSet()

        fun sdkStatus(context: Context): Int = HealthConnectClient.getSdkStatus(context)
    }
}
