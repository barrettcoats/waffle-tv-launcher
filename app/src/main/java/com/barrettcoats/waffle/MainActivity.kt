package com.barrettcoats.waffle

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.barrettcoats.waffle.ui.LauncherScreen

class MainActivity : ComponentActivity() {
    private val viewModel: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LauncherScreen(viewModel) }
        window.decorView.viewTreeObserver.addOnDrawListener(drawListener)
    }

    override fun onDestroy() {
        cancelDrawCheck()
        window.decorView.viewTreeObserver.let { if (it.isAlive) it.removeOnDrawListener(drawListener) }
        super.onDestroy()
    }

    /** True once another app has covered the home screen since it was last shown. */
    private var hiddenSinceResume = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home is delivered here every time (Android pauses us briefly first, even when we're in
        // front). Coming back from an app we keep the focused tile; pressing Home while already
        // here resets to the top.
        if (!hiddenSinceResume) viewModel.onHomePressedWhileVisible()
    }

    override fun onResume() {
        super.onResume()
        hiddenSinceResume = false
        scheduleDrawCheck()
    }

    override fun onStart() {
        super.onStart()
        viewModel.startWallpaper(cameBack = true)
        viewModel.startWeather()
    }

    override fun onStop() {
        super.onStop()
        hiddenSinceResume = true
        cancelDrawCheck()
        viewModel.stopWallpaper()
        viewModel.stopWeather()
        viewModel.stopPhotoUpload() // never leave the upload page reachable once we're not on screen
        viewModel.saveNow()
    }

    // Stuck-screen guard. Android 15 holds back display notices for apps it considers cached
    // (a launcher behind another app is one) and delivers them when the app comes forward. If
    // the Shield is put to sleep with the power button while our screensaver is showing, we hear
    // "display off" right away (the screensaver makes the whole app foreground; moving it to
    // its own process doesn't help, since Android decides per app, not per process), but the
    // "display on" at wake is held back and never arrives. The window then believes the screen
    // is still off and skips every draw: the remote still moves focus, but the picture stays
    // frozen until the app is restarted. (Android still forces the one draw it needs to show
    // the window, so the first frame after Home looks right; every later one is lost.)
    //
    // So shortly after resuming we ask for a fresh draw, and if none happens while the power
    // service says the screen is on, we start ourselves over in a new window, which reads the
    // true display state. recreate() isn't enough: it keeps the same (stuck) window.
    private var lastDrawAt = 0L
    private var checkStartedAt = 0L
    private val drawListener = ViewTreeObserver.OnDrawListener { lastDrawAt = SystemClock.uptimeMillis() }
    private val requestDraw = Runnable {
        checkStartedAt = SystemClock.uptimeMillis()
        window.decorView.invalidate()
        window.decorView.postDelayed(drawCheck, 700)
    }
    private val drawCheck = Runnable {
        if (lastDrawAt >= checkStartedAt || isFinishing) return@Runnable
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive == true
        if (!interactive) return@Runnable
        val now = SystemClock.uptimeMillis()
        if (lastRestartAt != 0L && now - lastRestartAt < 30_000) {
            Log.w("Home screen still isn't drawing after a restart; leaving it alone")
            return@Runnable
        }
        lastRestartAt = now
        Log.w("Home screen is stuck not drawing (display on, no frame); restarting it in a new window")
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle(),
        )
    }

    private fun scheduleDrawCheck() {
        cancelDrawCheck()
        window.decorView.postDelayed(requestDraw, 1000)
    }

    private fun cancelDrawCheck() {
        window.decorView.removeCallbacks(requestDraw)
        window.decorView.removeCallbacks(drawCheck)
    }

    private companion object {
        /** When the guard last restarted the screen, so a screen that stays stuck can't loop. */
        var lastRestartAt = 0L
    }
}
