package com.barrettcoats.waffle

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.barrettcoats.waffle.ui.LauncherScreen

class MainActivity : ComponentActivity() {
    private val viewModel: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LauncherScreen(viewModel) }
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
    }

    override fun onStart() {
        super.onStart()
        viewModel.startWallpaper()
        viewModel.startWeather()
    }

    override fun onStop() {
        super.onStop()
        hiddenSinceResume = true
        viewModel.stopWallpaper()
        viewModel.stopWeather()
        viewModel.stopPhotoUpload() // never leave the upload page reachable once we're not on screen
        viewModel.saveNow()
    }
}
