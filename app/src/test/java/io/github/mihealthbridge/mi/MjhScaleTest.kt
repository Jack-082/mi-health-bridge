package io.github.mihealthbridge.mi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 数据取自真实抓包：2026-11-24 两次称重，秤上显示 163.8 斤。 */
class MjhScaleTest {
    private fun h(s: String) = MiBeacon.unhex(s)

    @Test fun stableWeightAndImpedance() {
        val r = MjhScale.decode(h("caaca55a160f37363e5f3a3b3c3d"), h("363d1e4142434478"))!!
        assertEquals(1638, r.raw)
        assertEquals(1, r.unit)
        assertEquals(81.9, r.kg!!, 1e-9)
        assertEquals(606, r.impedance)
    }

    @Test fun settlingFrameWithoutImpedance() {
        val r = MjhScale.decode(h("caaca55a160f36363e543a3b3c3d"), h("363f404142434477"))!!
        assertEquals(0x066d, r.raw) // 164.5 斤，读数还在跳
        assertEquals(0, r.impedance)
    }

    @Test fun settlePicksRepeatedValue() {
        fun r(raw: Int, imp: Int = 0) = MjhScale.Reading(raw, 1, imp)
        val s = MjhScale.settle(listOf(r(0), r(1645), r(1641), r(1638), r(1638), r(1638, 606), r(1638, 606)))!!
        assertEquals(1638, s.raw)
        assertEquals(606, s.impedance)
        assertNull(MjhScale.settle(listOf(r(1645), r(1641), r(1638))))
    }

    @Test fun rejectsWrongSizes() {
        assertNull(MjhScale.decode(h("00"), h("363d1e4142434478")))
    }
}
