package com.wanderwildwood.hitome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadPacingTest {

    private val sources = listOf("com.wanderwildwood.kotozute", "com.heavencanthandle.almanac")
    private val minute = 60_000L

    @Test
    fun theFirstWakeAlwaysReads() {
        assertTrue(ReadPacing.wakeReads(now = 5 * minute, lastRead = 0L, lastSources = null, sources = sources))
    }

    @Test
    fun aWakeSoonAfterAReadDoesNotAskTheAppsAgain() {
        assertFalse(ReadPacing.wakeReads(now = 14 * minute, lastRead = 1, lastSources = sources, sources = sources))
    }

    @Test
    fun aWakeFifteenMinutesOnReadsAgain() {
        assertTrue(ReadPacing.wakeReads(now = 15 * minute + 1, lastRead = 1, lastSources = sources, sources = sources))
    }

    @Test
    fun choosingDifferentAppsReadsAtOnce() {
        val more = sources + "com.wanderwildwood.jimeikin"
        assertTrue(ReadPacing.wakeReads(now = 2 * minute, lastRead = 1, lastSources = sources, sources = more))
    }

    @Test
    fun theMoonIsAskedOnTheHalfHour() {
        assertEquals(30 * minute, ReadPacing.untilNextMoon(0))
        assertEquals(10 * minute, ReadPacing.untilNextMoon(20 * minute))
        assertEquals(1_000L, ReadPacing.untilNextMoon(30 * minute - 10))
    }
}
