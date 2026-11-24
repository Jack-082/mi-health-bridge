package io.github.mihealthbridge.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncReportTest {
    @Test fun jsonRoundTrip() {
        val report = SyncReport(
            atMillis = 1_782_000_000_000L, windowStartSec = 1_781_900_000L, windowEndSec = 1_782_003_600L,
            items = listOf(
                SyncReport.TypeResult("步数", SyncReport.State.Synced, written = 2),
                SyncReport.TypeResult("体重", SyncReport.State.NoData),
                SyncReport.TypeResult("睡眠", SyncReport.State.Failed, reason = "拉取失败：timeout"),
                SyncReport.TypeResult("心率", SyncReport.State.Partial, written = 20, failed = 1, reason = "写入失败 1 条"),
                SyncReport.TypeResult("血氧", SyncReport.State.Unauthorized, reason = "未授予写入权限"),
            ),
        )
        assertEquals(report, SyncReport.fromJson(report.toJson()))
    }

    @Test fun badJsonGivesNull() {
        assertNull(SyncReport.fromJson(""))
        assertNull(SyncReport.fromJson("{\"at\":1}"))
    }
}
