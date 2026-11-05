package com.barrettcoats.waffle.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The current time, updated each minute (and when the clock or time zone changes). */
@Composable
fun rememberNow(): State<Long> {
    val context = LocalContext.current
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                now.longValue = System.currentTimeMillis()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    // Ticks stop while the screen is off; catch up as soon as we're visible again.
    LifecycleResumeEffect(Unit) {
        now.longValue = System.currentTimeMillis()
        onPauseOrDispose { }
    }
    return now
}

private val OnWallStyle = TextStyle(fontFamily = Inter, shadow = WallShadow)

/** The big time at the top left (12- or 24-hour, following the system setting). */
@Composable
fun TimeText(now: Long) {
    Text(
        DateFormat.getTimeFormat(LocalContext.current).format(Date(now)),
        color = Palette.OnWall,
        fontSize = 44.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5).sp,
        style = OnWallStyle,
    )
}

/** "Wednesday, November 4" in the device's language and order. */
@Composable
fun DateText(now: Long) {
    val locale = Locale.getDefault()
    Text(
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale).format(Date(now)),
        color = Palette.OnWallMuted,
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        style = OnWallStyle,
    )
}

private sealed interface NetState {
    data object Offline : NetState
    data class Ethernet(val internet: Boolean) : NetState
    /** [level] is 0..3 (how many arcs are lit). */
    data class Wifi(val level: Int, val internet: Boolean) : NetState
    data class Other(val internet: Boolean) : NetState
}

@Composable
private fun rememberNetState(): State<NetState> {
    val context = LocalContext.current.applicationContext
    val state = remember { mutableStateOf<NetState>(NetState.Offline) }
    DisposableEffect(context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val wifi = context.getSystemService(WifiManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                state.value = caps.toNetState(wifi)
            }

            override fun onLost(network: Network) {
                state.value = NetState.Offline
            }
        }
        cm.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { cm.unregisterNetworkCallback(callback) }
    }
    return state
}

private fun NetworkCapabilities.toNetState(wifi: WifiManager): NetState {
    val internet = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    return when {
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetState.Ethernet(internet)
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
            val rssi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                signalStrength
            } else {
                @Suppress("DEPRECATION")
                wifi.connectionInfo.rssi
            }
            NetState.Wifi(rssiToLevel(rssi), internet)
        }
        else -> NetState.Other(internet)
    }
}

private fun rssiToLevel(rssi: Int): Int = when {
    rssi == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED -> 3
    rssi >= -60 -> 3
    rssi >= -70 -> 2
    rssi >= -80 -> 1
    else -> 0
}

private val NoInternet = Color(0xFFFFC145)
private val Dim = Color.White.copy(alpha = 0.35f)
private val IconShadow = Color.Black.copy(alpha = 0.35f)

/** Wi-Fi arcs, an Ethernet glyph, or crossed-out Wi-Fi when offline. Amber means no internet. */
@Composable
fun NetworkIndicator() {
    val net by rememberNetState()
    val tint = LocalContentColor.current
    val light = tint.luminance() > 0.5f
    Canvas(Modifier.size(25.dp, 20.dp)) {
        // A soft offset copy underneath, like the text shadows, so it reads on bright skies.
        if (light) translate(top = 1.5.dp.toPx()) { drawNet(net, tint, shadow = true) }
        drawNet(net, tint, shadow = false)
    }
}

private fun DrawScope.drawNet(net: NetState, tint: Color, shadow: Boolean) {
    fun c(color: Color) = when {
        shadow -> IconShadow
        color == Palette.OnWall -> tint
        color == Dim -> tint.copy(alpha = 0.35f)
        else -> color
    }
    when (net) {
        NetState.Offline -> {
            drawWifi(level = -1, on = c(Palette.OnWall), off = c(Dim))
            drawLine(
                c(NoInternet),
                Offset(2.dp.toPx(), 1.dp.toPx()),
                Offset(size.width - 2.dp.toPx(), size.height - 1.dp.toPx()),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        is NetState.Wifi -> drawWifi(net.level, on = c(if (net.internet) Palette.OnWall else NoInternet), off = c(Dim))
        is NetState.Ethernet -> drawEthernet(c(if (net.internet) Palette.OnWall else NoInternet))
        is NetState.Other -> drawEthernet(c(if (net.internet) Palette.OnWall else NoInternet))
    }
}

private fun DrawScope.drawWifi(level: Int, on: Color, off: Color) {
    val stroke = 2.5.dp.toPx()
    val dot = 4.dp.toPx()
    val cx = size.width / 2
    val cy = size.height - dot / 2
    drawCircle(if (level >= 0) on else off, radius = dot / 2, center = Offset(cx, cy))
    val maxRadius = cy - stroke / 2
    for (i in 1..3) {
        val r = maxRadius * (i + 0.6f) / 3.6f
        drawArc(
            color = if (i <= level) on else off,
            startAngle = -135f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2, r * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** A small "LAN" glyph: one box on top, wired down to three boxes. */
private fun DrawScope.drawEthernet(color: Color) {
    val w = size.width
    val h = size.height
    val line = 2.dp.toPx()
    val top = Size(9.dp.toPx(), 6.dp.toPx())
    val leaf = Size(6.dp.toPx(), 5.dp.toPx())
    drawRect(color, topLeft = Offset((w - top.width) / 2, 0f), size = top)
    val busY = h / 2
    drawLine(color, Offset(w / 2, top.height), Offset(w / 2, busY), line)
    val xs = listOf(leaf.width / 2, w / 2, w - leaf.width / 2)
    drawLine(color, Offset(xs.first(), busY), Offset(xs.last(), busY), line)
    for (x in xs) {
        drawLine(color, Offset(x, busY), Offset(x, h - leaf.height), line)
        drawRect(color, topLeft = Offset(x - leaf.width / 2, h - leaf.height), size = leaf)
    }
}
