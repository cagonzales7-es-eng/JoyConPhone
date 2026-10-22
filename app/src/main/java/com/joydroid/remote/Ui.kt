package com.joydroid.remote

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val Bg = Color(0xFF101014)
private val Key = Color(0xFF2E2E36)
private val KeyDown = Color(0xFFEDEDED)
private val Muted = Color(0xFFB0B0B8)
private val Blue = Color(0xFF0AB9E6)
private val Red = Color(0xFFFF4554)

@Composable
fun JoyConApp(onStart: (Side, Mode, String) -> Unit, onStop: () -> Unit, onDiscoverable: () -> Unit) {
    val st by JoyConEngine.state.collectAsState()
    MaterialTheme(colorScheme = darkColorScheme()) {
        if (st.active) ControllerScreen(st, onStop, onDiscoverable) else HomeScreen(onStart)
    }
}

@Composable
private fun HomeScreen(onStart: (Side, Mode, String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("jc", Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(if (prefs.getString("mode", "BT") == "WIFI") Mode.WIFI else Mode.BLUETOOTH) }
    var ip by remember { mutableStateOf(prefs.getString("ip", "") ?: "") }
    fun go(side: Side) {
        prefs.edit().putString("mode", if (mode == Mode.WIFI) "WIFI" else "BT").putString("ip", ip.trim()).apply()
        onStart(side, mode, ip.trim())
    }
    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Joy-Con Phone", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Modo de conexión", color = Muted)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { mode = Mode.WIFI }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = if (mode == Mode.WIFI) Color(0xFF3D7BFF) else Key, contentColor = Color.White)
            ) { Text("Wi-Fi (servidor PC)", fontSize = 13.sp) }
            Button(
                onClick = { mode = Mode.BLUETOOTH }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = if (mode == Mode.BLUETOOTH) Color(0xFF3D7BFF) else Key, contentColor = Color.White)
            ) { Text("Bluetooth HID", fontSize = 13.sp) }
        }
        if (mode == Mode.WIFI) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = ip, onValueChange = { ip = it }, singleLine = true,
                label = { Text("IP de la PC (la muestra el servidor)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("Elige qué Joy-Con será este teléfono", color = Muted)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { go(Side.LEFT) },
            modifier = Modifier.fillMaxWidth().height(72.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color.Black)
        ) { Text("Joy-Con izquierdo", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { go(Side.RIGHT) },
            modifier = Modifier.fillMaxWidth().height(72.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White)
        ) { Text("Joy-Con derecho", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(24.dp))
        Text(
            if (mode == Mode.WIFI)
                "Wi-Fi: ejecuta joycon_server.py en la PC, escribe aquí la IP que muestra y elige el lado. Teléfono y PC deben estar en la misma red."
            else
                "Bluetooth: pulsa «Visible» y empareja «Joy-Con» desde la PC; si ya está emparejado, usa «PCs».",
            color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ControllerScreen(st: UiState, onStop: () -> Unit, onDiscoverable: () -> Unit) {
    val side = st.side
    val accent = if (side == Side.LEFT) Blue else Red
    var showHosts by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding().padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (side == Side.LEFT) "Joy-Con (L)" else "Joy-Con (R)",
                    color = accent, fontWeight = FontWeight.Bold
                )
                Text(st.status, color = Muted, fontSize = 11.sp, maxLines = 2)
            }
            if (st.mode == Mode.BLUETOOTH) {
                TextButton(onClick = onDiscoverable) { Text("Visible") }
                TextButton(onClick = { showHosts = !showHosts; JoyConEngine.refreshBonded() }) { Text("PCs") }
            }
            TextButton(onClick = onStop) { Text("Salir") }
        }

        if (showHosts) {
            Column(Modifier.fillMaxWidth().background(Key, RoundedCornerShape(12.dp)).padding(8.dp)) {
                if (st.bonded.isEmpty()) {
                    Text("No hay dispositivos emparejados. Empareja primero desde la PC.", color = Muted, fontSize = 12.sp)
                }
                st.bonded.forEach { (name, addr) ->
                    TextButton(onClick = { JoyConEngine.connectTo(addr); showHosts = false }) {
                        Text(name, color = Color.White)
                    }
                }
            }
        }

        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
            if (side == Side.LEFT) Rail(Side.LEFT) else Spacer(Modifier.width(60.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 8.dp)) {
                val s = minOf(maxWidth * 0.8f, maxHeight * 0.27f)
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (side == Side.LEFT) { Shoulder("ZL", JcButton.ZL); Shoulder("L", JcButton.L) }
                        else { Shoulder("R", JcButton.R); Shoulder("ZR", JcButton.ZR) }
                    }
                    Spacer(Modifier.weight(1f))
                    if (side == Side.LEFT) {
                        VirtualStick(s, accent)
                        Spacer(Modifier.weight(1f))
                        Diamond(s, "▲", JcButton.Up, "▼", JcButton.Down, "◀", JcButton.Left, "▶", JcButton.Right)
                    } else {
                        Diamond(s, "X", JcButton.X, "B", JcButton.B, "Y", JcButton.Y, "A", JcButton.A)
                        Spacer(Modifier.weight(1f))
                        VirtualStick(s, accent)
                    }
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        if (side == Side.LEFT) {
                            SmallKey("−", JcButton.Minus); SmallKey("L3", JcButton.LStick); SmallKey("◉", JcButton.Capture)
                        } else {
                            SmallKey("+", JcButton.Plus); SmallKey("R3", JcButton.RStick); SmallKey("⌂", JcButton.Home)
                        }
                    }
                }
            }
            if (side == Side.RIGHT) Rail(Side.RIGHT) else Spacer(Modifier.width(60.dp))
        }
    }
}

@Composable
private fun Rail(side: Side) {
    val sl = if (side == Side.LEFT) JcButton.LSL else JcButton.RSL
    val sr = if (side == Side.LEFT) JcButton.LSR else JcButton.RSR
    Column(Modifier.width(60.dp).fillMaxHeight().padding(vertical = 70.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PadButton("SL", sl, Modifier.fillMaxWidth().weight(1f), RoundedCornerShape(16.dp))
        PadButton("SR", sr, Modifier.fillMaxWidth().weight(1f), RoundedCornerShape(16.dp))
    }
}

@Composable
private fun RowScope.Shoulder(label: String, btn: JcButton) {
    PadButton(label, btn, Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(14.dp))
}

@Composable
private fun SmallKey(label: String, btn: JcButton) {
    PadButton(label, btn, Modifier.size(50.dp), CircleShape)
}

@Composable
private fun Diamond(
    size: Dp,
    topL: String, top: JcButton, botL: String, bot: JcButton,
    leftL: String, left: JcButton, rightL: String, right: JcButton
) {
    val b = size * 0.36f
    Box(Modifier.size(size)) {
        PadButton(topL, top, Modifier.align(Alignment.TopCenter).size(b))
        PadButton(botL, bot, Modifier.align(Alignment.BottomCenter).size(b))
        PadButton(leftL, left, Modifier.align(Alignment.CenterStart).size(b))
        PadButton(rightL, right, Modifier.align(Alignment.CenterEnd).size(b))
    }
}

@Composable
private fun PadButton(label: String, btn: JcButton, modifier: Modifier = Modifier, shape: Shape = CircleShape) {
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    Box(
        modifier
            .clip(shape)
            .background(if (pressed) KeyDown else Key)
            .pointerInput(btn) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    InputState.set(btn, true)
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    waitForUpOrCancellation()
                    pressed = false
                    InputState.set(btn, false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (pressed) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
private fun VirtualStick(diameter: Dp, accent: Color) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(Color(0xFF1B1B21))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val radius = size.width / 2f
                    val center = Offset(radius, radius)
                    fun update(pos: Offset) {
                        var d = pos - center
                        val len = d.getDistance()
                        if (len > radius) d = d * (radius / len)
                        knob = d
                        var x = d.x / radius
                        var y = -d.y / radius
                        if (kotlin.math.hypot(x, y) < 0.08f) { x = 0f; y = 0f }
                        InputState.stickX = x
                        InputState.stickY = y
                    }
                    update(down.position)
                    drag(down.id) { change -> update(change.position); change.consume() }
                    knob = Offset.Zero
                    InputState.stickX = 0f
                    InputState.stickY = 0f
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .offset { IntOffset(knob.x.roundToInt(), knob.y.roundToInt()) }
                .size(diameter * 0.45f)
                .clip(CircleShape)
                .background(accent)
        )
    }
}
