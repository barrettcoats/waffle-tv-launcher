package com.barrettcoats.waffle.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.barrettcoats.waffle.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

data class AppInfo(
    val packageName: String,
    val label: String,
    /** Has a LEANBACK_LAUNCHER entry, i.e. it's a real TV app rather than a phone app. */
    val isTv: Boolean,
    /** Changes when the app is updated, so its artwork gets reloaded. */
    val updated: Long,
)

class AppArt(val bitmap: ImageBitmap, val isBanner: Boolean)

class AppRepository(private val context: Context) {
    private val pm: PackageManager = context.packageManager
    private val artCache = ConcurrentHashMap<String, AppArt>()

    /** Every launchable app except this one, TV apps first, each group sorted by name. */
    fun loadApps(): Map<String, AppInfo> {
        val found = LinkedHashMap<String, AppInfo>()
        fun collect(category: String, isTv: Boolean) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(intent, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg == context.packageName || pkg in found) continue
                val updated = try {
                    pm.getPackageInfo(pkg, 0).lastUpdateTime
                } catch (e: PackageManager.NameNotFoundException) {
                    0L
                }
                found[pkg] = AppInfo(pkg, info.loadLabel(pm).toString(), isTv, updated)
            }
        }
        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, isTv = true)
        collect(Intent.CATEGORY_LAUNCHER, isTv = false)
        return found.values
            .sortedWith(compareBy<AppInfo> { !it.isTv }.thenBy { it.label.lowercase() })
            .associateBy { it.packageName }
    }

    fun launchIntent(packageName: String): Intent? =
        pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName)

    private fun AppInfo.cacheKey() = "$packageName@$updated"

    fun cachedArt(app: AppInfo): AppArt? = artCache[app.cacheKey()]

    /** The app's TV banner if it has one, otherwise its icon. Blocking; call off the main thread. */
    fun loadArt(app: AppInfo): AppArt? {
        val key = app.cacheKey()
        artCache[key]?.let { return it }
        val art = try {
            val intent = launchIntent(app.packageName)
            val banner = intent?.let { runCatching { pm.getActivityBanner(it) }.getOrNull() }
                ?: runCatching { pm.getApplicationBanner(app.packageName) }.getOrNull()
            if (banner != null) {
                AppArt(banner.render(640, 360), isBanner = true)
            } else {
                val icon = intent?.let { runCatching { pm.getActivityIcon(it) }.getOrNull() }
                    ?: pm.getApplicationIcon(app.packageName)
                AppArt(icon.render(192, 192), isBanner = false)
            }
        } catch (e: Exception) {
            Log.w("No artwork for ${app.packageName}", e)
            null
        }
        if (art != null) artCache[key] = art
        return art
    }

    private fun Drawable.render(fallbackWidth: Int, fallbackHeight: Int): ImageBitmap {
        val w = intrinsicWidth.takeIf { it > 0 } ?: fallbackWidth
        val h = intrinsicHeight.takeIf { it > 0 } ?: fallbackHeight
        return toBitmap(w, h).asImageBitmap()
    }
}
