package com.barrettcoats.waffle.data

import com.barrettcoats.waffle.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

/** How many tiles a section's rows hold. Tiles are sized (16:9) to fill exactly that many. */
object Columns {
    const val MIN = 2
    const val MAX = 8
    /** Big tiles for the favorites row. */
    const val FAVORITES = 4
    /** Everything else. */
    const val DEFAULT = 5
}

@Serializable
data class Tile(
    val id: String = newId(),
    val packageName: String,
)

@Serializable
data class Section(
    val id: String = newId(),
    val title: String = "",
    val tiles: List<Tile> = emptyList(),
    /** Tiles per row; every tile in the section is sized so this many fill the row. */
    val columns: Int = Columns.DEFAULT,
    /** Where a row with fewer tiles than columns sits. */
    val shortRows: RowAlign = RowAlign.LEFT,
)

@Serializable
data class LauncherConfig(
    val version: Int = CONFIG_VERSION,
    val sections: List<Section> = emptyList(),
    /** 0 = square corners. */
    val cornerRadiusDp: Int = 16,
    /** How long each wallpaper stays up before crossfading to the next one; 0 = never change. */
    val wallpaperIntervalSeconds: Int = 600,
    /** Pick wallpapers in random order instead of by file name. */
    val wallpaperShuffle: Boolean = false,
    /** App names under every tile (icon-only tiles always show theirs). */
    val showAppNames: Boolean = false,
    /** Section names are always shown while editing; this controls the normal home screen. */
    val showSectionTitles: Boolean = false,
    /** Where the weather is for; null until chosen in Settings → Weather. */
    val weatherPlace: WeatherPlace? = null,
    val temperatureUnit: TemperatureUnit = TemperatureUnit.AUTO,
    /** Off hides weather entirely, including the "set up" button. */
    val weatherEnabled: Boolean = true,
    /** Put newly installed TV apps at the end of the last section. */
    val autoAddNewApps: Boolean = true,
    /**
     * Every package that was installed last time we looked, so a new install can be told apart from
     * an app the user removed from the home screen. Null until the first app scan.
     */
    val knownPackages: Set<String>? = null,
    /** "Not now" was chosen on the make-this-your-home-screen prompt; don't ask again on start. */
    val homePromptDismissed: Boolean = false,
    /** The "hold OK for options" tip has been acted on and no longer shows. */
    val tipSeen: Boolean = false,
    /**
     * When each wallpaper photo shows, keyed by file name. A photo that isn't listed (or has no
     * parts) shows at any time.
     */
    val photoTimes: Map<String, PhotoSchedule> = emptyMap(),
    /** Version 1's global short-row side. Only read to migrate it to [Section.shortRows]. */
    val lastRowFill: RowFill = RowFill.LEFT,
)

/** When one wallpaper photo shows. Room to add dates (holidays, birthdays) later. */
@Serializable
data class PhotoSchedule(
    /** Empty = any time. */
    val parts: Set<DayPart> = emptySet(),
)

@Serializable
enum class RowFill { LEFT, RIGHT }

@Serializable
enum class RowAlign { LEFT, CENTER, RIGHT }

/** 2 replaced per-tile sizes with per-section [Section.columns], and added [Section.shortRows]. */
const val CONFIG_VERSION = 2

/**
 * Brings an older layout up to [CONFIG_VERSION], so the home screen looks the same as before.
 * [tileWidths] is the width (dp) of each version-1 section's first tile, or null if it had none.
 *
 * Columns: as many of that width as fit across a 1080p screen (what version 1 showed). Short rows:
 * version 1 had one side for every section, but a section that fit on one row was always
 * centered, so the top section gets Center and the rest get the old side.
 */
fun LauncherConfig.migrated(tileWidths: List<Int?> = emptyList()): LauncherConfig {
    if (version >= CONFIG_VERSION) return this
    val oldSide = if (lastRowFill == RowFill.RIGHT) RowAlign.RIGHT else RowAlign.LEFT
    return copy(
        version = CONFIG_VERSION,
        sections = sections.mapIndexed { i, s ->
            val width = tileWidths.getOrNull(i)
            s.copy(
                columns = if (width == null) Columns.DEFAULT else columnsFitting(width),
                shortRows = if (i == 0) RowAlign.CENTER else oldSide,
            )
        },
    )
}

/**
 * Any column count outside [Columns.MIN]..[Columns.MAX] (a hand-edited file, or a test build's
 * "Auto" = 0) is replaced the way [migrated] would, from the old tile widths when there are some.
 */
fun LauncherConfig.withValidColumns(tileWidths: () -> List<Int?>): LauncherConfig {
    if (sections.all { it.columns in Columns.MIN..Columns.MAX }) return this
    val widths = tileWidths()
    return copy(
        sections = sections.mapIndexed { i, s ->
            if (s.columns in Columns.MIN..Columns.MAX) s
            else s.copy(columns = widths.getOrNull(i)?.let(::columnsFitting) ?: Columns.DEFAULT)
        },
    )
}

/** Version 1 geometry: a 960dp-wide screen, 48dp side margins, 22dp between tiles. */
internal fun columnsFitting(tileWidthDp: Int): Int =
    ((864 + 22) / (tileWidthDp + 22)).coerceIn(Columns.MIN, Columns.MAX)

fun newId(): String = UUID.randomUUID().toString()

/** Well-known TV apps that make good first favorites, in order of preference. */
private val LIKELY_FAVORITES = listOf(
    "com.netflix.ninja",
    "com.google.android.youtube.tv",
    "org.smarttube.stable",
    "com.teamsmart.videomanager.tv",
    "com.plexapp.android",
    "org.jellyfin.androidtv",
    "com.disney.disneyplus",
    "com.amazon.amazonvideo.livingroom",
    "com.wbd.stream",
    "org.xbmc.kodi",
)

/** First run: a row of up to four big favorites, then every other TV app as smaller tiles. */
fun defaultConfig(apps: Map<String, AppInfo>): LauncherConfig {
    val tvApps = apps.values.filter { it.isTv }.ifEmpty { apps.values.toList() }
    val known = LIKELY_FAVORITES.mapNotNull { pkg -> tvApps.firstOrNull { it.packageName == pkg } }
    val favorites = (known + tvApps).distinct().take(4)
    val rest = tvApps - favorites.toSet()
    return LauncherConfig(
        sections = listOf(
            Section(
                title = "Favorites",
                columns = Columns.FAVORITES,
                shortRows = RowAlign.CENTER,
                tiles = favorites.map { Tile(packageName = it.packageName) },
            ),
            Section(title = "Apps", tiles = rest.map { Tile(packageName = it.packageName) }),
        ),
    )
}

/** Reads and writes the layout as JSON in the app's files dir. */
class ConfigStore(private val file: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun load(): LauncherConfig? {
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            val config = json.decodeFromString<LauncherConfig>(text)
            val migrated = if (config.version >= CONFIG_VERSION) config else config.migrated(firstTileWidths(text))
            migrated.withValidColumns { firstTileWidths(text) }
        } catch (e: Exception) {
            // Keep the broken file for inspection instead of silently overwriting it.
            Log.e("Unreadable layout file, moved aside to ${file.name}.bad", e)
            file.renameTo(File(file.parentFile, file.name + ".bad"))
            null
        }
    }

    /** Version 1 stored a size on every tile; read the first tile's width in each section. */
    private fun firstTileWidths(text: String): List<Int?> =
        json.parseToJsonElement(text).jsonObject["sections"]?.jsonArray?.map { section ->
            section.jsonObject["tiles"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("widthDp")?.jsonPrimitive?.intOrNull
        } ?: emptyList()

    @Synchronized
    fun save(config: LauncherConfig) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(LauncherConfig.serializer(), config))
        if (!tmp.renameTo(file)) Log.e("Couldn't replace ${file.path}")
    }
}
