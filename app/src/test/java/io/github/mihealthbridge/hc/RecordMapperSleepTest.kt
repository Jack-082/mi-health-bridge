package io.github.mihealthbridge.hc

import androidx.health.connect.client.records.SleepSessionRecord
import io.github.mihealthbridge.mi.MiItem
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RecordMapperSleepTest {
    private val bed = 1_782_000_000L
    private val mapper = RecordMapper(nowMillis = (bed + 86_400) * 1000)

    private fun item(time: Long, bedtime: Long, wake: Long, stages: List<Triple<Long, Long, Long>> = emptyList()) = MiItem(
        time = time, zoneOffsetSec = 28_800, sid = "dev", key = "sleep", category = null,
        value = JSONObject()
            .put("bedtime", bedtime).put("wake_up_time", wake).put("duration", (wake - bedtime) / 60)
            .put("items", JSONArray().apply {
                stages.forEach { (s, e, st) -> put(JSONObject().put("start_time", s).put("end_time", e).put("state", st)) }
            }),
    )

    @Test fun overlappingItemsKeepNewest() {
        val logs = mutableListOf<String>()
        val partial = item(time = bed + 3 * 3600, bedtime = bed, wake = bed + 3 * 3600)
        val full = item(time = bed + 8 * 3600, bedtime = bed, wake = bed + 8 * 3600)
        val sessions = mapper.sleep(listOf(full, partial)) { logs += it }.filterIsInstance<SleepSessionRecord>()
        assertEquals(1, sessions.size)
        assertEquals(Instant.ofEpochSecond(bed + 8 * 3600), sessions[0].endTime)
        assertEquals(1, logs.size)
    }

    @Test fun separateNightsAreKept() {
        val logs = mutableListOf<String>()
        val night1 = item(time = bed + 8 * 3600, bedtime = bed, wake = bed + 8 * 3600)
        val night2 = item(time = bed + 32 * 3600, bedtime = bed + 24 * 3600, wake = bed + 32 * 3600)
        val sessions = mapper.sleep(listOf(night1, night2)) { logs += it }.filterIsInstance<SleepSessionRecord>()
        assertEquals(2, sessions.size)
        assertTrue(logs.isEmpty())
    }

    @Test fun diagnosticsSumStageMinutesPerState() {
        val night = item(
            time = bed + 7200, bedtime = bed, wake = bed + 7200,
            stages = listOf(Triple(bed, bed + 3600, 2L), Triple(bed + 3600, bed + 5400, 3L), Triple(bed + 5400, bed + 6000, 3L)),
        )
        val (sig, line) = mapper.sleepDiagnostics(listOf(night)).single()
        assertTrue(line, line.contains("s2=60 s3=40"))
        assertTrue(line, line.contains("duration=120"))
        assertEquals(sig, mapper.sleepDiagnostics(listOf(night)).single().first)
    }
}
