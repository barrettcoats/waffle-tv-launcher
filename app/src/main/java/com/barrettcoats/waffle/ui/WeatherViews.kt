package com.barrettcoats.waffle.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.barrettcoats.waffle.data.Sky
import com.barrettcoats.waffle.data.WeatherReport
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

private val SunColor = Color(0xFFFFC53D)
private val MoonColor = Color(0xFFF2E8C4)
private val CloudColor = Color.White
private val DarkCloudColor = Color(0xFFD5DCE4)
private val RainColor = Color(0xFF7CC4FF)
private val IconShadow = Color.Black.copy(alpha = 0.3f)

/** A small, friendly weather picture drawn in code (no image assets, any size). */
@Composable
fun WeatherIcon(sky: Sky, isDay: Boolean, size: Dp, modifier: Modifier = Modifier, shadow: Boolean = true) {
    Canvas(modifier.size(size)) {
        if (shadow) translate(top = size.toPx() * 0.04f) { drawWeather(sky, isDay, tint = IconShadow) }
        drawWeather(sky, isDay, tint = null)
    }
}

/** [tint] non-null draws everything in that one colour (used for the shadow pass). */
private fun DrawScope.drawWeather(sky: Sky, isDay: Boolean, tint: Color?) {
    val s = size.minDimension
    fun c(color: Color) = tint ?: color
    when (sky) {
        Sky.CLEAR -> if (isDay) drawSun(Offset(s * 0.5f, s * 0.5f), s * 0.2f, c(SunColor)) else drawMoon(s, c(MoonColor))
        Sky.PARTLY_CLOUDY -> {
            if (isDay) drawSun(Offset(s * 0.36f, s * 0.36f), s * 0.15f, c(SunColor))
            else drawMoon(s * 0.7f, c(MoonColor))
            drawCloud(s, dy = s * 0.08f, color = c(CloudColor))
        }
        Sky.CLOUDY -> drawCloud(s, dy = 0f, color = c(CloudColor))
        Sky.FOG -> for (i in 0..2) {
            val y = s * (0.38f + i * 0.14f)
            drawLine(c(CloudColor), Offset(s * (0.18f + i * 0.05f), y), Offset(s * (0.82f - i * 0.03f), y), s * 0.07f, StrokeCap.Round)
        }
        Sky.DRIZZLE, Sky.RAIN -> {
            drawCloud(s, dy = -s * 0.1f, color = c(DarkCloudColor))
            val long = sky == Sky.RAIN
            for (i in 0..2) {
                val x = s * (0.35f + i * 0.15f)
                drawLine(
                    c(RainColor),
                    Offset(x, s * 0.7f),
                    Offset(x - s * 0.05f, s * (if (long) 0.88f else 0.78f)),
                    s * 0.06f,
                    StrokeCap.Round,
                )
            }
        }
        Sky.SNOW -> {
            drawCloud(s, dy = -s * 0.1f, color = c(DarkCloudColor))
            for (i in 0..2) drawCircle(c(CloudColor), s * 0.045f, Offset(s * (0.34f + i * 0.16f), s * (0.76f + (i % 2) * 0.08f)))
        }
        Sky.STORM -> {
            drawCloud(s, dy = -s * 0.12f, color = c(DarkCloudColor))
            val bolt = Path().apply {
                moveTo(s * 0.54f, s * 0.6f)
                lineTo(s * 0.42f, s * 0.78f)
                lineTo(s * 0.52f, s * 0.78f)
                lineTo(s * 0.46f, s * 0.94f)
                lineTo(s * 0.62f, s * 0.72f)
                lineTo(s * 0.52f, s * 0.72f)
                lineTo(s * 0.58f, s * 0.6f)
                close()
            }
            drawPath(bolt, c(SunColor))
        }
    }
}

private fun DrawScope.drawSun(center: Offset, radius: Float, color: Color) {
    drawCircle(color, radius, center)
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val dir = Offset(Math.cos(a).toFloat(), Math.sin(a).toFloat())
        drawLine(color, center + dir * (radius * 1.45f), center + dir * (radius * 1.95f), radius * 0.32f, StrokeCap.Round)
    }
}

private fun DrawScope.drawMoon(s: Float, color: Color) {
    val outer = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(s * 0.5f, s * 0.5f), s * 0.26f)) }
    val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(s * 0.62f, s * 0.4f), s * 0.22f)) }
    drawPath(Path.combine(PathOperation.Difference, outer, bite), color)
}

/** Three puffs on a flat base. */
private fun DrawScope.drawCloud(s: Float, dy: Float, color: Color) {
    drawCircle(color, s * 0.15f, Offset(s * 0.34f, s * 0.6f + dy))
    drawCircle(color, s * 0.21f, Offset(s * 0.53f, s * 0.5f + dy))
    drawCircle(color, s * 0.14f, Offset(s * 0.7f, s * 0.6f + dy))
    drawRoundRect(
        color,
        topLeft = Offset(s * 0.2f, s * 0.58f + dy),
        size = Size(s * 0.64f, s * 0.16f),
        cornerRadius = CornerRadius(s * 0.08f),
    )
}

private val ShadowText = TextStyle(fontFamily = Inter, shadow = WallShadow)

/**
 * Weather on the date line: a small icon and the temperature. Before a place is chosen it's just
 * the icon (selecting it starts setup), so the header stays quiet.
 */
@Composable
fun WeatherChip(report: WeatherReport?, configured: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FlatSurface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        containerColor = Color.Transparent,
        focusedContainerColor = Palette.GlassFocused,
        focusedBorderColor = Palette.Focus,
        focusedBorderWidth = 2.dp,
        focusedScale = 1.08f,
        label = when {
            report != null -> "Weather: ${report.temperature} degrees, ${report.sky.label}"
            configured -> "Weather"
            else -> "Set up weather"
        },
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val sky = report?.sky ?: Sky.PARTLY_CLOUDY
            WeatherIcon(sky, report?.isDay ?: true, 26.dp, Modifier.alpha(if (report == null) 0.8f else 1f))
            if (report != null) {
                Spacer(Modifier.width(6.dp))
                Text("${report.temperature}°", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, style = ShadowText)
            }
        }
    }
}

/** The forecast sheet: now, then the next days. */
@Composable
fun ForecastDialog(report: WeatherReport?, placeName: String, onChangePlace: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember { FocusRequester() }
        val shape = LocalSheetShape.current
        Column(
            Modifier
                .ignorePressFromBefore()
                .width(700.dp)
                .background(Palette.Sheet, shape)
                .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
                .padding(horizontal = 32.dp, vertical = 28.dp),
        ) {
            Text(report?.place?.name ?: placeName, color = Palette.SheetText, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            report?.place?.region?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Palette.SheetMuted, fontSize = 15.sp)
            }
            Spacer(Modifier.height(18.dp))
            if (report == null) {
                Text("Getting the forecast…", color = Palette.SheetMuted, fontSize = 18.sp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WeatherIcon(report.sky, report.isDay, 84.dp, shadow = false)
                    Spacer(Modifier.width(18.dp))
                    Column {
                        Text("${report.temperature}°", color = Palette.SheetText, fontSize = 56.sp, fontWeight = FontWeight.Light)
                        Text(report.sky.label, color = Palette.SheetMuted, fontSize = 18.sp)
                    }
                }
                Spacer(Modifier.height(22.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (day in report.days.take(6)) {
                        Column(
                            Modifier
                                .weight(1f)
                                .background(Palette.SheetItem, LocalShape.current)
                                .padding(vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(dayName(day.date), color = Palette.SheetText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            WeatherIcon(day.sky, true, 40.dp, shadow = false)
                            Spacer(Modifier.height(6.dp))
                            Text("${day.high}°", color = Palette.SheetText, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text("${day.low}°", color = Palette.SheetMuted, fontSize = 15.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SheetPill("Close", onDismiss, Modifier.focusRequester(first))
                SheetPill("Change Location", {
                    onDismiss()
                    onChangePlace()
                })
                Spacer(Modifier.weight(1f))
                Text("Weather data by Open-Meteo.com", color = Palette.SheetMuted, fontSize = 12.sp)
            }
        }
        RequestInitialFocus(first)
    }
}

@Composable
private fun SheetPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FlatSurface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        containerColor = Palette.SheetItem,
        focusedContainerColor = Palette.SheetFocused,
        contentColor = Palette.SheetText,
        focusedContentColor = Palette.SheetFocusedText,
        focusedBorderColor = null,
        focusedScale = 1.05f,
    ) {
        Text(label, Modifier.padding(horizontal = 22.dp, vertical = 10.dp), fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

private fun dayName(date: LocalDate): String =
    if (date == LocalDate.now()) "Today" else date.dayOfWeek.getDisplayName(DateTextStyle.SHORT, Locale.getDefault())
