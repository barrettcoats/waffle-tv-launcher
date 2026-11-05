package com.barrettcoats.waffle.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.darkColorScheme
import com.barrettcoats.waffle.R

/**
 * Light, friendly look meant to sit on top of a bright photo: white text with soft shadows on
 * the wallpaper, frosted dark glass for menus, white focus rings.
 */
object Palette {
    // Things drawn directly on the wallpaper.
    val OnWall = Color.White
    val OnWallMuted = Color.White.copy(alpha = 0.82f)
    val Glass = Color.White.copy(alpha = 0.20f)
    val GlassFocused = Color.White.copy(alpha = 0.34f)
    val Focus = Color.White
    /** Small warm highlight (the "editing" dot, sizes while resizing). */
    val Accent = Color(0xFFFFC145)

    // Menus and dialogs.
    val Sheet = Color(0xE6202024)
    val SheetText = Color.White
    val SheetMuted = Color.White.copy(alpha = 0.6f)
    val SheetItem = Color.White.copy(alpha = 0.07f)
    val SheetFocused = Color.White
    val SheetFocusedText = Color(0xFF1C1C1E)
    val Field = Color.Black.copy(alpha = 0.35f)

    /** Shown under the wallpaper (and instead of it when the folder is empty). */
    val SkyTop = Color(0xFF3F7FBF)
    val SkyMiddle = Color(0xFF8EC3EA)
    val SkyBottom = Color(0xFFF3DDB0)
}

val Inter = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Keeps white text readable on any part of any photo. */
val WallShadow = Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 2f), blurRadius = 12f)

/** Shape for tiles and buttons, from the config's corner radius. */
val LocalShape = staticCompositionLocalOf<Shape> { RectangleShape }

/** Slightly rounder shape for menus and dialogs. */
val LocalSheetShape = staticCompositionLocalOf<Shape> { RectangleShape }

@Composable
fun LauncherTheme(cornerRadiusDp: Int, content: @Composable () -> Unit) {
    val shape = if (cornerRadiusDp > 0) RoundedCornerShape(cornerRadiusDp.dp) else RectangleShape
    val sheetShape = if (cornerRadiusDp > 0) RoundedCornerShape((cornerRadiusDp * 1.5f).dp) else RectangleShape
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Focus,
            surface = Palette.Glass,
            onSurface = Palette.OnWall,
        ),
    ) {
        CompositionLocalProvider(
            LocalShape provides shape,
            LocalSheetShape provides sheetShape,
            LocalContentColor provides Palette.OnWall,
            LocalTextStyle provides TextStyle(fontFamily = Inter),
            content = content,
        )
    }
}

/** The one focusable building block: a fill, an optional resting border, a white ring and lift when focused. */
@Composable
fun FlatSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = LocalShape.current,
    containerColor: Color = Palette.Glass,
    focusedContainerColor: Color = Palette.GlassFocused,
    contentColor: Color = Palette.OnWall,
    focusedContentColor: Color = contentColor,
    restingBorder: Color? = null,
    focusedBorderColor: Color? = Palette.Focus,
    focusedBorderWidth: Dp = 3.dp,
    focusedScale: Float = 1f,
    lift: Boolean = false,
    /** What screen readers (and test tools) call this; otherwise its text content is used. */
    label: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val resting = restingBorder?.let { Border(BorderStroke(1.dp, it), shape = shape) } ?: Border.None
    val focusedBorder = focusedBorderColor
        ?.let { Border(BorderStroke(focusedBorderWidth, it), inset = (-1).dp, shape = shape) }
        ?: Border.None
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        // Draw the focused item above its neighbours so the scale-up isn't overlapped.
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .semantics(mergeDescendants = true) { if (label != null) contentDescription = label },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            contentColor = contentColor,
            focusedContainerColor = focusedContainerColor,
            focusedContentColor = focusedContentColor,
            pressedContainerColor = focusedContainerColor,
            pressedContentColor = focusedContentColor,
        ),
        border = ClickableSurfaceDefaults.border(
            border = resting,
            focusedBorder = focusedBorder,
            pressedBorder = focusedBorder,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale, pressedScale = focusedScale * 0.98f),
        glow = if (lift) {
            ClickableSurfaceDefaults.glow(
                focusedGlow = Glow(elevationColor = Color.Black.copy(alpha = 0.5f), elevation = 16.dp),
            )
        } else {
            ClickableSurfaceDefaults.glow()
        },
        content = content,
    )
}
