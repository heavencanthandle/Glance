package com.wanderwildwood.hitome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlanceOrderTest {

    @Test
    fun newProviderGoesAtTheEnd() {
        val saved = Glance.DEFAULT_ORDER.joinToString(",")
        val merged = Glance.combine(saved, null, listOf("com.example.almanac"))
        assertEquals(Glance.DEFAULT_ORDER + "com.example.almanac", merged)
    }

    @Test
    fun movedProviderStaysWhereItWasPut() {
        val saved = (listOf("com.example.almanac") + Glance.DEFAULT_ORDER).joinToString(",")
        val merged = Glance.combine(saved, null, listOf("com.example.almanac"))
        assertEquals("com.example.almanac", merged.first())
    }

    @Test
    fun unknownNamesAreDropped() {
        val merged = Glance.combine("nope,${Glance.SKY}", null, emptyList())
        assertEquals(Glance.SKY, merged.first())
        assertFalse(merged.contains("nope"))
        assertEquals(Glance.DEFAULT_ORDER.size, merged.size)
    }

    @Test
    fun uninstalledProviderLeavesTheOrder() {
        val saved = (Glance.DEFAULT_ORDER + "com.example.almanac").joinToString(",")
        val merged = Glance.combine(saved, null, emptyList())
        assertFalse(merged.contains("com.example.almanac"))
    }

    @Test
    fun builtInPassedAsExtraIsNotListedTwice() {
        val merged = Glance.combine(null, null, listOf(Glance.SKY))
        assertEquals(1, merged.count { it == Glance.SKY })
    }

    @Test
    fun authorityIsThePackageGlanceProvider() {
        assertTrue(Glance.authorityMatches("com.example.app.glance", "com.example.app"))
        assertTrue(Glance.authorityMatches("other;com.example.app.glance", "com.example.app"))
        assertFalse(Glance.authorityMatches("com.example.app.glance.lines", "com.example.app"))
        assertFalse(Glance.authorityMatches(null, "com.example.app"))
    }
}

class ClockRoomTest {

    @Test
    fun systemClockLeavesThePreviewRoomAlone() {
        assertEquals(380, Clock.previewRoom(Clock.Size.SYSTEM, 380))
        assertEquals(0, Clock.heightDp(Clock.Size.SYSTEM))
    }

    @Test
    fun aLargerClockLeavesLessRoomForLines() {
        val small = Clock.previewRoom(Clock.Size.SMALL, 380)
        val larger = Clock.previewRoom(Clock.Size.LARGER, 380)
        assertTrue(larger < small)
        assertTrue(small < 380)
    }
}
