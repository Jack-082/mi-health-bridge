package io.github.mihealthbridge.mi

/**
 * 如一智能体脂秤 MJH-05（wole.scales.mjh05）的实时数据解码。
 *
 * 米家登录后，秤约每秒在 mi.miot.ble 的 0x102 通道上推送一组两条加密通知，
 * 解密后分别为 14 字节和 8 字节。两者拼成 22 字节，第 i 字节再异或 (0x30 + i) 得到：
 *
 *   [0..5]  固定头（含义未知）
 *   [6]     0/1 交替（帧序）
 *   [7]     单位：1 = 斤（实测）；0 推测为公斤
 *   [8..9]  体重 ×10，大端，按 [7] 的单位
 *   [14]    0x08（消息类型？）
 *   [15..16] 阻抗（Ω，大端；体重稳定后才出现，光脚时）
 *   [21]    校验（算法未知，不校验）
 *
 * 以上布局来自真实称重抓包（163.8 斤 → 0x0666），只在这一台秤上验证过。
 */
object MjhScale {

    data class Reading(
        /** 体重原始值（×10，单位见 [unit]）。 */
        val raw: Int,
        val unit: Int,
        /** 阻抗 Ω；0 表示尚未测出。 */
        val impedance: Int,
    ) {
        /** 换算成公斤；单位未知时为 null。 */
        val kg: Double? get() = when (unit) {
            1 -> raw / 20.0   // 斤
            0 -> raw / 10.0   // 公斤（推测）
            else -> null
        }
    }

    fun deobfuscate(data: ByteArray): ByteArray =
        ByteArray(data.size) { i -> (data[i].toInt() xor (0x30 + i)).toByte() }

    /** [first] 为 14 字节那条，[second] 为 8 字节那条（均为解密后的明文）。 */
    fun decode(first: ByteArray, second: ByteArray): Reading? {
        if (first.size != 14 || second.size != 8) return null
        val m = deobfuscate(first + second)
        fun u8(i: Int) = m[i].toInt() and 0xFF
        return Reading(
            raw = (u8(8) shl 8) or u8(9),
            unit = u8(7),
            impedance = (u8(15) shl 8) or u8(16),
        )
    }

    /**
     * 从一次称重的读数序列里挑出最终结果：同一体重连续出现至少 [stableCount] 次视为稳定，
     * 取最后一个稳定值（阻抗取同体重读数里最后一个非 0 值）。
     */
    fun settle(readings: List<Reading>, stableCount: Int = 3): Reading? {
        var best: Reading? = null
        var run = 0
        var prev: Int? = null
        for (r in readings) {
            if (r.raw <= 0) { run = 0; prev = null; continue }
            run = if (r.raw == prev) run + 1 else 1
            prev = r.raw
            if (run >= stableCount) best = r
        }
        val w = best ?: return null
        val imp = readings.lastOrNull { it.raw == w.raw && it.impedance > 0 }?.impedance ?: 0
        return w.copy(impedance = imp)
    }
}
