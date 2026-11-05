package com.barrettcoats.waffle.data

/**
 * Pure layout edits. Each returns a new config, or the same instance when nothing changes, so
 * callers can skip saving no-ops.
 */

enum class MoveDirection { LEFT, RIGHT, UP, DOWN }

fun LauncherConfig.mapSection(id: String, f: (Section) -> Section): LauncherConfig =
    copy(sections = sections.map { if (it.id == id) f(it) else it })

fun LauncherConfig.mapTile(tileId: String, f: (Tile) -> Tile): LauncherConfig =
    copy(sections = sections.map { s -> s.copy(tiles = s.tiles.map { if (it.id == tileId) f(it) else it }) })

/** Index of the section holding [tileId], or -1. */
fun LauncherConfig.sectionIndexOf(tileId: String): Int =
    sections.indexOfFirst { s -> s.tiles.any { it.id == tileId } }

/** Moves [tileId] into section [to] at [index] (clamped). Its size comes from the section's columns. */
fun LauncherConfig.moveTileToSection(tileId: String, to: Int, index: Int = Int.MAX_VALUE): LauncherConfig {
    val from = sectionIndexOf(tileId)
    if (from < 0 || to !in sections.indices) return this
    val tile = sections[from].tiles.first { it.id == tileId }
    val result = sections.toMutableList()
    result[from] = result[from].copy(tiles = result[from].tiles.filterNot { it.id == tileId })
    val dest = result[to]
    result[to] = dest.copy(tiles = dest.tiles.toMutableList().apply { add(index.coerceIn(0, size), tile) })
    return copy(sections = result)
}

/**
 * Moves a tile one step on screen. [columns] is how many tiles fit across its section, so up and
 * down move between rows and only cross into the neighbouring section at the top or bottom edge.
 * Left and right follow reading order, carrying on into the previous/next section at the ends.
 */
fun LauncherConfig.moveTile(tileId: String, direction: MoveDirection, columns: Int): LauncherConfig {
    val si = sectionIndexOf(tileId)
    if (si < 0) return this
    val tiles = sections[si].tiles
    val ti = tiles.indexOfFirst { it.id == tileId }
    val n = tiles.size
    val cols = columns.coerceAtLeast(1)
    val row = ti / cols
    val lastRow = (n - 1) / cols

    fun reorder(target: Int): LauncherConfig =
        if (target == ti) this
        else mapSection(sections[si].id) { s -> s.copy(tiles = s.tiles.toMutableList().apply { add(target, removeAt(ti)) }) }

    return when (direction) {
        MoveDirection.LEFT -> when {
            ti > 0 -> reorder(ti - 1)
            si > 0 -> moveTileToSection(tileId, si - 1)
            else -> this
        }
        MoveDirection.RIGHT -> when {
            ti < n - 1 -> reorder(ti + 1)
            si < sections.lastIndex -> moveTileToSection(tileId, si + 1, 0)
            else -> this
        }
        MoveDirection.UP -> when {
            row > 0 -> reorder(ti - cols)
            si > 0 -> moveTileToSection(tileId, si - 1)
            else -> this
        }
        MoveDirection.DOWN -> when {
            ti + cols < n -> reorder(ti + cols)
            row < lastRow -> reorder(n - 1) // nothing directly below: drop to the end of the short last row
            si < sections.lastIndex -> moveTileToSection(tileId, si + 1, ti % cols)
            else -> this
        }
    }
}

fun LauncherConfig.moveSection(sectionId: String, delta: Int): LauncherConfig {
    val from = sections.indexOfFirst { it.id == sectionId }
    val to = from + delta
    if (from < 0 || to !in sections.indices) return this
    return copy(sections = sections.toMutableList().apply { add(to, removeAt(from)) })
}

/** Takes the tiles for [packages] off the home screen (apps that were uninstalled). */
fun LauncherConfig.removeTilesFor(packages: Set<String>): LauncherConfig {
    if (packages.isEmpty() || sections.none { s -> s.tiles.any { it.packageName in packages } }) return this
    return copy(sections = sections.map { s -> s.copy(tiles = s.tiles.filterNot { it.packageName in packages }) })
}

/**
 * Reconciles the layout with what's installed now. TV apps that weren't installed last time are
 * appended to the last section (when [LauncherConfig.autoAddNewApps] is on). Apps the user removed
 * from the home screen stay off it, but an app that was uninstalled and later reinstalled counts
 * as new again. The very first scan only records what's there.
 */
fun LauncherConfig.syncInstalled(apps: Map<String, AppInfo>): LauncherConfig {
    val known = knownPackages ?: return copy(knownPackages = apps.keys)
    val onScreen = sections.flatMapTo(HashSet()) { s -> s.tiles.map { it.packageName } }
    val fresh = apps.values.filter { it.isTv && it.packageName !in known && it.packageName !in onScreen }
    var next = if (apps.keys == known) this else copy(knownPackages = apps.keys)
    val target = next.sections.lastOrNull()
    if (autoAddNewApps && target != null && fresh.isNotEmpty()) {
        next = next.mapSection(target.id) { s -> s.copy(tiles = s.tiles + fresh.map { Tile(packageName = it.packageName) }) }
    }
    return next
}
