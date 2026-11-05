package com.barrettcoats.waffle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LayoutOpsTest {
    private fun tile(name: String) = Tile(id = name, packageName = name)

    /** Favorites (big): a b c d. Apps (small, 5 across): e f g h i / j k */
    private val config = LauncherConfig(
        sections = listOf(
            Section(id = "fav", tiles = listOf("a", "b", "c", "d").map { tile(it) }),
            Section(id = "apps", tiles = listOf("e", "f", "g", "h", "i", "j", "k").map { tile(it) }),
        ),
    )

    private fun LauncherConfig.names(section: Int) = sections[section].tiles.joinToString("") { it.packageName }

    @Test fun rightReordersWithinSection() {
        assertEquals("bacd", config.moveTile("a", MoveDirection.RIGHT, 4).names(0))
    }

    @Test fun rightAtEndCarriesIntoNextSectionStart() {
        val c = config.moveTile("d", MoveDirection.RIGHT, 4)
        assertEquals("abc", c.names(0))
        assertEquals("defghijk", c.names(1))
    }

    @Test fun leftAtStartCarriesIntoPreviousSectionEnd() {
        val c = config.moveTile("e", MoveDirection.LEFT, 5)
        assertEquals("abcde", c.names(0))
        assertEquals("fghijk", c.names(1))
    }

    @Test fun upMovesOneRowWithinSection() {
        // j is under e: moving it up puts it where e was.
        assertEquals("jefghik", config.moveTile("j", MoveDirection.UP, 5).names(1))
    }

    @Test fun upFromTopRowGoesToPreviousSection() {
        val c = config.moveTile("g", MoveDirection.UP, 5)
        assertEquals("abcdg", c.names(0))
        assertEquals("efhijk", c.names(1))
    }

    @Test fun downMovesOneRowWithinSection() {
        assertEquals("fghijek", config.moveTile("e", MoveDirection.DOWN, 5).names(1))
    }

    @Test fun downWithNothingBelowDropsToEndOfShortLastRow() {
        // h has no tile under it (the last row is j k): it goes to the end.
        assertEquals("efgijkh", config.moveTile("h", MoveDirection.DOWN, 5).names(1))
    }

    @Test fun downFromLastRowGoesToNextSectionSameColumn() {
        val c = config.moveTile("c", MoveDirection.DOWN, 4)
        assertEquals("abd", c.names(0))
        assertEquals("efcghijk", c.names(1))
    }

    @Test fun edgesAreNoOps() {
        assertSame(config, config.moveTile("a", MoveDirection.UP, 4))
        assertSame(config, config.moveTile("a", MoveDirection.LEFT, 4))
        assertSame(config, config.moveTile("k", MoveDirection.DOWN, 5))
        assertSame(config, config.moveTile("k", MoveDirection.RIGHT, 5))
    }

    @Test fun moveSectionSwapsAndClampsAtEnds() {
        assertEquals(listOf("apps", "fav"), config.moveSection("apps", -1).sections.map { it.id })
        assertSame(config, config.moveSection("fav", -1))
    }

    @Test fun removeTilesForDropsThoseAppsEverywhere() {
        val c = config.removeTilesFor(setOf("b", "k"))
        assertEquals("acd", c.names(0))
        assertEquals("efghij", c.names(1))
        assertSame(config, config.removeTilesFor(setOf("not-on-screen")))
        assertSame(config, config.removeTilesFor(emptySet()))
    }

    private fun apps(vararg names: String, phone: Set<String> = emptySet()) =
        names.associateWith { AppInfo(it, it, isTv = it !in phone, updated = 0) }

    @Test fun firstScanOnlyRecords() {
        val c = config.syncInstalled(apps("a", "z"))
        assertEquals(setOf("a", "z"), c.knownPackages)
        assertEquals(config.sections, c.sections)
    }

    @Test fun newTvAppGoesToEndOfLastSection() {
        val c = config.copy(knownPackages = setOf("a")).syncInstalled(apps("a", "new"))
        assertEquals("efghijknew", c.names(1))
    }

    @Test fun removedAppIsNotAddedBack() {
        // "x" is installed and known but not on screen (the user removed it).
        val c = config.copy(knownPackages = setOf("x")).syncInstalled(apps("x"))
        assertEquals(config.sections, c.sections)
    }

    @Test fun reinstalledAppCountsAsNew() {
        val uninstalled = config.copy(knownPackages = setOf("x")).syncInstalled(apps())
        val reinstalled = uninstalled.syncInstalled(apps("x"))
        assertEquals("efghijkx", reinstalled.names(1))
    }

    @Test fun phoneAppsAndAutoAddOffAreLeftAlone() {
        val base = config.copy(knownPackages = emptySet())
        assertEquals(config.sections, base.syncInstalled(apps("p", phone = setOf("p"))).sections)
        assertEquals(config.sections, base.copy(autoAddNewApps = false).syncInstalled(apps("n")).sections)
    }

    @Test fun unchangedInstallSetIsANoOp() {
        val c = config.copy(knownPackages = setOf("a"))
        assertSame(c, c.syncInstalled(apps("a")))
    }
}
