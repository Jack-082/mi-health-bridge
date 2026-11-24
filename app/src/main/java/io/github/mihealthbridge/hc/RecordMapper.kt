package io.github.mihealthbridge.hc

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
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import io.github.mihealthbridge.mi.MiItem
import io.github.mihealthbridge.mi.ScaleWeighIn
import io.github.mihealthbridge.mi.firstLong
import io.github.mihealthbridge.mi.optDoubleOrNull
import io.github.mihealthbridge.mi.optLongOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 把小米云端条目映射为 Health Connect 记录。
 *
 * 所有记录都带稳定的 clientRecordId；clientRecordVersion 取写入时刻，
 * 因此重复同步同一时间段会“覆盖更新”而不是产生重复数据。
 */
class RecordMapper(
    private val nowMillis: Long = System.currentTimeMillis(),
    private val defaultOffset: ZoneOffset = ZoneOffset.ofHours(8),
) {
    private val device = Device(manufacturer = "Xiaomi", model = "Smart Band", type = Device.TYPE_WATCH)
    private val scaleDevice = Device(manufacturer = "Xiaomi", model = "Body Composition Scale", type = Device.TYPE_SCALE)
    private val now: Instant = Instant.ofEpochMilli(nowMillis)

    private fun meta(id: String, dev: Device = device) = Metadata(
        clientRecordId = id,
        clientRecordVersion = nowMillis,
        device = dev,
        recordingMethod = Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED,
    )

    private fun offsetOf(item: MiItem): ZoneOffset =
        item.zoneOffsetSec?.let { runCatching { ZoneOffset.ofTotalSeconds(it) }.getOrNull() } ?: defaultOffset

    private fun instant(sec: Long): Instant = Instant.ofEpochSecond(sec)

    // ---------------- 日常活动（按自然日汇总） ----------------

    /**
     * steps key 的 value 含 steps/distance/calories；calories key 的总量更准，优先使用。
     * 每天一条记录，调用方必须保证拉取窗口覆盖整天，否则会被部分数据覆盖。
     */
    fun dailyActivity(stepItems: List<MiItem>, calorieItems: List<MiItem>): List<Record> {
        class Acc(val offset: ZoneOffset) {
            var steps = 0L; var distance = 0.0; var kcal = 0.0; var kcalOverride: Double? = null
            var lastSec = 0L
        }
        val byDay = sortedMapOf<LocalDate, Acc>()
        fun acc(item: MiItem): Acc? {
            val t = item.time ?: return null
            val off = offsetOf(item)
            val day = instant(t).atOffset(off).toLocalDate()
            return byDay.getOrPut(day) { Acc(off) }.also { it.lastSec = maxOf(it.lastSec, t) }
        }
        for (item in stepItems) {
            val a = acc(item) ?: continue
            a.steps += item.value.optLongOrNull("steps") ?: 0
            a.distance += item.value.optDoubleOrNull("distance") ?: 0.0
            a.kcal += item.value.optDoubleOrNull("calories") ?: 0.0
        }
        for (item in calorieItems) {
            val a = acc(item) ?: continue
            a.kcalOverride = (a.kcalOverride ?: 0.0) + (item.value.optDoubleOrNull("calories") ?: 0.0)
        }

        val out = ArrayList<Record>()
        for ((day, a) in byDay) {
            val start = day.atStartOfDay().toInstant(a.offset)
            val dayEnd = day.plusDays(1).atStartOfDay().toInstant(a.offset)
            var end = minOf(instant(a.lastSec + 60), dayEnd, now)
            if (!end.isAfter(start)) end = start.plusSeconds(60)
            val id = day.toString()
            if (a.steps in 1..999_999) {
                out += StepsRecord(startTime = start, startZoneOffset = a.offset, endTime = end, endZoneOffset = a.offset, count = a.steps, metadata = meta("mi_steps_$id"))
            }
            if (a.distance in 0.01..1_000_000.0) {
                out += DistanceRecord(startTime = start, startZoneOffset = a.offset, endTime = end, endZoneOffset = a.offset, distance = Length.meters(a.distance), metadata = meta("mi_distance_$id"))
            }
            val kcal = a.kcalOverride ?: a.kcal
            if (kcal in 0.01..100_000.0) {
                out += ActiveCaloriesBurnedRecord(startTime = start, startZoneOffset = a.offset, endTime = end, endZoneOffset = a.offset, energy = Energy.kilocalories(kcal), metadata = meta("mi_active_kcal_$id"))
            }
        }
        return out
    }

    // ---------------- 心率 ----------------

    /** 按整点小时打包成 HeartRateRecord（调用方保证窗口起点对齐整点）。 */
    fun heartRate(items: List<MiItem>): List<Record> {
        data class S(val sec: Long, val bpm: Long, val off: ZoneOffset)
        val samples = items.mapNotNull { item ->
            val t = item.time ?: return@mapNotNull null
            val bpm = item.value.optLongOrNull("bpm") ?: return@mapNotNull null
            if (bpm !in 1..300) null else S(t, bpm, offsetOf(item))
        }.distinctBy { it.sec }.sortedBy { it.sec }

        return samples.groupBy { it.sec / 3600 }.map { (hour, list) ->
            val start = instant(list.first().sec)
            val end = instant(list.last().sec + 1)
            HeartRateRecord(
                startTime = start, startZoneOffset = list.first().off,
                endTime = end, endZoneOffset = list.last().off,
                samples = list.map { HeartRateRecord.Sample(instant(it.sec), it.bpm) },
                metadata = meta("mi_hr_${hour * 3600}"),
            )
        }
    }

    fun restingHeartRate(items: List<MiItem>): List<Record> = items.mapNotNull { item ->
        val t = item.value.optLongOrNull("date_time") ?: item.time ?: return@mapNotNull null
        val bpm = item.value.optLongOrNull("bpm") ?: return@mapNotNull null
        if (bpm !in 1..300) return@mapNotNull null
        RestingHeartRateRecord(time = instant(t), zoneOffset = offsetOf(item), beatsPerMinute = bpm, metadata = meta("mi_rhr_$t"))
    }.distinctBy { it.metadata.clientRecordId }

    // ---------------- 血氧 ----------------

    fun spo2(items: List<MiItem>): List<Record> = items.mapNotNull { item ->
        val t = item.value.optLongOrNull("time") ?: item.time ?: return@mapNotNull null
        val v = item.value.optDoubleOrNull("spo2") ?: item.value.optDoubleOrNull("value") ?: return@mapNotNull null
        if (v <= 0 || v > 100) return@mapNotNull null
        OxygenSaturationRecord(time = instant(t), zoneOffset = offsetOf(item), percentage = Percentage(v), metadata = meta("mi_spo2_$t"))
    }.distinctBy { it.metadata.clientRecordId }

    // ---------------- 睡眠 ----------------

    private class SleepCandidate(val item: MiItem, val startSec: Long, val endSec: Long)

    private fun sleepBounds(item: MiItem): SleepCandidate? {
        val v = item.value
        val startSec = v.firstLong("bedtime", "device_bedtime", "bed_timestamp") ?: return null
        val endSec = v.firstLong("wake_up_time", "device_wake_up_time", "out_bed_timestamp") ?: item.time ?: return null
        return if (endSec > startSec) SleepCandidate(item, startSec, endSec) else null
    }

    /**
     * 返回睡眠记录 + 睡眠期间平均呼吸频率。
     *
     * 云端同一晚可能返回多条互相重叠的条目（例如未睡完时上传的半截记录和完整记录），
     * 重叠时只保留 time 较新的一条（相同则保留时长较长的），并通过 [log] 记录下来。
     */
    fun sleep(items: List<MiItem>, log: (String) -> Unit = {}): List<Record> {
        val kept = ArrayList<SleepCandidate>()
        for (c in items.mapNotNull { sleepBounds(it) }.sortedBy { it.startSec }) {
            val prev = kept.lastOrNull()
            if (prev == null || c.startSec >= prev.endSec) {
                kept += c
                continue
            }
            val cNewer = compareValuesBy(c, prev, { it.item.time ?: 0L }, { it.endSec - it.startSec }) >= 0
            val (win, lose) = if (cNewer) c to prev else prev to c
            log("睡眠去重：${span(lose)} 与 ${span(win)} 重叠，保留 time=${win.item.time}，丢弃 time=${lose.item.time}")
            kept[kept.lastIndex] = win
        }

        val out = ArrayList<Record>()
        for (c in kept) {
            val item = c.item
            val v = item.value
            val startSec = c.startSec
            val endSec = c.endSec
            val off = offsetOf(item)
            val start = instant(startSec)
            val end = instant(endSec)
            val sleepId = "${item.sid ?: "unknown"}_${item.time ?: endSec}"

            val stages = ArrayList<SleepSessionRecord.Stage>()
            v.optJSONArray("items")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val seg = arr.optJSONObject(i) ?: continue
                    val s = maxOf(seg.optLongOrNull("start_time") ?: continue, startSec)
                    val e = minOf(seg.optLongOrNull("end_time") ?: continue, endSec)
                    if (e <= s) continue
                    stages += SleepSessionRecord.Stage(startTime = instant(s), endTime = instant(e), stage = sleepStage(seg.optLongOrNull("state")))
                }
            }
            // 去掉与前一段重叠的部分，Health Connect 不接受重叠分期
            stages.sortBy { it.startTime }
            val clean = ArrayList<SleepSessionRecord.Stage>()
            for (st in stages) {
                val prevEnd = clean.lastOrNull()?.endTime
                val s = if (prevEnd != null && st.startTime.isBefore(prevEnd)) prevEnd else st.startTime
                if (st.endTime.isAfter(s)) clean += SleepSessionRecord.Stage(startTime = s, endTime = st.endTime, stage = st.stage)
            }

            val isNap = v.optBoolean("is_nap", false)
            val score = v.optLongOrNull("score") ?: v.optLongOrNull("sleep_score")
            out += SleepSessionRecord(
                startTime = start, startZoneOffset = off,
                endTime = end, endZoneOffset = off,
                title = if (isNap) "小睡" else "睡眠",
                notes = score?.let { "小米睡眠评分 $it" },
                stages = clean,
                metadata = meta("mi_sleep_$sleepId"),
            )

            val breath = v.optDoubleOrNull("avg_breath")
            if (breath != null && breath in 1.0..100.0) {
                out += RespiratoryRateRecord(time = end, zoneOffset = off, rate = breath, metadata = meta("mi_resp_$sleepId"))
            }
        }
        return out
    }

    /**
     * 每条云端睡眠条目的诊断信息：(签名, 日志行)。签名不变说明内容没变，调用方据此避免重复输出。
     * 用于核对：分期编号 → 分钟数 是否与云端 sleep_*_duration 一致，以及同一晚是否出现多条。
     */
    fun sleepDiagnostics(items: List<MiItem>): List<Pair<String, String>> = items.map { item ->
        val v = item.value
        val bed = v.firstLong("bedtime", "device_bedtime", "bed_timestamp")
        val wake = v.firstLong("wake_up_time", "device_wake_up_time", "out_bed_timestamp")
        val perState = sortedMapOf<Long, Long>()
        v.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val seg = arr.optJSONObject(i) ?: continue
                val s = seg.optLongOrNull("start_time") ?: continue
                val e = seg.optLongOrNull("end_time") ?: continue
                if (e <= s) continue
                val state = seg.optLongOrNull("state") ?: -1L
                perState[state] = (perState[state] ?: 0L) + (e - s)
            }
        }
        val top = DIAG_FIELDS.mapNotNull { k -> v.optLongOrNull(k)?.let { "$k=$it" } }
        val stageText = perState.entries.joinToString(" ") { "s${it.key}=${it.value / 60}" }
        val off = offsetOf(item)
        val line = buildString {
            append("睡眠诊断 ").append(fmt(bed, off)).append(" → ").append(fmt(wake, off))
            if (bed != null && wake != null) append("（").append((wake - bed) / 60).append(" 分钟）")
            append(" | 云端: ").append(top.joinToString(" ").ifEmpty { "无时长字段" })
            append(" | 分期合计(分钟): ").append(stageText.ifEmpty { "无分期" })
            append(" | sid=").append(item.sid).append(" time=").append(item.time)
            append(" tz=").append(item.zoneOffsetSec)
            append(" nap=").append(v.opt("is_nap"))
            append(" | 字段: ").append(v.keys().asSequence().sorted().joinToString(","))
        }
        val sig = listOf(item.sid, item.time, bed, wake, top, stageText).joinToString("|")
        sig to line
    }

    private fun span(c: SleepCandidate): String {
        val off = offsetOf(c.item)
        return "${fmt(c.startSec, off)}→${fmt(c.endSec, off)}"
    }

    private fun fmt(sec: Long?, off: ZoneOffset): String =
        sec?.let { instant(it).atOffset(off).format(DIAG_TIME) } ?: "?"

    /** 小米分期编码（HealthMi 实测）：2 深睡，3 浅睡，4 REM，5 清醒。 */
    private fun sleepStage(state: Long?): Int = when (state) {
        2L -> SleepSessionRecord.STAGE_TYPE_DEEP
        3L -> SleepSessionRecord.STAGE_TYPE_LIGHT
        4L -> SleepSessionRecord.STAGE_TYPE_REM
        5L -> SleepSessionRecord.STAGE_TYPE_AWAKE
        else -> SleepSessionRecord.STAGE_TYPE_SLEEPING
    }

    // ---------------- 体重（体脂秤） ----------------

    /**
     * 称重 → 体重 / 体脂率 / 体内水分 / 骨量 / 去脂体重 / 基础代谢。
     * ID 只按称重时刻生成，同一次称重无论来自米家还是小米运动健康都只写一份。
     */
    fun weighIns(list: List<ScaleWeighIn>): List<Record> {
        val out = ArrayList<Record>()
        for (w in list.distinctBy { it.timeSec }) {
            val t = w.timeSec
            val at = instant(t)
            val off = defaultOffset
            out += WeightRecord(time = at, zoneOffset = off, weight = Mass.kilograms(w.weightKg), metadata = meta("mi_weight_$t", scaleDevice))
            w.bodyFatPct?.takeIf { it <= 100 }?.let {
                out += BodyFatRecord(time = at, zoneOffset = off, percentage = Percentage(it), metadata = meta("mi_bodyfat_$t", scaleDevice))
            }
            val waterKg = w.bodyWaterKg ?: w.bodyWaterPct?.takeIf { it <= 100 }?.let { w.weightKg * it / 100 }
            waterKg?.takeIf { it < w.weightKg }?.let {
                out += BodyWaterMassRecord(time = at, zoneOffset = off, mass = Mass.kilograms(it), metadata = meta("mi_bodywater_$t", scaleDevice))
            }
            w.boneMassKg?.takeIf { it < w.weightKg }?.let {
                out += BoneMassRecord(time = at, zoneOffset = off, mass = Mass.kilograms(it), metadata = meta("mi_bone_$t", scaleDevice))
            }
            w.leanMassKg?.takeIf { it < w.weightKg }?.let {
                out += LeanBodyMassRecord(time = at, zoneOffset = off, mass = Mass.kilograms(it), metadata = meta("mi_lean_$t", scaleDevice))
            }
            w.bmrKcal?.takeIf { it < 10_000 }?.let {
                out += BasalMetabolicRateRecord(time = at, zoneOffset = off, basalMetabolicRate = Power.kilocaloriesPerDay(it), metadata = meta("mi_bmr_$t", scaleDevice))
            }
        }
        return out
    }

    // ---------------- 运动记录 ----------------

    /** 只写运动会话本身；距离/热量已包含在日汇总里，避免重复计算。 */
    fun workouts(items: List<MiItem>): List<Record> = items.mapNotNull { item ->
        val v = item.value
        val startSec = v.optLongOrNull("start_time") ?: item.time ?: return@mapNotNull null
        val endSec = v.optLongOrNull("end_time") ?: (startSec + (v.optLongOrNull("duration") ?: 0))
        if (endSec <= startSec) return@mapNotNull null
        val off = offsetOf(item)
        val label = item.category ?: item.key ?: v.optString("sport_type").ifEmpty { "workout" }
        val notes = buildList {
            v.optDoubleOrNull("distance")?.takeIf { it > 0 }?.let { add("距离 ${it.toLong()} m") }
            (v.optDoubleOrNull("calories") ?: v.optDoubleOrNull("total_cal"))?.takeIf { it > 0 }?.let { add("热量 ${it.toLong()} kcal") }
            v.optLongOrNull("avg_hrm")?.takeIf { it > 0 }?.let { add("平均心率 $it") }
            v.optLongOrNull("max_hrm")?.takeIf { it > 0 }?.let { add("最大心率 $it") }
        }.joinToString("，").ifEmpty { null }
        ExerciseSessionRecord(
            startTime = instant(startSec), startZoneOffset = off,
            endTime = instant(endSec), endZoneOffset = off,
            exerciseType = exerciseType(label),
            title = label,
            notes = notes,
            metadata = meta("mi_workout_${item.sid ?: "unknown"}_${item.key ?: "w"}_${item.time ?: startSec}"),
        )
    }

    companion object {
        private val DIAG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        private val DIAG_FIELDS = listOf(
            "duration", "sleep_deep_duration", "sleep_light_duration", "sleep_rem_duration",
            "sleep_awake_duration", "awake_duration", "score", "sleep_score",
        )

        fun exerciseType(raw: String): Int {
            val t = raw.lowercase()
            fun has(vararg k: String) = k.any { t.contains(it) }
            val indoor = has("indoor", "室内", "treadmill", "跑步机")
            return when {
                has("跑", "run", "jog") -> if (indoor) ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL else ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
                has("步行", "健走", "walk") -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
                has("骑", "cycl", "bike", "riding", "ride") -> if (indoor) ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY else ExerciseSessionRecord.EXERCISE_TYPE_BIKING
                has("泳", "swim") -> if (has("open", "开放")) ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER else ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
                has("hiit", "interval") -> ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
                has("瑜伽", "yoga") -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
                has("椭圆", "elliptical") -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
                has("划船", "rowing") -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE
                has("力量", "器械", "strength", "weight", "gym") -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
                has("羽毛球", "badminton") -> ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON
                has("篮球", "basketball") -> ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL
                has("足球", "soccer", "football") -> ExerciseSessionRecord.EXERCISE_TYPE_SOCCER
                has("乒乓", "table_tennis", "table tennis", "pingpong") -> ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS
                has("网球", "tennis") -> ExerciseSessionRecord.EXERCISE_TYPE_TENNIS
                has("登山", "徒步", "hik", "climb", "mountain") -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
                else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
            }
        }
    }
}
