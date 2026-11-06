package com.barrettcoats.waffle.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import kotlinx.coroutines.delay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.barrettcoats.waffle.LauncherViewModel
import com.barrettcoats.waffle.data.AppArt
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.barrettcoats.waffle.Log
import com.barrettcoats.waffle.data.AppInfo
import com.barrettcoats.waffle.data.TemperatureUnit
import com.barrettcoats.waffle.data.DayPart
import com.barrettcoats.waffle.data.WeatherPlace
import com.barrettcoats.waffle.data.describe
import com.barrettcoats.waffle.data.sunTimes
import com.barrettcoats.waffle.data.resolve
import com.barrettcoats.waffle.data.MoveDirection
import com.barrettcoats.waffle.data.RowAlign
import com.barrettcoats.waffle.data.Section
import com.barrettcoats.waffle.data.Tile
import com.barrettcoats.waffle.data.Columns

/** Side margins sized for TV overscan (5% of a 960×540 dp screen). */
private val SCREEN_PADDING_H = 48.dp
/** The header lines up with the left edge of a full row of tiles. */
private val HEADER_PADDING_H = 56.dp
private val TILE_GAP = 22.dp

/** The tile picked up to move with the D-pad. */
private data class ActiveEdit(val tileId: String)

private sealed interface Overlay {
    data object Settings : Overlay
    data class TileMenu(val sectionId: String, val tile: Tile) : Overlay
    data class SectionMenu(val section: Section) : Overlay
    data class ConfirmDeleteSection(val section: Section) : Overlay
    data class PickApp(val sectionId: String, val replaceTileId: String?) : Overlay
    /** [sectionId] null means a new section; [moveTileId] is moved into a new section once it's made. */
    data class NameSection(val sectionId: String?, val current: String, val moveTileId: String? = null) : Overlay
    data object WallpaperSettings : Overlay
    data object TimesOfDay : Overlay
    /** Every photo with when it shows; [focusName] is the photo to start on. */
    data class PhotoTimesGrid(val focusName: String? = null) : Overlay
    /** Morning / Day / Evening / Night checklist for one photo. */
    data class PhotoTimeChoice(val name: String) : Overlay
    data object Appearance : Overlay
    data object About : Overlay
    data object Diagnostics : Overlay
    data object AllApps : Overlay
    data object Forecast : Overlay
    data object WeatherSettings : Overlay
    data object WeatherSearch : Overlay
    /** Android is blocking our internet access; explain and offer the switch. */
    data object NetworkBlocked : Overlay
    data object PhotoUpload : Overlay
    data object MakeDefaultHome : Overlay
    data object ConfirmRemovePhotos : Overlay
    data object ScreensaverHelp : Overlay
    data class CrashReport(val text: String) : Overlay
    /** A list of options with the current one checked; Back returns to [parent]. */
    data class Choice(val title: String, val items: List<MenuItem>, val parent: Overlay) : Overlay
}

/** "It's evening in Portland · Sunrise 6:56 AM · Sunset 4:51 PM", or how the clock is used instead. */
private fun timesOfDaySubtitle(place: WeatherPlace?, now: DayPart, context: android.content.Context): String {
    val it = "It's ${now.label.lowercase()}"
    if (place == null) {
        return "$it by the clock (morning 6–10, day 10–5, evening 5–7, night). " +
            "Set your location to follow sunrise and sunset."
    }
    val day = sunTimes(place, java.time.LocalDate.now())
    val format = android.text.format.DateFormat.getTimeFormat(context)
    val sun = when {
        day.sunrise != null && day.sunset != null ->
            "Sunrise ${format.format(java.util.Date(day.sunrise))}  ·  Sunset ${format.format(java.util.Date(day.sunset))}"
        day.alwaysUp -> "The sun stays up all day today"
        else -> "The sun stays down all day today"
    }
    return "$it in ${place.name}  ·  $sun"
}

private val CORNER_CHOICES = listOf(
    "Square" to 0,
    "Subtle" to 6,
    "Slight" to 10,
    "Rounded" to 16,
    "Extra Round" to 28,
)
private val COLUMN_CHOICES = Columns.MIN..Columns.MAX
private fun alignName(a: RowAlign) = when (a) {
    RowAlign.LEFT -> "Left"
    RowAlign.CENTER -> "Center"
    RowAlign.RIGHT -> "Right"
}
private val INTERVAL_CHOICES = listOf(
    "5 minutes" to 300,
    "10 minutes" to 600,
    "30 minutes" to 1800,
    "1 hour" to 3600,
    "Never" to 0,
)

/** Text drawn straight on the wallpaper always gets the soft shadow. */
@Composable
private fun WallText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 16.sp,
    fontWeight: FontWeight = FontWeight.Medium,
    color: Color = Palette.OnWall,
    textAlign: TextAlign? = null,
) {
    Text(
        text,
        modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = Inter, shadow = WallShadow),
    )
}

@Composable
fun LauncherScreen(vm: LauncherViewModel) {
    val ready by vm.ready.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val wallpaper by vm.wallpaper.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val upload by vm.upload.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var editing by rememberSaveable { mutableStateOf(false) }
    var active by remember { mutableStateOf<ActiveEdit?>(null) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    // A tile that should take focus as soon as it's composed (after launch, a move, an add…).
    var focusTileId by remember { mutableStateOf<String?>(null) }
    // Same idea for a section header in edit mode (after moving a section).
    var focusSectionId by remember { mutableStateOf<String?>(null) }
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    var lastSearch by rememberSaveable { mutableStateOf("") }
    // Bumped on every place search, so only the newest search's answer opens the list.
    var placeSearchId by remember { mutableStateOf(0) }
    // After choosing a town the dialogs close; put focus back on the weather so the remote isn't "dead".
    val weatherFocus = remember { FocusRequester() }
    var focusWeather by remember { mutableStateOf(false) }
    LaunchedEffect(focusWeather) {
        if (focusWeather) {
            withFrameNanos { }
            runCatching { weatherFocus.requestFocus() }
            focusWeather = false
        }
    }
    val scroll = rememberScrollState()

    fun tipDone() {
        if (!vm.config.value.tipSeen) vm.updateSettings { it.copy(tipSeen = true) }
    }

    fun firstTileId() = vm.config.value.sections
        .flatMap { it.tiles }
        .firstOrNull { it.packageName in vm.installed.value }
        ?.id

    // Pressing Home while already home: leave any mode and go back to the first tile.
    LaunchedEffect(Unit) {
        vm.homePressed.collect {
            active = null
            overlay = null
            editing = false
            scroll.animateScrollTo(0)
            focusTileId = firstTileId()
        }
    }

    // Android's "make this your home app?" confirmation; re-check afterwards.
    var isHome by remember { mutableStateOf(true) }
    var homeLabel by remember { mutableStateOf("") }
    fun refreshHome() {
        isHome = vm.isDefaultHome()
        homeLabel = vm.homeAppLabel()
    }
    val homeRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshHome()
        Log.i("Home app request finished; default home now ${if (isHome) "this launcher" else vm.homeAppPackage()}")
    }
    fun launchHomeIntent(intent: android.content.Intent) {
        try {
            homeRequest.launch(intent)
        } catch (e: Exception) {
            Log.w("Couldn't open ${intent.action}: ${e.message}")
            vm.openSystemSettings(context)
        }
    }
    fun askToBeHome() = launchHomeIntent(vm.homeRequestIntent())
    // Already home: Android's own chooser, so the Home button can be given to another launcher.
    fun chooseHomeApp() = if (isHome) launchHomeIntent(vm.homeChooserIntent()) else askToBeHome()

    LaunchedEffect(ready) {
        if (ready && !initialFocusDone) {
            focusTileId = firstTileId()
            initialFocusDone = true
            refreshHome()
            // Out of the box, opened from another launcher: offer to take over the Home button.
            if (!isHome && !vm.config.value.homePromptDismissed) overlay = Overlay.MakeDefaultHome
        }
    }

    // The upload server lives exactly as long as its screen (Home or any other exit stops it).
    LaunchedEffect(overlay) {
        if (overlay != Overlay.PhotoUpload) vm.stopPhotoUpload()
    }

    // A home screen never closes on Back; it only steps out of modes.
    BackHandler {
        when {
            active != null -> active = null
            editing -> editing = false
        }
    }

    fun skipPicture(forward: Boolean) {
        if (!vm.skipWallpaper(forward)) {
            Toast.makeText(context, "Add more photos in Settings → Wallpaper to switch between them", Toast.LENGTH_LONG).show()
        }
    }

    LauncherTheme(config.cornerRadiusDp) {
        Box(
            Modifier
                .fillMaxSize()
                // On the home screen, the remote's skip buttons change the picture (unless music is
                // playing, in which case they stay with the music). Not while a tile is being edited.
                .onPreviewKeyEvent { event ->
                    val forward = when (event.key) {
                        Key.MediaFastForward, Key.MediaNext, Key.MediaSkipForward -> true
                        Key.MediaRewind, Key.MediaPrevious, Key.MediaSkipBackward -> false
                        else -> return@onPreviewKeyEvent false
                    }
                    if (active != null || vm.isMusicPlaying()) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) skipPicture(forward)
                    true
                },
        ) {
            Wallpaper(wallpaper)
            Column(Modifier.fillMaxSize()) {
                TopBar(
                    editing = editing,
                    onSettings = { refreshHome(); overlay = Overlay.Settings },
                    onNetwork = { vm.openWifiSettings(context) },
                    weather = if (!config.weatherEnabled) null else {
                        {
                            WeatherChip(weather, configured = config.weatherPlace != null, modifier = Modifier.focusRequester(weatherFocus)) {
                                overlay = when {
                                    weather == null && vm.internetBlockedForUs() -> Overlay.NetworkBlocked
                                    config.weatherPlace == null -> Overlay.WeatherSearch
                                    else -> Overlay.Forecast
                                }
                            }
                        }
                    },
                )
                if (ready) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(horizontal = SCREEN_PADDING_H)
                            .padding(top = 26.dp, bottom = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(32.dp),
                    ) {
                        for (section in config.sections) {
                            key(section.id) {
                                SectionView(
                                    section = section,
                                    installed = installed,
                                    editing = editing,
                                    showTitle = config.showSectionTitles,
                                    showAppNames = config.showAppNames,
                                    active = active,
                                    focusTileId = focusTileId,
                                    focusHeader = focusSectionId == section.id,
                                    onFocusHandled = { focusTileId = null },
                                    onHeaderFocusHandled = { focusSectionId = null },
                                    // In edit mode OK picks the tile up (like Apple TV); otherwise it opens the app.
                                    onTileClick = { tile ->
                                        if (editing) {
                                            active = ActiveEdit(tile.id)
                                        } else {
                                            vm.launch(context, tile.packageName)
                                        }
                                    },
                                    onTileMenu = {
                                        tipDone()
                                        overlay = Overlay.TileMenu(section.id, it)
                                    },
                                    onSectionMenu = { overlay = Overlay.SectionMenu(section) },
                                    onAddTile = { overlay = Overlay.PickApp(section.id, null) },
                                    onMove = { tile, dir, columns ->
                                        vm.moveTile(tile.id, dir, columns)
                                        focusTileId = tile.id
                                    },
                                    onActiveDone = { active = null },
                                    artLoader = vm::loadArt,
                                    peekArt = vm::cachedArt,
                                )
                            }
                        }
                        if (editing) {
                            PillButton(
                                "Add Section",
                                onClick = { overlay = Overlay.NameSection(null, "") },
                                modifier = Modifier.padding(start = HEADER_PADDING_H - SCREEN_PADDING_H),
                            )
                        }
                    }
                }
            }
            // Shown for a while on each start until it's been acted on, so it never covers apps for long.
            var tipTimedOut by remember { mutableStateOf(false) }
            LaunchedEffect(ready) {
                if (ready) {
                    delay(15_000)
                    tipTimedOut = true
                }
            }
            AnimatedVisibility(
                visible = ready && !config.tipSeen && !editing && !tipTimedOut && overlay == null,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(),
                exit = fadeOut(tween(800)),
            ) {
                TipPill("Tip: hold OK on any app to move or remove it")
            }
        }

        val dismiss = { overlay = null }
        when (val o = overlay) {
            null -> Unit
            Overlay.Settings -> MenuDialog(
                title = "Settings",
                items = listOfNotNull(
                    if (!isHome) MenuItem("Make This My Home Screen") { askToBeHome() } else null,
                    MenuItem(if (editing) "Done Editing" else "Edit Home Screen") {
                        editing = !editing
                        tipDone()
                    },
                    MenuItem("All Apps", submenu = true) { overlay = Overlay.AllApps },
                    MenuItem("Add Section") { overlay = Overlay.NameSection(null, "") },
                    MenuItem(
                        "Put New Apps on Home Screen",
                        value = if (config.autoAddNewApps) "On" else "Off",
                        keepOpen = true,
                    ) { vm.updateSettings { it.copy(autoAddNewApps = !it.autoAddNewApps) } },
                    MenuItem("Wallpaper", submenu = true) { overlay = Overlay.WallpaperSettings },
                    MenuItem("Weather", value = config.weatherPlace?.name ?: "Off".takeUnless { config.weatherEnabled }, submenu = true) {
                        overlay = Overlay.WeatherSettings
                    },
                    MenuItem("Appearance", submenu = true) { overlay = Overlay.Appearance },
                    MenuItem("Home App", value = homeLabel, submenu = true) { chooseHomeApp() },
                    MenuItem("Android Settings") { vm.openSystemSettings(context) },
                    MenuItem("About") { overlay = Overlay.About },
                ),
                onDismiss = dismiss,
            )
            Overlay.Appearance -> {
                val corner = CORNER_CHOICES.minBy { kotlin.math.abs(it.second - config.cornerRadiusDp) }.first
                MenuDialog(
                    title = "Appearance",
                    items = listOf(
                        MenuItem("Corners", value = corner, submenu = true) {
                            overlay = Overlay.Choice(
                                title = "Corners",
                                items = CORNER_CHOICES.map { (name, dp) ->
                                    MenuItem(name, checked = name == corner) {
                                        vm.updateSettings { it.copy(cornerRadiusDp = dp) }
                                    }
                                },
                                parent = Overlay.Appearance,
                            )
                        },
                        MenuItem("App Names", value = if (config.showAppNames) "Shown" else "Hidden", keepOpen = true) {
                            vm.updateSettings { it.copy(showAppNames = !it.showAppNames) }
                        },
                        MenuItem("Section Names", value = if (config.showSectionTitles) "Shown" else "Hidden", keepOpen = true) {
                            vm.updateSettings { it.copy(showSectionTitles = !it.showSectionTitles) }
                        },
                    ),
                    onDismiss = { overlay = Overlay.Settings },
                )
            }
            Overlay.WallpaperSettings -> {
                val interval = INTERVAL_CHOICES.firstOrNull { it.second == config.wallpaperIntervalSeconds }?.first
                    ?: "${config.wallpaperIntervalSeconds / 60} minutes"
                val photoCount = remember(overlay) { vm.wallpaperCount() }
                MenuDialog(
                    title = "Wallpaper",
                    subtitle = when {
                        photoCount == 0 -> "No photos yet, so the home screen shows a sky gradient."
                        photoCount == 1 -> "1 photo"
                        else -> "$photoCount photos  ·  Fast-forward ⏩ on the remote shows the next one"
                    },
                    items = listOfNotNull(
                        if (photoCount > 1) {
                            MenuItem("Show Next Picture") {
                                skipPicture(forward = true)
                                overlay = null // close everything so the new picture is visible
                            }
                        } else {
                            null
                        },
                        MenuItem("Add Photos from Your Phone", submenu = true) {
                            overlay = when (vm.startPhotoUpload()) {
                                LauncherViewModel.UploadStart.Started -> Overlay.PhotoUpload
                                LauncherViewModel.UploadStart.Blocked -> Overlay.NetworkBlocked
                                LauncherViewModel.UploadStart.NoNetwork -> {
                                    Toast.makeText(context, "Connect the TV to your Wi-Fi first", Toast.LENGTH_LONG).show()
                                    Overlay.WallpaperSettings
                                }
                            }
                        },
                        MenuItem("Order", value = if (config.wallpaperShuffle) "Shuffle" else "By Name", keepOpen = true) {
                            vm.updateSettings { it.copy(wallpaperShuffle = !it.wallpaperShuffle) }
                        },
                        MenuItem("Change Picture Every", value = interval, submenu = true) {
                            overlay = Overlay.Choice(
                                title = "Change Picture Every",
                                items = INTERVAL_CHOICES.map { (name, seconds) ->
                                    MenuItem(name, checked = name == interval) {
                                        vm.updateSettings { it.copy(wallpaperIntervalSeconds = seconds) }
                                    }
                                },
                                parent = Overlay.WallpaperSettings,
                            )
                        },
                        if (photoCount > 0) {
                            MenuItem("Times of Day", value = vm.dayPart().label, submenu = true) { overlay = Overlay.TimesOfDay }
                        } else {
                            null
                        },
                        MenuItem("Use as Screensaver", submenu = true) { overlay = Overlay.ScreensaverHelp },
                        if (photoCount > 0) MenuItem("Remove All Photos") { overlay = Overlay.ConfirmRemovePhotos } else null,
                    ),
                    onDismiss = { overlay = Overlay.Settings },
                )
            }
            Overlay.TimesOfDay -> {
                val now = vm.dayPart()
                val photos = remember(overlay) { vm.wallpaperFiles() }
                MenuDialog(
                    title = "Times of Day",
                    subtitle = timesOfDaySubtitle(config.weatherPlace, now, context),
                    items = buildList {
                        add(MenuItem("Choose Photos for Each Time", submenu = true) { overlay = Overlay.PhotoTimesGrid() })
                        DayPart.entries.forEach { part ->
                            val n = photos.count { part in config.photoTimes[it.name]?.parts.orEmpty() }
                            add(
                                MenuItem(
                                    if (part == now) "${part.label}  ·  now" else part.label,
                                    value = when (n) {
                                        0 -> "Any-time photos"
                                        1 -> "1 photo"
                                        else -> "$n photos"
                                    },
                                    keepOpen = true,
                                ) { overlay = Overlay.PhotoTimesGrid() },
                            )
                        }
                        if (config.weatherPlace == null) {
                            add(MenuItem("Set Your Location", submenu = true) { overlay = Overlay.WeatherSearch })
                        }
                    },
                    onDismiss = { overlay = Overlay.WallpaperSettings },
                )
            }
            is Overlay.PhotoTimesGrid -> {
                val photos = remember(overlay) { vm.wallpaperFiles() }
                PhotoGridDialog(
                    title = "Choose Photos for Each Time",
                    subtitle = "Pick a photo to choose when it shows. Photos on Any time fill in whenever no photo is set for that time.",
                    photos = photos,
                    labelOf = { config.photoTimes[it.name].describe() },
                    thumbnail = { vm.wallpaperThumbnail(it) },
                    focus = photos.firstOrNull { it.name == o.focusName },
                    onPick = { overlay = Overlay.PhotoTimeChoice(it.name) },
                    onDismiss = { overlay = Overlay.TimesOfDay },
                )
            }
            is Overlay.PhotoTimeChoice -> {
                val parts = config.photoTimes[o.name]?.parts.orEmpty()
                MenuDialog(
                    title = "When to Show This Photo",
                    subtitle = "Pick one or more. It's ${vm.dayPart().label.lowercase()} now.",
                    items = buildList {
                        add(MenuItem("Any Time", checked = parts.isEmpty(), keepOpen = true) { vm.setPhotoTimes(o.name, emptySet()) })
                        DayPart.entries.forEach { part ->
                            add(MenuItem(part.label, checked = part in parts, keepOpen = true) {
                                vm.setPhotoTimes(o.name, if (part in parts) parts - part else parts + part)
                            })
                        }
                        add(MenuItem("Done") { })
                    },
                    onDismiss = { overlay = Overlay.PhotoTimesGrid(o.name) },
                )
            }
            Overlay.PhotoUpload -> {
                val session = upload
                if (session == null) {
                    overlay = Overlay.WallpaperSettings
                } else {
                    PhotoUploadDialog(session) {
                        vm.stopPhotoUpload()
                        overlay = Overlay.WallpaperSettings
                    }
                }
            }
            Overlay.ScreensaverHelp -> MenuDialog(
                title = "Use Your Photos as the Screensaver",
                subtitle = if (vm.isOurScreensaver()) {
                    "It's already on. When the TV is idle it shows these photos as a slow slideshow with the time."
                } else {
                    "When the TV is idle it can show these photos as a slow slideshow with the time.\n" +
                        "In Screensaver settings, choose “Waffle Photos”."
                },
                items = listOf(
                    MenuItem("Open Screensaver Settings") { vm.openScreensaverSettings(context) },
                    MenuItem("Preview Now") { vm.previewScreensaver(context) },
                    MenuItem("Not Now") { },
                ),
                onDismiss = { overlay = Overlay.WallpaperSettings },
            )
            Overlay.ConfirmRemovePhotos -> MenuDialog(
                title = "Remove all photos?",
                subtitle = "They're deleted from the TV (your phone keeps its copies). The home screen goes back to the sky gradient.",
                items = listOf(
                    MenuItem("Cancel") { },
                    MenuItem("Remove All") { vm.removeAllWallpapers() },
                ),
                onDismiss = { overlay = Overlay.WallpaperSettings },
            )
            is Overlay.Choice -> MenuDialog(
                title = o.title,
                items = o.items,
                onDismiss = { overlay = o.parent },
            )
            Overlay.About -> MenuDialog(
                title = "Waffle",
                subtitle = "Version ${vm.versionName}\nFree and open source, MIT License.\n" +
                    "Weather data by Open-Meteo.com. Inter typeface, SIL Open Font License.",
                items = listOf(
                    MenuItem("Diagnostics", submenu = true) { overlay = Overlay.Diagnostics },
                    MenuItem("Close") { },
                ),
                onDismiss = { overlay = Overlay.Settings },
            )
            Overlay.Diagnostics -> {
                val crash = remember(overlay) { vm.lastCrash() }
                MenuDialog(
                    title = "Diagnostics",
                    subtitle = "For bug reports. Full logs: adb logcat -s Waffle",
                    items = vm.diagnostics().map { (label, value) -> MenuItem(label, value = value, keepOpen = true) { } } +
                        if (crash != null) {
                            MenuItem("Last Crash", value = crash.lineSequence().first().removePrefix("Time: "), submenu = true) {
                                overlay = Overlay.CrashReport(crash)
                            }
                        } else {
                            MenuItem("Last Crash", value = "None", keepOpen = true) { }
                        },
                    onDismiss = { overlay = Overlay.About },
                )
            }
            is Overlay.CrashReport -> TextDialog(
                title = "Last Crash",
                body = o.text,
                actions = listOf(MenuItem("Clear Report") { vm.clearLastCrash() }),
                onDismiss = { overlay = Overlay.Diagnostics },
            )
            is Overlay.TileMenu -> {
                val others = config.sections.filter { it.id != o.sectionId }
                val favorites = config.sections.firstOrNull()
                MenuDialog(
                    title = installed[o.tile.packageName]?.label ?: o.tile.packageName,
                    items = buildList {
                        add(
                            MenuItem("Move") {
                                active = ActiveEdit(o.tile.id)
                                focusTileId = o.tile.id
                            },
                        )
                        // The top section is the favorites row; offer the one-step version people expect.
                        if (favorites != null && favorites.id != o.sectionId) {
                            add(
                                MenuItem("Move to ${favorites.title.ifBlank { "Top Row" }}") {
                                    vm.moveTileToSection(o.tile.id, favorites.id)
                                    focusTileId = o.tile.id
                                },
                            )
                        }
                        add(
                            MenuItem("Move to Section", submenu = true) {
                                overlay = Overlay.Choice(
                                    title = "Move to Section",
                                    items = others.mapIndexed { i, section ->
                                        MenuItem(section.title.ifBlank { "Section ${i + 1}" }) {
                                            vm.moveTileToSection(o.tile.id, section.id)
                                            focusTileId = o.tile.id
                                        }
                                    } + MenuItem("New Section…") {
                                        overlay = Overlay.NameSection(null, "", moveTileId = o.tile.id)
                                    },
                                    parent = o,
                                )
                            },
                        )
                        add(MenuItem("Change App") { overlay = Overlay.PickApp(o.sectionId, o.tile.id) })
                        add(MenuItem("App Info") { vm.openAppInfo(context, o.tile.packageName) })
                        add(MenuItem("Remove from Home Screen") { focusTileId = vm.removeTile(o.tile.id) })
                        if (!editing) add(MenuItem("Edit Home Screen") { editing = true })
                    },
                    onDismiss = dismiss,
                )
            }
            is Overlay.SectionMenu -> {
                // The overlay holds a snapshot; show the section as it is now (after a Columns change etc.).
                val section = config.sections.firstOrNull { it.id == o.section.id } ?: o.section
                val back = Overlay.SectionMenu(section)
                MenuDialog(
                title = section.title.ifBlank { "Untitled Section" },
                items = listOf(
                    MenuItem("Add App") { overlay = Overlay.PickApp(section.id, null) },
                    MenuItem("Rename") { overlay = Overlay.NameSection(section.id, section.title) },
                    MenuItem("Columns", value = "${section.columns}", submenu = true) {
                        overlay = Overlay.Choice(
                            title = "Apps per Row",
                            items = COLUMN_CHOICES.map { n ->
                                MenuItem("$n", checked = n == section.columns) {
                                    vm.setSectionLayout(section.id, columns = n)
                                }
                            },
                            parent = back,
                        )
                    },
                    MenuItem("Short Rows", value = alignName(section.shortRows), submenu = true) {
                        overlay = Overlay.Choice(
                            title = "Short Rows Line Up on the",
                            items = RowAlign.entries.map { a ->
                                MenuItem(alignName(a), checked = a == section.shortRows) {
                                    vm.setSectionLayout(section.id, shortRows = a)
                                }
                            },
                            parent = back,
                        )
                    },
                    MenuItem("Move Up") {
                        vm.moveSection(section.id, -1)
                        focusSectionId = section.id
                    },
                    MenuItem("Move Down") {
                        vm.moveSection(section.id, 1)
                        focusSectionId = section.id
                    },
                    MenuItem("Delete Section") {
                        if (section.tiles.isEmpty()) focusSectionId = vm.deleteSection(section.id)
                        else overlay = Overlay.ConfirmDeleteSection(section)
                    },
                ),
                onDismiss = dismiss,
                )
            }
            is Overlay.ConfirmDeleteSection -> MenuDialog(
                title = "Delete “${o.section.title.ifBlank { "Untitled Section" }}”?",
                subtitle = "Its ${o.section.tiles.size} apps will be removed from the home screen. " +
                    "The apps themselves stay installed.",
                items = listOf(
                    MenuItem("Cancel") { },
                    MenuItem("Delete") { focusSectionId = vm.deleteSection(o.section.id) },
                ),
                onDismiss = dismiss,
            )
            Overlay.Forecast -> ForecastDialog(
                report = weather,
                placeName = config.weatherPlace?.name ?: "Weather",
                onChangePlace = { overlay = Overlay.WeatherSearch },
                onDismiss = dismiss,
            )
            Overlay.WeatherSettings -> {
                val unitName = { u: TemperatureUnit ->
                    when (u) {
                        TemperatureUnit.AUTO -> "Automatic (°${if (u.resolve() == TemperatureUnit.FAHRENHEIT) "F" else "C"})"
                        TemperatureUnit.CELSIUS -> "Celsius (°C)"
                        TemperatureUnit.FAHRENHEIT -> "Fahrenheit (°F)"
                    }
                }
                MenuDialog(
                    title = "Weather",
                    items = buildList {
                        add(MenuItem("Location", value = config.weatherPlace?.name ?: "Not set", submenu = true) {
                            overlay = Overlay.WeatherSearch
                        })
                        add(MenuItem("Temperature", value = unitName(config.temperatureUnit), submenu = true) {
                            overlay = Overlay.Choice(
                                title = "Temperature",
                                items = TemperatureUnit.entries.map { u ->
                                    MenuItem(unitName(u), checked = u == config.temperatureUnit) {
                                        vm.updateSettings { it.copy(temperatureUnit = u) }
                                    }
                                },
                                parent = Overlay.WeatherSettings,
                            )
                        })
                        add(MenuItem("Show Weather", value = if (config.weatherEnabled) "On" else "Off", keepOpen = true) {
                            vm.updateSettings { it.copy(weatherEnabled = !it.weatherEnabled) }
                        })
                    },
                    onDismiss = { overlay = Overlay.Settings },
                )
            }
            Overlay.WeatherSearch -> TextInputDialog(
                title = "Weather Location",
                initial = lastSearch.ifBlank { config.weatherPlace?.name ?: "" },
                subtitle = "Type a city or town, then choose it from the list.",
                confirmLabel = "Search",
                onConfirm = { query ->
                    if (query.isBlank()) return@TextInputDialog
                    lastSearch = query
                    overlay = null
                    val searchId = ++placeSearchId
                    vm.searchPlaces(query) { result ->
                        // A double press on Search starts two searches; drop the older answer. Also
                        // drop it if something else has been opened since (or a town already chosen).
                        if (searchId != placeSearchId || overlay != null) return@searchPlaces
                        overlay = when {
                            result is LauncherViewModel.PlaceSearch.Blocked -> Overlay.NetworkBlocked
                            result !is LauncherViewModel.PlaceSearch.Found -> {
                                Toast.makeText(context, "Couldn't reach the weather service. Check the network.", Toast.LENGTH_LONG).show()
                                Overlay.WeatherSearch
                            }
                            result.places.isEmpty() -> {
                                Toast.makeText(context, "No places called “$query”", Toast.LENGTH_LONG).show()
                                Overlay.WeatherSearch
                            }
                            else -> Overlay.Choice(
                                title = "Choose “$query”",
                                items = result.places.map { place ->
                                    MenuItem(place.name, value = place.region) {
                                        vm.updateSettings { it.copy(weatherPlace = place, weatherEnabled = true) }
                                        // A Choice goes back to its parent after a pick; here the parent is
                                        // the search box, so close everything instead.
                                        overlay = null
                                        placeSearchId++
                                        focusWeather = true
                                    }
                                },
                                parent = Overlay.WeatherSearch,
                            )
                        }
                    }
                },
                onDismiss = dismiss,
            )
            Overlay.MakeDefaultHome -> MenuDialog(
                title = "Make Waffle Your Home Screen?",
                subtitle = "Then the Home button on your remote brings you here. You can switch back any time " +
                    "in Android's settings.",
                items = listOf(
                    MenuItem("Yes, Use Waffle") { askToBeHome() },
                    MenuItem("Not Now") { vm.updateSettings { it.copy(homePromptDismissed = true) } },
                ),
                onDismiss = dismiss,
            )
            Overlay.NetworkBlocked -> MenuDialog(
                title = "Internet Access Is Off",
                subtitle = "This TV is blocking network access for Waffle, so weather and adding photos " +
                    "from your phone can't work.\nIf App Info has a Network access switch, turn it on. If it " +
                    "doesn't (LineageOS on TVs), uninstall and reinstall Waffle: this happens when an old " +
                    "version was installed first.",
                items = listOf(
                    MenuItem("Open App Info") { vm.openAppInfo(context, context.packageName) },
                    MenuItem("Not Now") { },
                ),
                onDismiss = dismiss,
            )
            Overlay.AllApps -> AppPickerDialog(
                title = "All Apps",
                apps = installed.values.toList(),
                artLoader = vm::loadArt,
                peekArt = vm::cachedArt,
                onPick = { app ->
                    overlay = null
                    vm.launch(context, app.packageName)
                },
                onDismiss = { overlay = Overlay.Settings },
            )
            is Overlay.PickApp -> AppPickerDialog(
                apps = installed.values.toList(),
                artLoader = vm::loadArt,
                peekArt = vm::cachedArt,
                onPick = { app ->
                    overlay = null
                    focusTileId = if (o.replaceTileId != null) {
                        vm.replaceTileApp(o.replaceTileId, app.packageName)
                        o.replaceTileId
                    } else {
                        vm.addTile(o.sectionId, app.packageName)
                    }
                },
                onDismiss = dismiss,
            )
            is Overlay.NameSection -> TextInputDialog(
                title = if (o.sectionId == null) "New Section" else "Rename Section",
                initial = o.current,
                onConfirm = { name ->
                    overlay = null
                    if (o.sectionId == null) {
                        val id = vm.addSection(name)
                        if (o.moveTileId != null) {
                            vm.moveTileToSection(o.moveTileId, id)
                            focusTileId = o.moveTileId
                        } else {
                            editing = true // so the new, empty section shows up with its "+" tile
                        }
                    } else {
                        vm.renameSection(o.sectionId, name)
                    }
                },
                onDismiss = dismiss,
            )
        }
    }
}

/** One picture in [FadeOver]'s stack; null is the sky gradient. */
private class WallLayer(val image: ImageBitmap?, val alpha: Animatable<Float, AnimationVector1D>)

/**
 * Fades each new picture in on top of the ones already showing, which stay fully opaque until
 * it has finished and they're dropped. (Crossfade fades the old one out at the same time, so
 * halfway through the sky gradient shows through, and skipping quickly stacks several
 * half-faded pictures and flashes it.) A change mid-fade just starts another layer on top.
 */
@Composable
private fun FadeOver(image: ImageBitmap?, durationMillis: Int = 2500) {
    val layers = remember { mutableStateListOf<WallLayer>() }
    LaunchedEffect(image) {
        if (layers.lastOrNull()?.image === image && layers.isNotEmpty()) return@LaunchedEffect
        val layer = WallLayer(image, Animatable(if (layers.isEmpty()) 1f else 0f))
        layers.add(layer)
        layer.alpha.animateTo(1f, tween(durationMillis))
        // Fully covered now: everything underneath can go.
        while (layers.firstOrNull() !== layer) layers.removeAt(0)
    }
    for (layer in layers) {
        key(layer) {
            val m = Modifier.fillMaxSize().graphicsLayer { alpha = layer.alpha.value }
            if (layer.image != null) {
                Image(layer.image, contentDescription = null, modifier = m, contentScale = ContentScale.Crop)
            } else {
                Box(m.background(Brush.verticalGradient(listOf(Palette.SkyTop, Palette.SkyMiddle, Palette.SkyBottom))))
            }
        }
    }
}

/** The photo (fading in over the last one when it changes), over a sky gradient, under soft scrims for legibility. */
@Composable
private fun Wallpaper(image: ImageBitmap?) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Palette.SkyTop, Palette.SkyMiddle, Palette.SkyBottom)),
            ),
        )
        FadeOver(image)
        // Darken the top (clock, first section title) and a touch overall, so white text stays
        // readable on bright skies without dulling the picture.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.38f),
                    0.28f to Color.Black.copy(alpha = 0.10f),
                    0.7f to Color.Black.copy(alpha = 0.06f),
                    1f to Color.Black.copy(alpha = 0.28f),
                ),
            ),
        )
    }
}

@Composable
private fun TopBar(
    editing: Boolean,
    onSettings: () -> Unit,
    onNetwork: () -> Unit,
    weather: (@Composable () -> Unit)?,
) {
    val now by rememberNow()
    // Things you read on the left, lined up with the tiles; the two controls on the right.
    Row(
        Modifier
            .fillMaxWidth()
            // The buttons' glyphs (not their round focus areas) line up with the tiles' right edge.
            .padding(start = HEADER_PADDING_H, end = HEADER_PADDING_H - 10.dp, top = 26.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            if (editing) {
                WallText("Editing Home Screen", fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(Palette.Accent, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    WallText(
                        "Select an app to move it  ·  Hold OK for more  ·  Back when done",
                        fontSize = 18.sp,
                        color = Palette.OnWallMuted,
                    )
                }
            } else {
                TimeText(now)
                Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    DateText(now)
                    if (weather != null) {
                        WallText("  ·", fontSize = 18.sp, color = Palette.OnWallMuted)
                        weather()
                    }
                }
            }
        }
        // Centred on the time's line.
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircleIconButton(onClick = onNetwork, label = "Network settings") { NetworkIndicator() }
            CircleIconButton(onClick = onSettings, label = "Settings") {
                androidx.tv.material3.Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(25.dp))
            }
        }
    }
}

/** A round, borderless button for the top bar that turns solid white when focused. */
@Composable
private fun CircleIconButton(onClick: () -> Unit, label: String, content: @Composable () -> Unit) {
    FlatSurface(
        onClick = onClick,
        label = label,
        modifier = Modifier.size(46.dp),
        shape = CircleShape,
        containerColor = Color.Transparent,
        focusedContainerColor = Palette.Focus,
        contentColor = Palette.OnWall,
        focusedContentColor = Palette.SheetFocusedText,
        focusedBorderColor = null,
        focusedScale = 1.1f,
    ) {
        Box(Modifier.align(Alignment.Center)) { content() }
    }
}

@Composable
private fun SectionView(
    section: Section,
    installed: Map<String, AppInfo>,
    editing: Boolean,
    showTitle: Boolean,
    showAppNames: Boolean,
    active: ActiveEdit?,
    focusTileId: String?,
    focusHeader: Boolean,
    onFocusHandled: () -> Unit,
    onHeaderFocusHandled: () -> Unit,
    onTileClick: (Tile) -> Unit,
    onTileMenu: (Tile) -> Unit,
    onSectionMenu: () -> Unit,
    onAddTile: () -> Unit,
    onMove: (Tile, MoveDirection, Int) -> Unit,
    onActiveDone: () -> Unit,
    artLoader: suspend (AppInfo) -> AppArt?,
    peekArt: (AppInfo) -> AppArt?,
) {
    // Uninstalled apps are removed from the layout; one that can't be opened right now (disabled, or
    // mid-update) is hidden outside edit mode rather than shown broken.
    val tiles = if (editing) section.tiles else section.tiles.filter { it.packageName in installed }
    val titleVisible = showTitle && section.title.isNotBlank()
    if (!editing && tiles.isEmpty() && !titleVisible) return

    val headerFocus = remember { FocusRequester() }
    LaunchedEffect(focusHeader, editing) {
        if (focusHeader && editing) {
            runCatching { headerFocus.requestFocus() }
            onHeaderFocusHandled()
        }
    }

    Column(Modifier.fillMaxWidth()) {
        if (editing) {
            PillButton(
                text = section.title.ifBlank { "Untitled Section" },
                hint = "Options",
                onClick = onSectionMenu,
                // Same left edge as the header and the tiles.
                modifier = Modifier.padding(start = HEADER_PADDING_H - SCREEN_PADDING_H).focusRequester(headerFocus),
            )
            Spacer(Modifier.height(16.dp))
        } else if (titleVisible) {
            WallText(section.title, Modifier.padding(start = 4.dp), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
        }
        if (tiles.isEmpty() && !editing) return@Column
        // How many tiles fit across, read when a tile is moved up or down (not state: no recomposition).
        val columns = remember { intArrayOf(1) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Every tile gets the 16:9 size that makes exactly [Section.columns] fill the row.
        val tileWidth = (maxWidth - TILE_GAP * (section.columns - 1)) / section.columns
        val tileHeight = tileWidth * 9f / 16f
        TileGrid(
            gap = TILE_GAP,
            align = section.shortRows,
            columns = section.columns,
            modifier = Modifier.fillMaxWidth(),
            onColumns = { columns[0] = it },
        ) {
            // Not key()ed: FlowRow didn't re-place keyed children when they were reordered (Compose
            // 1.7 and 1.8), so tiles became positional, and focus follows a moved tile through
            // focusTileId. Kept that way with TileGrid since it works and costs nothing.
            for (tile in tiles) {
                TileView(
                    tile = tile,
                    width = tileWidth,
                    height = tileHeight,
                    app = installed[tile.packageName],
                    moving = active?.tileId == tile.id,
                    showName = showAppNames,
                    requestFocus = focusTileId == tile.id,
                    onFocusHandled = onFocusHandled,
                    onClick = { onTileClick(tile) },
                    onMenu = { onTileMenu(tile) },
                    onMove = { onMove(tile, it, columns[0]) },
                    wiggle = editing,
                    onDone = onActiveDone,
                    artLoader = artLoader,
                    peekArt = peekArt,
                )
            }
            if (editing) {
                FlatSurface(
                    onClick = onAddTile,
                    modifier = Modifier.size(tileHeight),
                    focusedContainerColor = Palette.Focus,
                    focusedContentColor = Palette.SheetFocusedText,
                    focusedBorderColor = null,
                    focusedScale = 1.08f,
                    lift = true,
                ) {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        androidx.tv.material3.Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(36.dp))
                        Text("Add App", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun TileView(
    tile: Tile,
    width: Dp,
    height: Dp,
    app: AppInfo?,
    moving: Boolean,
    showName: Boolean,
    requestFocus: Boolean,
    onFocusHandled: () -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onMove: (MoveDirection) -> Unit,
    onDone: () -> Unit,
    artLoader: suspend (AppInfo) -> AppArt?,
    peekArt: (AppInfo) -> AppArt?,
    modifier: Modifier = Modifier,
    wiggle: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    // Edit mode: tiles rock gently (each slightly out of step) so it's obvious things can move.
    val rotation = if (wiggle && !moving) {
        val phase = remember(tile.id) { (tile.id.hashCode() and 0xff) / 255f }
        val t by rememberInfiniteTransition(label = "wiggle").animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(durationMillis = 240 + (phase * 80).toInt(), easing = LinearEasing),
                RepeatMode.Reverse,
                initialStartOffset = StartOffset((phase * 240).toInt()),
            ),
            label = "angle",
        )
        t * 0.9f
    } else {
        0f
    }
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            runCatching { focusRequester.requestFocus() }
            onFocusHandled()
        }
    }

    val art by rememberAppArt(app, artLoader, peekArt)

    // While moving, the D-pad moves the tile instead of moving focus. Directions act on key-down
    // (so holding repeats); OK/Back finish on key-up so the release doesn't also count as a click.
    val keys = Modifier.onPreviewKeyEvent { event ->
        if (!moving) {
            if (event.key == Key.Menu) {
                if (event.type == KeyEventType.KeyUp) onMenu()
                return@onPreviewKeyEvent true
            }
            return@onPreviewKeyEvent false
        }
        val down = event.type == KeyEventType.KeyDown
        when (event.key) {
            Key.DirectionLeft -> if (down) onMove(MoveDirection.LEFT)
            Key.DirectionRight -> if (down) onMove(MoveDirection.RIGHT)
            Key.DirectionUp -> if (down) onMove(MoveDirection.UP)
            Key.DirectionDown -> if (down) onMove(MoveDirection.DOWN)
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Back, Key.Escape ->
                if (event.type == KeyEventType.KeyUp) onDone()
            else -> return@onPreviewKeyEvent false
        }
        true
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FlatSurface(
            onClick = { if (!moving) onClick() },
            onLongClick = { if (!moving) onMenu() },
            label = app?.label ?: "${tile.packageName}, not installed",
            modifier = Modifier
                .graphicsLayer { rotationZ = rotation }
                .size(width, height)
                .focusRequester(focusRequester)
                .then(keys),
            focusedBorderWidth = if (moving) 4.dp else 3.dp,
            focusedBorderColor = if (moving) Palette.Accent else Palette.Focus,
            // Picked up to move: lifted higher than a normal focus.
            focusedScale = if (moving) 1.12f else 1.08f,
            lift = true,
        ) {
            TileContent(tile, height, app, art, labelInside = !showName)
            if (moving) MoveOverlay(height)
        }
        if (showName) {
            WallText(
                app?.label ?: "Not installed",
                Modifier.width(width).padding(top = 10.dp),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TileContent(tile: Tile, height: Dp, app: AppInfo?, art: AppArt?, labelInside: Boolean) {
    when {
        app == null -> Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Not installed", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(tile.packageName, color = Palette.OnWallMuted, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center)
        }
        art != null && art.isBanner -> Image(
            bitmap = art.bitmap,
            contentDescription = null, // the tile itself carries the app's name
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        else -> Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (art != null) {
                // Tiles are 16:9, so height alone decides how big the icon can be.
                val iconSize = minOf(height * (if (labelInside) 0.45f else 0.6f), 96.dp)
                Image(art.bitmap, contentDescription = null, modifier = Modifier.size(iconSize))
            }
            if (labelInside || art == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    app.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun MoveOverlay(height: Dp) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))) {
        // Arrows on all four sides say "use the D-pad".
        val arrow = Modifier.size(if (height < 100.dp) 20.dp else 28.dp)
        androidx.tv.material3.Icon(Icons.Filled.KeyboardArrowUp, null, arrow.align(Alignment.TopCenter))
        androidx.tv.material3.Icon(Icons.Filled.KeyboardArrowDown, null, arrow.align(Alignment.BottomCenter))
        androidx.tv.material3.Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, arrow.align(Alignment.CenterStart))
        androidx.tv.material3.Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, arrow.align(Alignment.CenterEnd))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Move",
                color = Palette.Accent,
                fontSize = if (height < 100.dp) 15.sp else 19.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (height >= 90.dp) {
                Text("OK when done", color = Palette.OnWallMuted, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/** A one-time hint floating at the bottom of the screen. */
@Composable
private fun TipPill(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(bottom = 28.dp)
            .background(Palette.Sheet, CircleShape)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(Palette.Accent, CircleShape))
        Spacer(Modifier.width(12.dp))
        Text(text, color = Palette.SheetText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

/** A frosted pill with text (section headers in edit mode, "Add Section"). */
@Composable
private fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, hint: String? = null) {
    FlatSurface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        focusedContainerColor = Palette.Focus,
        focusedContentColor = Palette.SheetFocusedText,
        focusedBorderColor = null,
        focusedScale = 1.05f,
    ) {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (hint != null) {
                Spacer(Modifier.width(12.dp))
                Text(hint, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.alpha(0.7f))
            }
        }
    }
}
