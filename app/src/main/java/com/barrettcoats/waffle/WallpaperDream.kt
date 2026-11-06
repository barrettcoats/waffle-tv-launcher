package com.barrettcoats.waffle

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.service.dreams.DreamService
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.res.ResourcesCompat
import com.barrettcoats.waffle.data.WallpaperSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import com.barrettcoats.waffle.data.ConfigStore
import com.barrettcoats.waffle.data.DayPart
import com.barrettcoats.waffle.data.LauncherConfig
import com.barrettcoats.waffle.data.dayPartNow
import com.barrettcoats.waffle.data.wallpaperPool
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The screensaver itself: just hosts [SlideshowView]. */
class WallpaperDream : DreamService() {
    private var slideshow: SlideshowView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false // any key wakes the TV back up
        isFullscreen = true
        isScreenBright = true
        slideshow = SlideshowView(this).also { setContentView(it) }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        Log.i("Screensaver started")
        slideshow?.start()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        slideshow?.stop()
    }
}

/** "Preview Now" from Settings → Wallpaper: the same slideshow full screen; any button closes it. */
class ScreensaverPreviewActivity : android.app.Activity() {
    private lateinit var slideshow: SlideshowView

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        slideshow = SlideshowView(this)
        setContentView(slideshow)
    }

    override fun onResume() {
        super.onResume()
        slideshow.start()
    }

    override fun onPause() {
        super.onPause()
        slideshow.stop()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_UP) finish()
        return true
    }
}

/**
 * The launcher's wallpaper photos as a slow slideshow (crossfade plus a gentle zoom), with a clock
 * that changes corner every few minutes so nothing burns in. With no photos it shows the sky
 * gradient and the clock.
 */
class SlideshowView(context: android.content.Context) : FrameLayout(context) {
    private val scope = MainScope()
    private var jobs = mutableListOf<Job>()
    private var front: ImageView
    private var back: ImageView
    private val clockBox: LinearLayout
    private val time: TextView
    private val date: TextView

    init {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF3F7FBF.toInt(), 0xFF8EC3EA.toInt(), 0xFFF3DDB0.toInt()),
        )
        back = photoView().also { addView(it) }
        front = photoView().also { addView(it) }
        val inter = runCatching { ResourcesCompat.getFont(context, R.font.inter_semibold) }.getOrNull()
        time = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
            typeface = inter
            setShadowLayer(18f, 0f, 3f, 0x99000000.toInt())
        }
        date = TextView(context).apply {
            setTextColor(0xD9FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = inter
            setShadowLayer(14f, 0f, 2f, 0x99000000.toInt())
        }
        clockBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(time)
            addView(date)
        }
        addView(clockBox, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private fun photoView() = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0f
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch { clockLoop() }
        jobs += scope.launch { slideshowLoop() }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
    }

    private suspend fun clockLoop() {
        val corners = listOf(
            Gravity.BOTTOM or Gravity.START,
            Gravity.TOP or Gravity.END,
            Gravity.BOTTOM or Gravity.END,
            Gravity.TOP or Gravity.START,
        )
        var tick = 0
        while (true) {
            val now = Date()
            time.text = DateFormat.getTimeFormat(context).format(now)
            date.text = SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMd"), Locale.getDefault()).format(now)
            // Move to the next corner every 5 minutes (with a fade) to avoid burn-in.
            if (tick % 10 == 0) {
                val gravity = corners[(tick / 10) % corners.size]
                clockBox.animate().alpha(0f).setDuration(800).withEndAction {
                    val margin = (resources.displayMetrics.density * 56).toInt()
                    clockBox.layoutParams = (clockBox.layoutParams as FrameLayout.LayoutParams).apply {
                        this.gravity = gravity
                        setMargins(margin, margin, margin, margin)
                    }
                    clockBox.gravity = if (gravity and Gravity.END == Gravity.END) Gravity.END else Gravity.START
                    clockBox.animate().alpha(1f).setDuration(800).start()
                }.start()
            }
            tick++
            delay(30_000)
        }
    }

    private suspend fun slideshowLoop() {
        val source = WallpaperSource(context)
        val metrics = resources.displayMetrics
        // The launcher's saved settings, for which photos belong to which time of day.
        val store = ConfigStore(java.io.File(context.filesDir, "config.json"))
        var order = emptyList<java.io.File>()
        var orderPart: DayPart? = null
        var i = 0
        while (true) {
            val config = withContext(Dispatchers.IO) { store.load() } ?: LauncherConfig()
            val part = dayPartNow(config.weatherPlace)
            // A new round of photos when this one runs out, or when it turns morning, evening...
            if (i >= order.size || part != orderPart) {
                val files = withContext(Dispatchers.IO) { source.list() }
                order = wallpaperPool(files, config.photoTimes, part).shuffled()
                orderPart = part
                i = 0
                if (order.isEmpty()) {
                    delay(60_000)
                    continue
                }
            }
            val file = order[i++]
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { source.decode(file, metrics.widthPixels, metrics.heightPixels)?.asAndroidBitmap() }.getOrNull()
            }
            if (bitmap != null) show(bitmap)
            delay(SLIDE_MS)
        }
    }

    /** Fade the next photo in over the current one, zooming slowly for the whole slide. */
    private fun show(bitmap: android.graphics.Bitmap) {
        val incoming = back
        val outgoing = front
        back = outgoing
        front = incoming
        incoming.setImageBitmap(bitmap)
        incoming.bringToFront()
        clockBox.bringToFront()
        ObjectAnimator.ofFloat(incoming, View.ALPHA, 0f, 1f).setDuration(FADE_MS).start()
        ObjectAnimator.ofPropertyValuesHolder(
            incoming,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f),
        ).apply {
            duration = SLIDE_MS + FADE_MS
            interpolator = LinearInterpolator()
        }.start()
        ObjectAnimator.ofFloat(outgoing, View.ALPHA, outgoing.alpha, 0f).apply {
            startDelay = FADE_MS
            duration = 600
        }.start()
    }

    private companion object {
        const val SLIDE_MS = 30_000L
        const val FADE_MS = 2_500L
    }
}
