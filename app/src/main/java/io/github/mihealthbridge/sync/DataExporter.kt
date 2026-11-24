package io.github.mihealthbridge.sync

import android.content.Context
import android.net.Uri
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 导出为 CSV（UTF-8 带 BOM，Excel 可直接打开）。
 * 列：类型, 开始时间, 结束时间, 数值, 单位, 备注, 记录 ID
 */
object DataExporter {

    class Result(val rows: Int, val errors: List<String>)

    private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    suspend fun export(context: Context, uri: Uri, days: Int): Result {
        val data = SyncEngine(context).collectForExport(days)
        val rows = ArrayList<List<String>>()
        data.records.sortedBy { startOf(it) }.forEach { rows += toRows(it) }

        // 蓝牙体脂秤：本机记录的称重历史
        for (line in Settings(context).scaleBleHistory) {
            val p = line.split(',')
            val sec = p.getOrNull(0)?.toLongOrNull() ?: continue
            if (sec < data.startSec) continue
            val note = buildString {
                append("秤显示 ${(p.getOrNull(2)?.toIntOrNull() ?: 0) / 10.0}${if (p.getOrNull(3) == "1") " 斤" else " kg"}")
                p.getOrNull(4)?.toIntOrNull()?.takeIf { it > 0 }?.let { append("；阻抗 $it Ω") }
            }
            rows += row("体重（蓝牙秤）", Instant.ofEpochSecond(sec), null, p.getOrNull(1) ?: "", "kg", note, "mi_ble_weight_$sec")
        }

        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { w ->
            w.write("﻿")
            w.write(csvLine(listOf("类型", "开始时间", "结束时间", "数值", "单位", "备注", "记录ID")))
            rows.forEach { w.write(csvLine(it)) }
        } ?: throw IllegalStateException("无法写入所选文件")
        return Result(rows.size, data.errors)
    }

    private fun startOf(r: Record): Instant = when (r) {
        is StepsRecord -> r.startTime
        is DistanceRecord -> r.startTime
        is ActiveCaloriesBurnedRecord -> r.startTime
        is HeartRateRecord -> r.startTime
        is SleepSessionRecord -> r.startTime
        is ExerciseSessionRecord -> r.startTime
        is RestingHeartRateRecord -> r.time
        is OxygenSaturationRecord -> r.time
        is RespiratoryRateRecord -> r.time
        is WeightRecord -> r.time
        is BodyFatRecord -> r.time
        is BodyWaterMassRecord -> r.time
        is BoneMassRecord -> r.time
        is LeanBodyMassRecord -> r.time
        is BasalMetabolicRateRecord -> r.time
        else -> Instant.EPOCH
    }

    private fun fmtNum(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.2f", v)

    private fun toRows(r: Record): List<List<String>> {
        val id = r.metadata.clientRecordId.orEmpty()
        return when (r) {
            is StepsRecord -> listOf(row("步数", r.startTime, r.endTime, r.count.toString(), "步", "", id))
            is DistanceRecord -> listOf(row("距离", r.startTime, r.endTime, fmtNum(r.distance.inMeters), "m", "", id))
            is ActiveCaloriesBurnedRecord -> listOf(row("活动热量", r.startTime, r.endTime, fmtNum(r.energy.inKilocalories), "kcal", "", id))
            is HeartRateRecord -> r.samples.map { row("心率", it.time, null, it.beatsPerMinute.toString(), "bpm", "", id) }
            is RestingHeartRateRecord -> listOf(row("静息心率", r.time, null, r.beatsPerMinute.toString(), "bpm", "", id))
            is OxygenSaturationRecord -> listOf(row("血氧", r.time, null, fmtNum(r.percentage.value), "%", "", id))
            is RespiratoryRateRecord -> listOf(row("呼吸频率", r.time, null, fmtNum(r.rate), "次/分", "睡眠平均", id))
            is SleepSessionRecord -> {
                val minutes = Duration.between(r.startTime, r.endTime).toMinutes()
                listOf(row("睡眠", r.startTime, r.endTime, minutes.toString(), "分钟", listOfNotNull(r.title, r.notes).joinToString("；"), id)) +
                    r.stages.map { st ->
                        row("睡眠分期", st.startTime, st.endTime, Duration.between(st.startTime, st.endTime).toMinutes().toString(), "分钟", stageName(st.stage), id)
                    }
            }
            is ExerciseSessionRecord -> listOf(
                row("运动", r.startTime, r.endTime, Duration.between(r.startTime, r.endTime).toMinutes().toString(), "分钟",
                    listOfNotNull(r.title, r.notes).joinToString("；"), id),
            )
            is WeightRecord -> listOf(row("体重", r.time, null, fmtNum(r.weight.inKilograms), "kg", "", id))
            is BodyFatRecord -> listOf(row("体脂率", r.time, null, fmtNum(r.percentage.value), "%", "", id))
            is BodyWaterMassRecord -> listOf(row("体内水分", r.time, null, fmtNum(r.mass.inKilograms), "kg", "", id))
            is BoneMassRecord -> listOf(row("骨量", r.time, null, fmtNum(r.mass.inKilograms), "kg", "", id))
            is LeanBodyMassRecord -> listOf(row("去脂体重", r.time, null, fmtNum(r.mass.inKilograms), "kg", "", id))
            is BasalMetabolicRateRecord -> listOf(row("基础代谢", r.time, null, fmtNum(r.basalMetabolicRate.inKilocaloriesPerDay), "kcal/天", "", id))
            else -> emptyList()
        }
    }

    private fun stageName(stage: Int) = when (stage) {
        SleepSessionRecord.STAGE_TYPE_DEEP -> "深睡"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "浅睡"
        SleepSessionRecord.STAGE_TYPE_REM -> "REM"
        SleepSessionRecord.STAGE_TYPE_AWAKE -> "清醒"
        else -> "睡眠"
    }

    private fun row(type: String, start: Instant, end: Instant?, value: String, unit: String, note: String, id: String) =
        listOf(type, timeFmt.format(start), end?.let { timeFmt.format(it) } ?: "", value, unit, note, id)

    private fun csvLine(cells: List<String>): String =
        cells.joinToString(",") { c ->
            if (c.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + c.replace("\"", "\"\"") + "\"" else c
        } + "\r\n"
}
