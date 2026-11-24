package io.github.mihealthbridge.sync

import org.json.JSONArray
import org.json.JSONObject

/** 最近一次同步中，每一类 Health Connect 数据的结果。 */
data class SyncReport(
    val atMillis: Long,
    /** 本次拉取的时间范围（秒）；整次同步没跑起来时为 0。 */
    val windowStartSec: Long,
    val windowEndSec: Long,
    val items: List<TypeResult>,
) {
    enum class State { Synced, Partial, NoData, Failed, Unauthorized }

    data class TypeResult(
        val label: String,
        val state: State,
        /** 成功写入（或覆盖更新）的条数。 */
        val written: Int = 0,
        /** 写入失败的条数。 */
        val failed: Int = 0,
        val reason: String? = null,
    )

    fun toJson(): String = JSONObject()
        .put("at", atMillis)
        .put("from", windowStartSec)
        .put("to", windowEndSec)
        .put("items", JSONArray().apply {
            items.forEach {
                put(
                    JSONObject()
                        .put("label", it.label).put("state", it.state.name)
                        .put("written", it.written).put("failed", it.failed)
                        .put("reason", it.reason ?: ""),
                )
            }
        })
        .toString()

    companion object {
        fun fromJson(text: String): SyncReport? = runCatching {
            val o = JSONObject(text)
            val arr = o.getJSONArray("items")
            SyncReport(
                atMillis = o.getLong("at"),
                windowStartSec = o.optLong("from"),
                windowEndSec = o.optLong("to"),
                items = (0 until arr.length()).map { i ->
                    val it = arr.getJSONObject(i)
                    TypeResult(
                        label = it.getString("label"),
                        state = State.valueOf(it.getString("state")),
                        written = it.optInt("written"),
                        failed = it.optInt("failed"),
                        reason = it.optString("reason").ifEmpty { null },
                    )
                },
            )
        }.getOrNull()
    }
}
