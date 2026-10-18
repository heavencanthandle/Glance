package com.wanderwildwood.hitome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanelForHomeTest {

    @Test
    fun countsTakeTheirOwnPackagesNotTheirPlace() {
        val sources = Glance.sources(listOf(Glance.UNREAD, Glance.SKY))
        assertEquals(Glance.COUNTS + Glance.SKY, sources)
        assertEquals(Glance.SOURCES.toSet(), Glance.sources(Glance.DEFAULT_ORDER).toSet())
    }

    @Test
    fun aSourceWithNoLinesHasOneRowForItsStatus() {
        val rows = PanelForHome.rowsFor(Glance.SKY, PanelForHome.Answer(PanelForHome.OFF_APP))
        assertEquals(1, rows.size)
        assertEquals(listOf(Glance.SKY, null, null, null, 0, PanelForHome.OFF_APP), rows[0].toList())
    }

    @Test
    fun theHeadingIsOnTheFirstRowOnly() {
        val section = Glance.Section(
            "com.wanderwildwood.kotozute",
            "Messages",
            listOf(Glance.Line("3", "unread", true), Glance.Line(null, "second", false)),
        )
        val rows = PanelForHome.rowsFor("com.wanderwildwood.kotozute", PanelForHome.Answer(PanelForHome.ON, section))
        assertEquals(2, rows.size)
        assertEquals(listOf("com.wanderwildwood.kotozute", "Messages", "3", "unread", 1, PanelForHome.ON), rows[0].toList())
        assertNull(rows[1][1])
        assertEquals("com.wanderwildwood.kotozute", rows[1][0])
    }
}
