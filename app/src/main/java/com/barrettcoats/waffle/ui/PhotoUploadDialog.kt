package com.barrettcoats.waffle.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.barrettcoats.waffle.LauncherViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** "Send photos from your phone": scan or type the address, enter the code, pick photos. */
@Composable
fun PhotoUploadDialog(session: LauncherViewModel.UploadSession, onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val done = remember { FocusRequester() }
        val shape = LocalSheetShape.current
        Row(
            Modifier
                .ignorePressFromBefore()
                .width(820.dp)
                .background(Palette.Sheet, shape)
                .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
                .padding(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Scanning opens the page with the code filled in.
            QrCode("${session.url}/?code=${session.code}", Modifier.size(240.dp))
            Spacer(Modifier.width(32.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Add Photos from Your Phone", color = Palette.SheetText, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Step(1, "Connect your phone to the same Wi-Fi as this TV.")
                Step(2, "Scan the code with your phone's camera, or open:")
                Text(
                    session.url.removePrefix("http://"),
                    color = Palette.SheetText,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 40.dp),
                )
                Step(3, "If asked, enter this code:")
                Row(Modifier.padding(start = 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (digit in session.code) {
                        Box(
                            Modifier.size(48.dp, 58.dp).background(Palette.Field, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("$digit", color = Palette.SheetText, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlatSurface(
                        onClick = onDone,
                        modifier = Modifier.focusRequester(done),
                        shape = CircleShape,
                        containerColor = Palette.SheetItem,
                        focusedContainerColor = Palette.SheetFocused,
                        contentColor = Palette.SheetText,
                        focusedContentColor = Palette.SheetFocusedText,
                        focusedBorderColor = null,
                        focusedScale = 1.05f,
                    ) {
                        Text("Done", Modifier.padding(horizontal = 28.dp, vertical = 10.dp), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(18.dp))
                    Text(
                        when (session.received) {
                            0 -> "Waiting for photos…"
                            1 -> "1 photo received"
                            else -> "${session.received} photos received"
                        },
                        color = if (session.received > 0) Palette.Accent else Palette.SheetMuted,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        RequestInitialFocus(done)
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).background(Palette.SheetItem, CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", color = Palette.SheetText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = Palette.SheetMuted, fontSize = 17.sp)
    }
}

/** A QR code drawn as dark squares on a white card (with the quiet zone phones need). */
@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    }
    Box(modifier.background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Canvas(Modifier.matchParentSize()) {
            val cell = size.minDimension / matrix.width
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color(0xFF1C1C1E), Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
