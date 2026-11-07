package com.barrettcoats.waffle

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.barrettcoats.waffle.data.AppArt
import com.barrettcoats.waffle.data.AppInfo
import com.barrettcoats.waffle.data.AppRepository
import com.barrettcoats.waffle.data.ConfigStore
import com.barrettcoats.waffle.data.LauncherConfig
import com.barrettcoats.waffle.data.Section
import com.barrettcoats.waffle.data.Tile
import com.barrettcoats.waffle.data.MoveDirection
import com.barrettcoats.waffle.data.mapSection
import com.barrettcoats.waffle.data.mapTile
import com.barrettcoats.waffle.data.moveSection
import com.barrettcoats.waffle.data.moveTile
import com.barrettcoats.waffle.data.moveTileToSection
import com.barrettcoats.waffle.data.removeTilesFor
import com.barrettcoats.waffle.data.syncInstalled
import com.barrettcoats.waffle.data.PhotoUploadServer
import com.barrettcoats.waffle.data.WallpaperSource
import com.barrettcoats.waffle.data.DayPart
import com.barrettcoats.waffle.data.SunClock
import com.barrettcoats.waffle.data.PhotoSchedule
import com.barrettcoats.waffle.data.dayPartNow
import com.barrettcoats.waffle.data.describe
import com.barrettcoats.waffle.data.wallpaperPool
import com.barrettcoats.waffle.data.withParts
import fi.iki.elonen.NanoHTTPD
import com.barrettcoats.waffle.data.RowAlign
import com.barrettcoats.waffle.data.WeatherClient
import com.barrettcoats.waffle.data.WeatherPlace
import com.barrettcoats.waffle.data.WeatherReport
import androidx.compose.ui.graphics.ImageBitmap
import com.barrettcoats.waffle.data.defaultConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = AppRepository(application)
    private val store = ConfigStore(File(application.filesDir, "config.json"))

    @OptIn(ExperimentalCoroutinesApi::class)
    private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var saveJob: Job? = null

    private val _config = MutableStateFlow(LauncherConfig())
    val config: StateFlow<LauncherConfig> = _config.asStateFlow()

    private val _installed = MutableStateFlow<Map<String, AppInfo>>(emptyMap())
    val installed: StateFlow<Map<String, AppInfo>> = _installed.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val wallpapers = WallpaperSource(application)
    private val _wallpaper = MutableStateFlow(WallpaperFrame(null, 0))
    /** The current wallpaper and how to bring it in. */
    val wallpaper: StateFlow<WallpaperFrame> = _wallpaper.asStateFlow()
    private var wallpaperJob: Job? = null
    private var wallpaperWanted = false
    /**
     * The picture to show next, decoded as the home screen goes out of sight (while the process
     * still runs; Android freezes it soon after), so coming back to a due change can show it
     * before the first frame instead of fading it in over the old one. [part] is the time of day
     * it was picked for: crossing into another one on the way back means it doesn't fit.
     */
    private class PreparedWallpaper(val file: File, val bitmap: ImageBitmap, val part: DayPart)
    private var preparedWallpaper: PreparedWallpaper? = null
    private var prepareJob: Job? = null
    /**
     * Which picture is up and when it went up (wall-clock ms), kept on disk as well: Android
     * often kills a launcher's process while a big app (a movie) runs, and without this every
     * restart went back to the first picture with a fresh timer.
     */
    private val wallpaperState = application.getSharedPreferences("wallpaper", android.content.Context.MODE_PRIVATE)
    private var wallpaperChangedAt = wallpaperState.getLong("changedAt", 0L)

    private val weatherClient = WeatherClient()
    private val _weather = MutableStateFlow<WeatherReport?>(null)
    /** Latest forecast for the chosen place, or null (none chosen, or not fetched yet). */
    val weather: StateFlow<WeatherReport?> = _weather.asStateFlow()
    private var weatherJob: Job? = null

    private val _homePressed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Home pressed while the home screen was already showing: jump back to the top. */
    val homePressed: SharedFlow<Unit> = _homePressed.asSharedFlow()

    fun onHomePressedWhileVisible() {
        _homePressed.tryEmit(Unit)
    }
    private var wallpaperFile: File? = null

    val versionName: String = runCatching {
        application.packageManager.getPackageInfo(application.packageName, 0).versionName
    }.getOrNull() ?: "?"

    private val launcherApps = application.getSystemService(LauncherApps::class.java)
    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = refreshApps()
        override fun onPackageAdded(packageName: String, user: UserHandle) = refreshApps()
        override fun onPackageChanged(packageName: String, user: UserHandle) = refreshApps()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            refreshApps()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            refreshApps()
    }

    init {
        launcherApps.registerCallback(packageCallback, Handler(Looper.getMainLooper()))
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { repo.loadApps() }
            _installed.value = apps
            Log.i("Found ${apps.size} launchable apps (${apps.values.count { it.isTv }} TV apps)")
            val loaded = withContext(Dispatchers.IO) { store.load() }
            _config.value = loaded ?: defaultConfig(apps).also {
                Log.i("No saved layout; created the default one")
                scheduleSave(it)
            }
            val c = _config.value
            Log.i("Layout: ${c.sections.size} sections, ${c.sections.sumOf { it.tiles.size }} tiles")
            _ready.value = true
            syncInstalled()
        }
    }

    override fun onCleared() {
        stopPhotoUpload()
        launcherApps.unregisterCallback(packageCallback)
        saveNow()
    }

    // --- Wallpaper ----------------------------------------------------------------------------

    // --- Weather ------------------------------------------------------------------------------

    /** Keeps the forecast fresh (every 30 minutes) while the home screen is visible. */
    fun startWeather() {
        if (weatherJob?.isActive == true) return
        weatherJob = viewModelScope.launch {
            _ready.first { it }
            while (true) {
                val stale = _weather.value?.let { System.currentTimeMillis() - it.fetchedAt > WEATHER_REFRESH_MS } ?: true
                if (stale) refreshWeather()
                delay(60_000)
            }
        }
    }

    fun stopWeather() {
        weatherJob?.cancel()
    }

    private suspend fun refreshWeather() {
        val c = _config.value
        val place = c.weatherPlace
        if (place == null || !c.weatherEnabled) {
            _weather.value = null
            return
        }
        try {
            _weather.value = withContext(Dispatchers.IO) { weatherClient.forecast(place, c.temperatureUnit) }
            Log.i("Weather for ${place.name}: ${_weather.value?.temperature}° ${_weather.value?.sky}")
        } catch (e: Exception) {
            // Keep showing the last report; try again on the next tick.
            Log.w("Weather update failed${if (internetBlockedForUs()) " (network access blocked for this app)" else ""}: ${e.message}")
        }
    }

    /**
     * Some TV builds (LineageOS's per-app "Network access" switch, which is off by default for new
     * apps when restricted networking is on) block an app's internet while the TV itself is online.
     * Requests then fail with an unhelpful DNS error, so check for it explicitly.
     */
    @Suppress("DEPRECATION")
    fun internetBlockedForUs(): Boolean = runCatching {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        cm.activeNetworkInfo?.detailedState == NetworkInfo.DetailedState.BLOCKED
    }.getOrDefault(false)

    sealed interface PlaceSearch {
        data class Found(val places: List<WeatherPlace>) : PlaceSearch
        data object Blocked : PlaceSearch
        data object Failed : PlaceSearch
    }

    /** Looks up places by name for the location picker. */
    fun searchPlaces(query: String, onResult: (PlaceSearch) -> Unit) {
        viewModelScope.launch {
            val result = try {
                PlaceSearch.Found(withContext(Dispatchers.IO) { weatherClient.searchPlaces(query) })
            } catch (e: Exception) {
                val blocked = internetBlockedForUs()
                Log.w("Place search failed${if (blocked) " (Android is blocking our network access)" else ""}: ${e.message}")
                if (blocked) PlaceSearch.Blocked else PlaceSearch.Failed
            }
            onResult(result)
        }
    }

    /**
     * Rotates through the wallpaper folder while the launcher is visible. The interval is real
     * time since the picture last changed, including time spent in other apps or asleep: coming
     * home after a full interval shows the next picture straight away, and a shorter absence
     * only waits out what's left. (Counting only time on screen meant short, frequent visits
     * home never saw a change.)
     *
     * [cameBack] is true when the home screen has just come into view. A change that's due then
     * shouldn't play the slow fade over the old picture: with the next picture already decoded
     * (see [prepareNextWallpaper]) it's swapped in before the first frame, and otherwise (the
     * process was restarted, or it's now another part of the day) it gets a quick fade.
     */
    fun startWallpaper(cameBack: Boolean = false) {
        wallpaperWanted = true
        prepareJob?.cancel()
        val prepared = preparedWallpaper
        preparedWallpaper = null
        if (wallpaperJob?.isActive == true) return
        if (cameBack && prepared != null && _ready.value && wallpaperDue() && prepared.part == dayPart()) {
            showPrepared(prepared)
        }
        wallpaperJob = viewModelScope.launch {
            var fadeMillis = if (cameBack) FADE_QUICK_MS else FADE_SLOW_MS
            // The interval lives in the saved config, which may still be loading on first start.
            _ready.first { it }
            if (wallpaperFile?.exists() != true && !restoreWallpaper(fadeMillis)) showNextWallpaper(fadeMillis)
            while (true) {
                // The picture up may not belong to this part of the day (it just turned evening,
                // or its times were changed): move on to one that does, without waiting.
                if (!currentFitsNow()) {
                    showNextWallpaper(fadeMillis)
                    markWallpaperChanged()
                }
                val intervalMs = wallpaperIntervalMs()
                // Negative means the clock was set back; treat that as due rather than wait.
                val sinceChange = System.currentTimeMillis() - wallpaperChangedAt
                if (sinceChange in 0 until intervalMs) {
                    fadeMillis = FADE_SLOW_MS
                    // Wake at least every minute to notice the time of day changing.
                    delay(minOf(intervalMs - sinceChange, 60_000L))
                    continue
                }
                showNextWallpaper(fadeMillis)
                // Also when nothing changed (one photo, or it wouldn't decode), so we wait again.
                markWallpaperChanged()
                fadeMillis = FADE_SLOW_MS
            }
        }
    }

    /** How long a picture stays up; "Never" keeps it (but it still follows the time of day). */
    private fun wallpaperIntervalMs(): Long {
        val seconds = _config.value.wallpaperIntervalSeconds
        return if (seconds <= 0) Long.MAX_VALUE else seconds.coerceAtLeast(5) * 1000L
    }

    private fun wallpaperDue(): Boolean =
        System.currentTimeMillis() - wallpaperChangedAt !in 0 until wallpaperIntervalMs()

    /** Morning, day, evening or night right now where the TV is (clock hours without a place). */
    fun dayPart(): DayPart = dayPartNow(_config.value.weatherPlace)

    /** The photos that may show right now (see [wallpaperPool]). */
    private fun wallpaperPoolNow(files: List<File>): List<File> =
        wallpaperPool(files, _config.value.photoTimes, dayPart())

    private suspend fun currentFitsNow(): Boolean {
        val current = wallpaperFile ?: return true
        val files = withContext(Dispatchers.IO) { wallpapers.list() }
        return current in wallpaperPoolNow(files)
    }

    fun stopWallpaper() {
        wallpaperWanted = false
        wallpaperJob?.cancel()
        prepareNextWallpaper()
    }

    /**
     * Decodes the picture that's next, for [startWallpaper] to show at once if it's due when
     * the home screen comes back. Which picture is next doesn't depend on how long we're away
     * (a due change always moves one step), only on the part of the day, which is checked then.
     */
    private fun prepareNextWallpaper() {
        prepareJob?.cancel()
        preparedWallpaper = null
        if (!_ready.value || wallpaperIntervalMs() == Long.MAX_VALUE) return
        prepareJob = viewModelScope.launch {
            val files = withContext(Dispatchers.IO) { wallpapers.list() }
            val part = dayPart()
            val next = pickNextWallpaper(wallpaperPool(files, _config.value.photoTimes, part)) ?: return@launch
            if (next == wallpaperFile) return@launch
            val bitmap = decodeWallpaper(next) ?: return@launch
            preparedWallpaper = PreparedWallpaper(next, bitmap, part)
            Log.i("Wallpaper ${next.name} ready for when we're back")
        }
    }

    private fun showPrepared(p: PreparedWallpaper) {
        wallpaperFile?.let { rememberShown(it) }
        wallpaperFile = p.file
        _wallpaper.value = WallpaperFrame(p.bitmap, 0)
        markWallpaperChanged()
        Log.i("Wallpaper: ${p.file.name} (decoded while away, shown on return)")
    }

    private fun restartWallpaper() {
        if (!wallpaperWanted) return
        wallpaperJob?.cancel()
        startWallpaper()
    }

    /** Pictures shown before the current one, newest last, so "previous" works in shuffle too. */
    private val wallpaperHistory = ArrayDeque<File>()

    private fun rememberShown(file: File) {
        wallpaperHistory.addLast(file)
        while (wallpaperHistory.size > 30) wallpaperHistory.removeFirst()
    }

    /** The picture after the current one in [pool] (a random other one in shuffle), or null if empty. */
    private fun pickNextWallpaper(pool: List<File>): File? {
        if (pool.isEmpty()) return null
        val current = wallpaperFile
        return if (_config.value.wallpaperShuffle && pool.size > 1) {
            pool.filter { it != current }.random()
        } else {
            // The one after the current picture in name order (the first if there's none yet).
            // Found by name, since the current one may not be in this part of the day's photos.
            val after = current?.name?.lowercase()
            pool.firstOrNull { after != null && it.name.lowercase() > after } ?: pool.first()
        }
    }

    private suspend fun showNextWallpaper(fadeMillis: Int = FADE_SLOW_MS) {
        val files = withContext(Dispatchers.IO) { wallpapers.list() }
        if (files.isEmpty()) {
            wallpaperFile = null
            _wallpaper.value = WallpaperFrame(null, fadeMillis)
            return
        }
        val current = wallpaperFile
        val next = pickNextWallpaper(wallpaperPoolNow(files)) ?: return
        if (next == current && _wallpaper.value.image != null) return
        if (current != null) rememberShown(current)
        showWallpaper(next, files.size, fadeMillis)
    }

    private suspend fun showPreviousWallpaper() {
        val files = withContext(Dispatchers.IO) { wallpapers.list() }
        val pool = wallpaperPoolNow(files)
        val current = wallpaperFile
        while (wallpaperHistory.isNotEmpty()) {
            val f = wallpaperHistory.removeLast()
            if (f != current && f in pool) return showWallpaper(f, files.size)
        }
        // Nothing remembered yet: the one before in name order.
        if (pool.size > 1) {
            val before = current?.name?.lowercase()
            showWallpaper(pool.lastOrNull { before != null && it.name.lowercase() < before } ?: pool.last(), files.size)
        }
    }

    /**
     * Shows the next (or previous) picture now and restarts the timer, so a picture someone
     * picked doesn't change again a moment later. Returns false when there's nothing to switch to.
     */
    fun skipWallpaper(forward: Boolean): Boolean {
        if (wallpaperPoolNow(wallpapers.list()).size < 2) return false
        wallpaperJob?.cancel()
        viewModelScope.launch {
            if (forward) showNextWallpaper() else showPreviousWallpaper()
            Log.i("Wallpaper changed by hand (${if (forward) "next" else "previous"})")
            if (wallpaperWanted) startWallpaper()
        }
        return true
    }

    private suspend fun decodeWallpaper(file: File): ImageBitmap? {
        val metrics = getApplication<Application>().resources.displayMetrics
        return withContext(Dispatchers.IO) {
            runCatching { wallpapers.decode(file, metrics.widthPixels, metrics.heightPixels) }
                .onFailure { Log.e("Couldn't decode wallpaper ${file.name}", it) }
                .getOrNull()
        }
    }

    private suspend fun showWallpaper(next: File, count: Int, fadeMillis: Int = FADE_SLOW_MS) {
        val started = android.os.SystemClock.uptimeMillis()
        val bitmap = decodeWallpaper(next) ?: return
        Log.i("Wallpaper: ${next.name} ($count in folder, decoded in ${android.os.SystemClock.uptimeMillis() - started} ms, fade $fadeMillis ms)")
        wallpaperFile = next
        _wallpaper.value = WallpaperFrame(bitmap, fadeMillis)
        markWallpaperChanged()
    }

    private fun markWallpaperChanged(at: Long = System.currentTimeMillis()) {
        wallpaperChangedAt = at
        wallpaperState.edit().putString("file", wallpaperFile?.name).putLong("changedAt", at).apply()
    }

    /** After a process restart, put back the picture that was up (keeping its timer). */
    private suspend fun restoreWallpaper(fadeMillis: Int): Boolean {
        val name = wallpaperState.getString("file", null) ?: return false
        val files = withContext(Dispatchers.IO) { wallpapers.list() }
        val file = files.firstOrNull { it.name == name } ?: return false
        val changedAt = wallpaperChangedAt
        val intervalMs = _config.value.wallpaperIntervalSeconds * 1000L
        if (intervalMs > 0 && System.currentTimeMillis() - changedAt !in 0 until intervalMs) {
            // Overdue: don't load it just to replace it; the next one follows it in order.
            wallpaperFile = file
            Log.i("Wallpaper ${file.name} was due to change while we were gone")
            return true
        }
        showWallpaper(file, files.size, fadeMillis)
        if (wallpaperFile != file) return false
        markWallpaperChanged(changedAt)
        Log.i("Wallpaper restored after restart; up for ${(System.currentTimeMillis() - changedAt) / 60_000} min")
        return true
    }

    private fun refreshApps() {
        viewModelScope.launch {
            _installed.value = withContext(Dispatchers.IO) { repo.loadApps() }
            Log.i("App list changed: ${_installed.value.size} apps")
            if (_ready.value) syncInstalled()
        }
    }

    private fun syncInstalled() {
        val before = _config.value.sections.sumOf { it.tiles.size }
        edit { it.syncInstalled(_installed.value) }
        val added = _config.value.sections.sumOf { it.tiles.size } - before
        if (added > 0) Log.i("Added $added newly installed app(s) to the home screen")
        removeUninstalledTiles()
    }

    /**
     * Takes uninstalled apps off the home screen. The launchable-app list alone isn't trusted for
     * this: during an update, or early at boot, an app can be missing from it for a moment. So
     * each missing app is checked with the package manager and removed only if it's really gone.
     * (Disabled apps are still installed, so they stay, hidden, and come back when re-enabled.)
     */
    private fun removeUninstalledTiles() {
        val installed = _installed.value
        val missing = _config.value.sections.flatMap { s -> s.tiles.map { it.packageName } }
            .filterTo(HashSet()) { it !in installed }
        val gone = missing.filterTo(HashSet()) { !isPackageInstalled(it) }
        if (gone.isEmpty()) return
        edit { it.removeTilesFor(gone) }
        Log.i("Removed uninstalled app(s) from the home screen: ${gone.joinToString()}")
    }

    private fun isPackageInstalled(packageName: String): Boolean = try {
        getApplication<Application>().packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    suspend fun loadArt(app: AppInfo): AppArt? = withContext(Dispatchers.IO) { repo.loadArt(app) }

    fun cachedArt(app: AppInfo): AppArt? = repo.cachedArt(app)

    fun launch(context: Context, packageName: String) {
        val intent = repo.launchIntent(packageName)
        if (intent == null) {
            Log.w("No launch intent for $packageName")
            Toast.makeText(context, "Can't open $packageName", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            Log.i("Launching ${intent.component?.flattenToShortString() ?: packageName}")
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // ActivityNotFound, or SecurityException for apps that won't start from outside.
            Log.e("Launching $packageName failed", e)
            Toast.makeText(context, "Can't open $packageName", Toast.LENGTH_SHORT).show()
        }
    }

    fun openSystemSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No settings app found", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Saving -------------------------------------------------------------------------------

    /** Debounced, so holding a D-pad direction while resizing doesn't write the file 20×/s. */
    private fun scheduleSave(config: LauncherConfig) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch(saveDispatcher) {
            delay(400)
            trySave(config)
        }
    }

    /** Writes any pending change right away (called when the launcher goes to the background). */
    fun saveNow() {
        if (saveJob?.isActive == true) {
            saveJob?.cancel()
            trySave(_config.value)
        }
    }

    private fun trySave(config: LauncherConfig) {
        try {
            store.save(config)
        } catch (e: Exception) {
            Log.e("Saving the layout failed", e)
        }
    }

    private fun edit(transform: (LauncherConfig) -> LauncherConfig) {
        val before = _config.value
        val after = transform(before)
        if (after == before) return
        _config.value = after
        scheduleSave(after)
    }

    // --- Photos from a phone --------------------------------------------------------------------

    data class UploadSession(val url: String, val code: String, val received: Int = 0)

    private var uploadServer: PhotoUploadServer? = null
    private val _upload = MutableStateFlow<UploadSession?>(null)
    /** Non-null while the "send photos from your phone" screen is up. */
    val upload: StateFlow<UploadSession?> = _upload.asStateFlow()

    sealed interface UploadStart {
        data object Started : UploadStart
        data object Blocked : UploadStart
        data object NoNetwork : UploadStart
    }

    fun startPhotoUpload(): UploadStart {
        stopPhotoUpload()
        if (internetBlockedForUs()) return UploadStart.Blocked
        val address = lanAddress() ?: return UploadStart.NoNetwork
        val folder = wallpapers.folder() ?: return UploadStart.NoNetwork
        val code = (1000..9999).random().toString()
        for (port in 8765..8775) {
            val server = PhotoUploadServer(
                port = port,
                folder = folder,
                code = code,
                onAdded = { file, parts ->
                    viewModelScope.launch {
                        if (parts.isNotEmpty()) setPhotoTimes(file.name, parts)
                        _upload.value = _upload.value?.let { it.copy(received = it.received + 1) }
                        // Show it straight away, if it belongs to this part of the day.
                        val files = wallpapers.list()
                        if (file in wallpaperPoolNow(files)) showWallpaper(file, files.size)
                    }
                },
                onRemoved = { name ->
                    viewModelScope.launch {
                        setPhotoTimes(name, emptySet())
                        if (wallpaperFile?.exists() != true) showNextWallpaper()
                    }
                },
                timesOf = { name -> _config.value.photoTimes[name]?.parts.orEmpty() },
                onTimesChanged = { name, parts -> viewModelScope.launch { setPhotoTimes(name, parts) } },
                currentPart = { dayPart() },
            )
            try {
                server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            } catch (e: java.io.IOException) {
                continue // port in use
            }
            uploadServer = server
            _upload.value = UploadSession(url = "http://$address:$port", code = code)
            Log.i("Photo upload page open at http://$address:$port")
            return UploadStart.Started
        }
        return UploadStart.NoNetwork
    }

    fun stopPhotoUpload() {
        uploadServer?.stop()
        if (uploadServer != null) Log.i("Photo upload page closed")
        uploadServer = null
        _upload.value = null
    }

    /** This TV's IPv4 address on the current network, for the phone to connect to. */
    private fun lanAddress(): String? = runCatching {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    fun wallpaperCount(): Int = wallpapers.list().size

    /** Every wallpaper photo, in name order (for the Times of Day photo grid). */
    fun wallpaperFiles(): List<File> = wallpapers.list()

    /** A small decoded copy of [file] for the photo grid. */
    suspend fun wallpaperThumbnail(file: File): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching { wallpapers.decode(file, 320, 180) }.getOrNull()
    }

    /**
     * Sets when [name] shows (empty = any time). If that takes the picture on screen out of the
     * current part of the day, the rotation moves on within a minute; we nudge it right away.
     */
    fun setPhotoTimes(name: String, parts: Set<DayPart>) {
        if (_config.value.photoTimes[name]?.parts.orEmpty() == parts) return
        edit { it.copy(photoTimes = it.photoTimes.withParts(name, parts)) }
        Log.i("Photo times: $name -> ${PhotoSchedule(parts).describe()}")
        restartWallpaper()
    }

    fun isOurScreensaver(): Boolean = runCatching {
        val app = getApplication<Application>()
        Settings.Secure.getString(app.contentResolver, "screensaver_components")
            ?.contains("${app.packageName}/") == true
    }.getOrDefault(false)

    fun openScreensaverSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w("No screensaver settings screen on this device")
            openSystemSettings(context)
        }
    }

    /** Shows the screensaver slideshow full screen now (works on every TV, unlike "dream now"). */
    fun previewScreensaver(context: Context) {
        context.startActivity(Intent(context, ScreensaverPreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun removeAllWallpapers() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { wallpapers.list().forEach { it.delete() } }
            edit { it.copy(photoTimes = emptyMap()) }
            Log.i("Removed all wallpapers")
            showNextWallpaper()
        }
    }

    // --- Diagnostics --------------------------------------------------------------------------

    /** What the Home button currently opens: our package if we're the default home app. */
    fun homeAppPackage(): String? = runCatching {
        getApplication<Application>().packageManager
            .resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }.getOrNull()

    fun isDefaultHome(): Boolean = homeAppPackage() == getApplication<Application>().packageName

    /**
     * The system's own "make this your home app?" confirmation (RoleManager, Android 10+), or the
     * home-app picker on older versions.
     */
    fun homeRequestIntent(): Intent {
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = app.getSystemService(android.app.role.RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                return roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME)
            }
        }
        return Intent(Settings.ACTION_HOME_SETTINGS)
    }

    /** The full home-app chooser, for switching away from us (Android's Default home app screen). */
    fun homeChooserIntent(): Intent = Intent(Settings.ACTION_HOME_SETTINGS)

    /** Name of the app the Home button opens, for Settings → Home App. */
    fun homeAppLabel(): String {
        val app = getApplication<Application>()
        return when (val home = homeAppPackage()) {
            app.packageName -> "Waffle"
            null -> "Unknown"
            "android" -> "Not chosen"
            else -> _installed.value[home]?.label ?: runCatching {
                app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(home, 0)).toString()
            }.getOrDefault(home)
        }
    }

    /** Label/value rows for Settings → About → Diagnostics. */
    fun diagnostics(): List<Pair<String, String>> {
        val app = getApplication<Application>()
        val home = homeAppPackage()
        val homeText = when (home) {
            app.packageName -> "This launcher"
            null -> "Unknown"
            "android" -> "Not chosen (Android asks)"
            else -> _installed.value[home]?.label ?: home
        }
        val c = _config.value
        return listOf(
            "Home Button Opens" to homeText,
            "Launcher Version" to LauncherApp.versionLine(app),
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Apps Found" to "${_installed.value.size} (${_installed.value.values.count { it.isTv }} TV)",
            "Layout" to "${c.sections.size} sections, ${c.sections.sumOf { it.tiles.size }} tiles",
            "Wallpapers" to "${wallpapers.list().size} in folder",
            "Time of Day" to timeOfDayDiagnostic(c.weatherPlace),
            "Internet Access" to if (internetBlockedForUs()) "Blocked by Android" else "Allowed",
        )
    }

    private fun timeOfDayDiagnostic(place: WeatherPlace?): String {
        val part = dayPart().label
        if (place == null) return "$part (clock hours; no location)"
        val sun = SunClock.elevation(System.currentTimeMillis(), place.latitude, place.longitude)
        return "$part (sun ${"%.1f".format(sun)}°, ${place.name})"
    }

    fun lastCrash(): String? = LauncherApp.crashFile(getApplication()).takeIf { it.exists() }?.readText()

    fun clearLastCrash() {
        LauncherApp.crashFile(getApplication()).delete()
    }

    // --- Settings -----------------------------------------------------------------------------

    /** For the settings menus: any change to the non-layout fields of the config. */
    fun updateSettings(transform: (LauncherConfig) -> LauncherConfig) {
        val before = _config.value
        edit(transform)
        val after = _config.value
        if (after.wallpaperIntervalSeconds != before.wallpaperIntervalSeconds) restartWallpaper()
        if (after.weatherPlace != before.weatherPlace || after.temperatureUnit != before.temperatureUnit ||
            after.weatherEnabled != before.weatherEnabled
        ) {
            _weather.value = null
            viewModelScope.launch { refreshWeather() }
        }
    }

    /** The system page for an app (force stop, storage, uninstall). */
    fun openAppInfo(context: Context, packageName: String) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "App info isn't available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    fun openWifiSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            openSystemSettings(context)
        }
    }

    // --- Sections -----------------------------------------------------------------------------

    /** Returns the new section's id. */
    fun addSection(title: String): String {
        val section = Section(title = title)
        edit { it.copy(sections = it.sections + section) }
        return section.id
    }

    fun setSectionLayout(sectionId: String, columns: Int? = null, shortRows: RowAlign? = null) =
        edit {
            it.mapSection(sectionId) { s ->
                s.copy(columns = columns ?: s.columns, shortRows = shortRows ?: s.shortRows)
            }
        }

    fun renameSection(sectionId: String, title: String) =
        edit { it.mapSection(sectionId) { s -> s.copy(title = title) } }

    /** Returns the id of a neighbouring section to move focus to, if there is one. */
    fun deleteSection(sectionId: String): String? {
        val sections = _config.value.sections
        val index = sections.indexOfFirst { it.id == sectionId }
        val neighbour = sections.getOrNull(index + 1) ?: sections.getOrNull(index - 1)
        edit { it.copy(sections = it.sections.filterNot { s -> s.id == sectionId }) }
        return neighbour?.id
    }

    fun moveSection(sectionId: String, delta: Int) = edit { it.moveSection(sectionId, delta) }

    // --- Tiles --------------------------------------------------------------------------------

    /** Returns the new tile's id. */
    fun addTile(sectionId: String, packageName: String): String {
        val tile = Tile(packageName = packageName)
        edit { it.mapSection(sectionId) { s -> s.copy(tiles = s.tiles + tile) } }
        return tile.id
    }

    fun replaceTileApp(tileId: String, packageName: String) =
        edit { it.mapTile(tileId) { t -> t.copy(packageName = packageName) } }

    /** Returns the id of a neighbouring tile to move focus to, if there is one. */
    fun removeTile(tileId: String): String? {
        val section = _config.value.sections.firstOrNull { s -> s.tiles.any { it.id == tileId } } ?: return null
        val index = section.tiles.indexOfFirst { it.id == tileId }
        val neighbour = section.tiles.getOrNull(index + 1) ?: section.tiles.getOrNull(index - 1)
        edit { it.mapSection(section.id) { s -> s.copy(tiles = s.tiles.filterNot { t -> t.id == tileId }) } }
        Log.i("Removed ${section.tiles[index].packageName} from the home screen (by hand)")
        return neighbour?.id
    }


    /** One step on screen; see [com.barrettcoats.waffle.data.moveTile]. */
    fun moveTile(tileId: String, direction: MoveDirection, columns: Int) =
        edit { it.moveTile(tileId, direction, columns) }

    /** Appends the tile to another section (taking on that section's tile size). */
    fun moveTileToSection(tileId: String, sectionId: String) = edit { cfg ->
        cfg.moveTileToSection(tileId, cfg.sections.indexOfFirst { it.id == sectionId })
    }
}

private const val WEATHER_REFRESH_MS = 30 * 60 * 1000L

/** The normal fade between pictures while the home screen is in view. */
private const val FADE_SLOW_MS = 2500
/** Coming back to a change we couldn't get ready in time: quick, so it reads as settling in, not a glitch. */
private const val FADE_QUICK_MS = 400

/**
 * The wallpaper to show ([image] null is the built-in sky gradient) and how long to fade it in
 * over what's there; 0 swaps it at once. A class, not a data class, so showing the same picture
 * again still counts as a new frame.
 */
class WallpaperFrame(val image: ImageBitmap?, val fadeMillis: Int)
