package com.murtdah.fit.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvAndTargetsTest {
    @Test
    fun quotingRoundTrip() {
        val cells = listOf("Chicken, rice", "He said \"halal\"", "plain", "")
        val line = cells.joinToString(",") { Csv.escape(it) }
        assertEquals(cells, Csv.parseLine(line))
    }

    @Test
    fun sectionsParse() {
        val text = "#meals\nid,name,kcal\n1,\"Wrap, chicken\",650\n2,Yogurt,150\n#weights\nid,kg\n1,113.4\n"
        val s = Csv.sections(text)
        assertEquals(2, s.getValue("meals").size)
        assertEquals("Wrap, chicken", s.getValue("meals")[0]["name"])
        assertEquals("113.4", s.getValue("weights")[0]["kg"])
    }

    @Test
    fun calorieAndProteinFloorsCannotBeLowered() {
        val t = Targets(kcal = 1_500, proteinG = 100, proteinFloorG = 90).safe()
        assertEquals(2_000, t.kcal)
        assertEquals(150, t.proteinFloorG)
        assertEquals(150, t.proteinG)
    }

    @Test
    fun defaultsMatchBrief() {
        val t = Targets()
        assertEquals(listOf(2_100, 170, 150, 65, 210, 2_300, 30, 2_500, 8_000, 10_000),
            listOf(t.kcal, t.proteinG, t.proteinFloorG, t.fatG, t.carbsG, t.sodiumMaxMg, t.fiberG, t.waterMl, t.stepsMin, t.stepsTarget))
    }
}
