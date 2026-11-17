package app.orchard.tv.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceStatsTest {
    @Test
    fun capacityUsageMeasuresUsedSpace() {
        assertEquals(17, usedCapacityPercent(100, 83))
        assertEquals(76, usedCapacityPercent(1925, 463))
        assertEquals(0, usedCapacityPercent(100, 100))
        assertEquals(100, usedCapacityPercent(100, 0))
    }

    @Test
    fun invalidCapacityHasNoPercentage() {
        assertNull(usedCapacityPercent(0, 0))
        assertNull(usedCapacityPercent(100, 101))
        assertNull(usedCapacityPercent(100, -1))
    }

    @Test
    fun procStatCountsGuestTicksOnceAndTreatsIoWaitAsIdle() {
        val before = requireNotNull(CpuCounter.parseProcStat("cpu 100 2 30 400 20 1 2 3 5 0"))
        val after = requireNotNull(CpuCounter.parseProcStat("cpu 120 2 30 470 25 1 2 3 10 0"))
        assertEquals(21, after.percentSince(before))
        assertNull(CpuCounter.parseProcStat("cpu0 120 2 30 470 25"))
    }

    @Test
    fun uptimeUsesTotalCapacityOfAllOnlineCores() {
        val before = requireNotNull(CpuCounter.parseUptime("10.00 32.00", 4))
        val after = requireNotNull(CpuCounter.parseUptime("12.00 37.00", 4))
        assertEquals(38, after.percentSince(before))
        assertNull(requireNotNull(CpuCounter.parseUptime("12.00 37.00", 3)).percentSince(before))
        assertNull(CpuCounter.parseUptime("12.00 0.00", 4))
    }

    @Test
    fun resetOrDuplicateCountersDoNotEmitAPercentage() {
        val before = CpuCounter(20.0, 10.0)
        assertNull(before.percentSince(before))
        assertNull(CpuCounter(21.0, 1.0).percentSince(before))
    }
}
