package com.barrettcoats.waffle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ConfigMigrationTest {
    private fun v1(side: RowFill) = LauncherConfig(
        version = 1,
        lastRowFill = side,
        sections = listOf(Section(id = "fav"), Section(id = "apps"), Section(id = "games")),
    )

    @Test fun version1TopRowCentersAndOthersKeepTheOldSide() {
        val c = v1(RowFill.RIGHT).migrated(listOf(196, 150, null))
        assertEquals(CONFIG_VERSION, c.version)
        assertEquals(listOf(RowAlign.CENTER, RowAlign.RIGHT, RowAlign.RIGHT), c.sections.map { it.shortRows })
    }

    @Test fun version1TileSizesBecomeTheSameColumnCounts() {
        // The two default sizes: four big favorites, five apps; an empty section gets the default.
        val c = v1(RowFill.LEFT).migrated(listOf(196, 150, null))
        assertEquals(listOf(4, 5, Columns.DEFAULT), c.sections.map { it.columns })
        // Hand-resized tiles map to however many fit, within the allowed range.
        assertEquals(Columns.MAX, columnsFitting(40))
        assertEquals(Columns.MIN, columnsFitting(800))
        assertEquals(3, columnsFitting(270))
    }

    @Test fun outOfRangeColumnsAreRepaired() {
        val c = LauncherConfig(sections = listOf(Section(id = "a", columns = 0), Section(id = "b", columns = 3)))
        assertEquals(listOf(4, 3), c.withValidColumns { listOf(196, 150) }.sections.map { it.columns })
        assertEquals(listOf(Columns.DEFAULT, 3), c.withValidColumns { emptyList() }.sections.map { it.columns })
    }

    @Test fun currentVersionIsLeftAlone() {
        val c = LauncherConfig(sections = listOf(Section(id = "a", shortRows = RowAlign.RIGHT)))
        assertSame(c, c.migrated())
    }

    @Test fun version1FileOnDiskIsMigratedOnLoad() {
        val dir = createTempDirectory().toFile()
        val file = File(dir, "layout.json")
        file.writeText(
            """
            {"version": 1, "lastRowFill": "LEFT", "sections": [
              {"id": "fav", "tiles": [{"id": "a", "packageName": "a", "widthDp": 196, "heightDp": 110}]},
              {"id": "apps", "tiles": [{"id": "b", "packageName": "b", "widthDp": 150, "heightDp": 84}]}
            ]}
            """.trimIndent(),
        )
        val c = ConfigStore(file).load()!!
        assertEquals(listOf(RowAlign.CENTER, RowAlign.LEFT), c.sections.map { it.shortRows })
        assertEquals(listOf(4, 5), c.sections.map { it.columns })
        assertEquals("a", c.sections[0].tiles.single().packageName)
        dir.deleteRecursively()
    }
}
