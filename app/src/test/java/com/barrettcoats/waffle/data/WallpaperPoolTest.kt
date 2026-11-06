package com.barrettcoats.waffle.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class WallpaperPoolTest {
    private val a = File("a.jpg")
    private val b = File("b.jpg")
    private val c = File("c.jpg")
    private val files = listOf(a, b, c)

    private fun times(vararg p: Pair<File, Set<DayPart>>) = p.associate { (f, parts) -> f.name to PhotoSchedule(parts) }

    @Test fun nothingSetShowsEverything() {
        assertEquals(files, wallpaperPool(files, emptyMap(), DayPart.NIGHT))
    }

    @Test fun onlyTheMatchingPhotosShow() {
        val t = times(a to setOf(DayPart.NIGHT), b to setOf(DayPart.EVENING, DayPart.NIGHT))
        assertEquals(listOf(a, b), wallpaperPool(files, t, DayPart.NIGHT))
        assertEquals(listOf(b), wallpaperPool(files, t, DayPart.EVENING))
    }

    @Test fun fallsBackToAnyTimePhotos() {
        val t = times(a to setOf(DayPart.NIGHT))
        assertEquals(listOf(b, c), wallpaperPool(files, t, DayPart.DAY))
    }

    @Test fun fallsBackToAllWhenEveryPhotoIsForAnotherTime() {
        val t = times(a to setOf(DayPart.NIGHT), b to setOf(DayPart.NIGHT), c to setOf(DayPart.MORNING))
        assertEquals(files, wallpaperPool(files, t, DayPart.DAY))
    }

    @Test fun emptyFolder() {
        assertEquals(emptyList<File>(), wallpaperPool(emptyList(), emptyMap(), DayPart.DAY))
    }

    @Test fun emptyPartsMeanAnyTime() {
        val t = mapOf("a.jpg" to PhotoSchedule(emptySet()))
        assertEquals(files, wallpaperPool(files, t, DayPart.MORNING))
        assertEquals("Any time", t["a.jpg"].describe())
        assertEquals("Evening · Night", PhotoSchedule(setOf(DayPart.NIGHT, DayPart.EVENING)).describe())
        assertEquals(emptyMap<String, PhotoSchedule>(), t.withParts("a.jpg", emptySet()))
    }
}
