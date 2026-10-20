package com.wanderwildwood.hitome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonPhaseTest {

    @Test
    fun terminatorRunsFromNewThroughQuarterToFull() {
        assertEquals(1f, terminatorK(0f), 0.001f)
        assertEquals(0f, terminatorK(0.5f), 0.001f)
        assertEquals(-1f, terminatorK(1f), 0.001f)
    }

    @Test
    fun waxingLightsTheRightAndWaningTheLeft() {
        assertFalse(moonLit(0.9f, 0f, 0f, waxing = true))
        assertTrue(moonLit(0.9f, 0f, 1f, waxing = true))
        assertTrue(moonLit(0.4f, 0f, 0.5f, waxing = true))
        assertFalse(moonLit(-0.4f, 0f, 0.5f, waxing = true))
        assertTrue(moonLit(-0.4f, 0f, 0.5f, waxing = false))
        assertFalse(moonLit(0.4f, 0f, 0.5f, waxing = false))
        // A waxing crescent is only the right-hand rim.
        assertTrue(moonLit(0.95f, 0f, 0.1f, waxing = true))
        assertFalse(moonLit(0.2f, 0f, 0.1f, waxing = true))
        // A waxing gibbous leaves the left rim dark.
        assertTrue(moonLit(0f, 0f, 0.75f, waxing = true))
        assertFalse(moonLit(-0.9f, 0f, 0.75f, waxing = true))
    }

    @Test
    fun glyphSitsJustPastTheNumerals() {
        val placed = placeGlyph(
            timeLeft = 100,
            timeTop = 80,
            timeRight = 400,
            timeBottom = 280,
            textChars = 5,
            gap = 8,
            screenWidth = 1080,
        )
        assertEquals(408, placed!!.left)
        assertEquals(200, placed.size)
        assertTrue(placed.left >= 400)
        assertEquals(80, placed.top)
    }

    @Test
    fun aWideClockViewDoesNotHideCenteredDigits() {
        // The view is the whole screen; "10:48" is centered in it.
        val placed = placeGlyph(
            timeLeft = 0,
            timeTop = 100,
            timeRight = 1000,
            timeBottom = 300,
            textChars = 5,
            gap = 8,
            screenWidth = 1000,
        )
        val estimated = (200 * 0.62f * 5).toInt()
        val textRight = 500 + estimated / 2
        assertTrue(placed!!.left >= textRight)
    }

    @Test
    fun noRoomBesideTheTimeLeavesTheGlyphOut() {
        assertNull(
            placeGlyph(
                timeLeft = 900,
                timeTop = 100,
                timeRight = 1080,
                timeBottom = 300,
                textChars = 5,
                gap = 8,
                screenWidth = 1080,
            ),
        )
    }
}
