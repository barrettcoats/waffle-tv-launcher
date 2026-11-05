package com.barrettcoats.waffle.ui

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.barrettcoats.waffle.data.AppArt
import com.barrettcoats.waffle.data.AppInfo

/**
 * One row of a [MenuDialog]. [value] shows the current setting on the right; [checked] marks the
 * chosen option in a list of choices; [submenu] adds a chevron. Rows with [keepOpen] act in place
 * (toggles) instead of closing the menu.
 */
data class MenuItem(
    val label: String,
    val value: String? = null,
    val checked: Boolean = false,
    val submenu: Boolean = false,
    val keepOpen: Boolean = false,
    val action: () -> Unit,
)

/**
 * Put this on a dialog's outermost layout. Holding OK on a tile opens its menu while OK is still
 * down, so the key repeats and the release land on the menu's first button, and the release
 * clicks it. This drops OK events until a fresh OK press starts inside the dialog.
 */
@Composable
fun Modifier.ignorePressFromBefore(): Modifier {
    var pressedHere by remember { mutableStateOf(false) }
    return onPreviewKeyEvent { event ->
        if (event.key != Key.DirectionCenter && event.key != Key.Enter && event.key != Key.NumPadEnter) {
            return@onPreviewKeyEvent false
        }
        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) pressedHere = true
        !pressedHere
    }
}

/** Focus [requester] once the dialog's window is up. */
@Composable
fun RequestInitialFocus(requester: FocusRequester) {
    LaunchedEffect(requester) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
}

/**
 * Artwork for [app]: taken straight from the cache when it's already loaded (so a reused slot never
 * flashes empty), otherwise loaded in the background.
 */
@Composable
fun rememberAppArt(app: AppInfo?, load: suspend (AppInfo) -> AppArt?, peek: (AppInfo) -> AppArt?): State<AppArt?> {
    val state = remember(app) { mutableStateOf(app?.let(peek)) }
    LaunchedEffect(app) {
        if (app != null && state.value == null) state.value = load(app)
    }
    return state
}

/** Frosted dark sheet that every dialog sits on. */
@Composable
private fun Sheet(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = LocalSheetShape.current
    Column(
        modifier
            .ignorePressFromBefore()
            .background(Palette.Sheet, shape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun SheetTitle(text: String, subtitle: String? = null) {
    Column(Modifier.padding(start = 4.dp, bottom = 10.dp)) {
        Text(
            text,
            color = Palette.SheetText,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Palette.SheetMuted, fontSize = 15.sp)
        }
    }
}

/** A menu row: a quiet translucent pill that turns solid white with dark text when focused. */
@Composable
private fun SheetButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    checked: Boolean = false,
    submenu: Boolean = false,
) {
    FlatSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        containerColor = Palette.SheetItem,
        focusedContainerColor = Palette.SheetFocused,
        contentColor = Palette.SheetText,
        focusedContentColor = Palette.SheetFocusedText,
        focusedBorderColor = null,
        focusedScale = 1.03f,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                Modifier.weight(1f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (value != null) {
                Spacer(Modifier.width(12.dp))
                Text(value, Modifier.alpha(0.65f), fontSize = 16.sp, maxLines = 1)
            }
            if (checked) {
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Filled.Check, contentDescription = "Selected", modifier = Modifier.size(22.dp))
            }
            if (submenu) {
                Spacer(Modifier.width(10.dp))
                Text("›", Modifier.alpha(0.65f), fontSize = 24.sp)
            }
        }
    }
}

@Composable
fun MenuDialog(title: String, items: List<MenuItem>, onDismiss: () -> Unit, subtitle: String? = null) {
    Dialog(onDismissRequest = onDismiss) {
        val first = remember { FocusRequester() }
        Sheet(Modifier.width(440.dp)) {
            SheetTitle(title, subtitle)
            Column(
                Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Start on the chosen option in a list of choices, otherwise on the first row.
                val start = items.indexOfFirst { it.checked }.coerceAtLeast(0)
                items.forEachIndexed { index, item ->
                    SheetButton(
                        label = item.label,
                        value = item.value,
                        checked = item.checked,
                        submenu = item.submenu,
                        onClick = {
                            if (!item.keepOpen) onDismiss()
                            item.action()
                        },
                        modifier = if (index == start) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
        }
        RequestInitialFocus(first)
    }
}

/** Read-only scrolling text, e.g. a crash report. */
@Composable
fun TextDialog(title: String, body: String, onDismiss: () -> Unit, actions: List<MenuItem> = emptyList()) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember { FocusRequester() }
        Sheet(Modifier.fillMaxWidth(0.8f).fillMaxHeight(0.85f)) {
            SheetTitle(title)
            // Focusable so the D-pad can scroll it.
            val scroll = rememberScrollState()
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Palette.Field, LocalShape.current)
                    .focusRequester(first)
                    .focusable()
                    .verticalScroll(scroll)
                    .padding(16.dp),
            ) {
                Text(body, color = Palette.SheetText, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                (actions + MenuItem("Close") { }).forEach { item ->
                    SheetButton(
                        item.label,
                        onClick = {
                            onDismiss()
                            item.action()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        RequestInitialFocus(first)
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    confirmLabel: String = "Save",
) {
    Dialog(onDismissRequest = onDismiss) {
        var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
        // The Android TV keyboard (Leanback) wipes the field when it connects, a moment after the
        // dialog opens, so the current name or town vanished. Until the person has typed anything,
        // ignore a change that empties the whole starting text in one go. Backspace removes one
        // character at a time, so it still works, even on a one-letter start.
        val openedAt = remember { SystemClock.uptimeMillis() }
        var edited by remember { mutableStateOf(false) }
        var fieldFocused by remember { mutableStateOf(false) }
        val field = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        val shape = LocalShape.current
        Sheet(Modifier.width(480.dp)) {
            SheetTitle(title, subtitle)
            BasicTextField(
                value = value,
                onValueChange = { new ->
                    val keyboardReset = !edited && new.text.isEmpty() && initial.length > 1 &&
                        value.text == initial && SystemClock.uptimeMillis() - openedAt < KEYBOARD_RESET_WINDOW_MS
                    if (keyboardReset) return@BasicTextField
                    if (new.text != value.text) edited = true
                    value = new
                },
                singleLine = true,
                textStyle = TextStyle(color = Palette.SheetText, fontSize = 20.sp, fontFamily = Inter),
                cursorBrush = SolidColor(Palette.SheetText),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = if (confirmLabel == "Search") ImeAction.Search else ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { onConfirm(value.text.trim()) },
                    onSearch = { onConfirm(value.text.trim()) },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(field)
                    .onFocusChanged { fieldFocused = it.isFocused }
                    // A single-line field keeps Up/Down to itself; on a remote they should move to
                    // the buttons, or there's no way to reach Save without the on-screen keyboard.
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                            else -> false
                        }
                    }
                    .background(Palette.Field, shape)
                    .border(2.dp, if (fieldFocused) Palette.SheetText else Color.Transparent, shape)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetButton(confirmLabel, onClick = { onConfirm(value.text.trim()) }, modifier = Modifier.weight(1f))
                SheetButton("Cancel", onClick = onDismiss, modifier = Modifier.weight(1f))
            }
        }
        RequestInitialFocus(field)
    }
}

@Composable
fun AppPickerDialog(
    apps: List<AppInfo>,
    title: String = "Choose an App",
    artLoader: suspend (AppInfo) -> AppArt?,
    peekArt: (AppInfo) -> AppArt?,
    onPick: (AppInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember { FocusRequester() }
        Sheet(Modifier.fillMaxWidth(0.84f).fillMaxHeight(0.86f)) {
            SheetTitle(title)
            if (apps.isEmpty()) {
                Text("No apps found", color = Palette.SheetMuted)
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(170.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(8.dp),
            ) {
                itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                    PickerCell(
                        app = app,
                        artLoader = artLoader,
                        peekArt = peekArt,
                        onClick = { onPick(app) },
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
        }
        if (apps.isNotEmpty()) RequestInitialFocus(first)
    }
}

@Composable
private fun PickerCell(
    app: AppInfo,
    artLoader: suspend (AppInfo) -> AppArt?,
    peekArt: (AppInfo) -> AppArt?,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val art by rememberAppArt(app, artLoader, peekArt)
    Column(modifier) {
        FlatSurface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            containerColor = Palette.SheetItem,
            focusedContainerColor = Color.White.copy(alpha = 0.16f),
            focusedScale = 1.06f,
            lift = true,
        ) {
            val a = art
            if (a != null) {
                Image(
                    bitmap = a.bitmap,
                    contentDescription = app.label,
                    modifier = if (a.isBanner) Modifier.fillMaxSize() else Modifier.size(48.dp).align(Alignment.Center),
                    contentScale = if (a.isBanner) ContentScale.Crop else ContentScale.Fit,
                )
            }
        }
        Text(
            app.label,
            Modifier.padding(start = 4.dp, top = 8.dp),
            fontSize = 14.sp,
            color = if (app.isTv) Palette.SheetText else Palette.SheetMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!app.isTv) {
            Text("Phone app", Modifier.padding(start = 4.dp), fontSize = 12.sp, color = Palette.SheetMuted)
        }
    }
}

/** How long after a text dialog opens an all-at-once clear is taken to be the keyboard connecting. */
private const val KEYBOARD_RESET_WINDOW_MS = 1500L
