package com.joydroid.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

enum class Mode { BLUETOOTH, WIFI }

data class UiState(
    val active: Boolean = false,
    val side: Side = Side.RIGHT,
    val mode: Mode = Mode.BLUETOOTH,
    val registered: Boolean = false,
    val host: String? = null,
    val status: String = "",
    val bonded: List<Pair<String, String>> = emptyList()
)

@SuppressLint("MissingPermission")
object JoyConEngine {
    private val sendLock = Any()
    private lateinit var app: Context
    private var adapter: BluetoothAdapter? = null
    private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    private var protocol = JoyConProtocol(Side.RIGHT)
    private var motion: MotionSource? = null
    private var wifi: WifiSender? = null
    const val PC_PORT = 26800
    private var loop: Thread? = null
    @Volatile private var running = false
    private var lastBuzz = 0L

    val state = MutableStateFlow(UiState())

    private fun status(s: String) = state.update { it.copy(status = s) }

    fun start(ctx: Context, side: Side, mode: Mode = Mode.BLUETOOTH, pcHost: String = "") {
        stop()
        app = ctx.applicationContext
        if (mode == Mode.WIFI) {
            protocol = JoyConProtocol(side)
            InputState.clear()
            state.value = UiState(active = true, side = side, mode = mode, status = "Enviando a $pcHost:$PC_PORT…")
            val m = MotionSource(app, side).also { it.start() }
            motion = m
            running = true
            wifi = WifiSender(side, m, pcHost, PC_PORT).also {
                it.start { msg -> status("Error de red: $msg") }
            }
            return
        }
        val ad = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (ad == null || !ad.isEnabled) {
            state.value = UiState(active = true, side = side, mode = mode, status = "Bluetooth no disponible o desactivado")
            return
        }
        adapter = ad
        protocol = JoyConProtocol(side)
        InputState.clear()
        state.value = UiState(active = true, side = side, mode = mode, status = "Registrando dispositivo HID…")
        motion = MotionSource(app, side).also { it.start() }
        running = true

        ad.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    if (side == Side.LEFT) "Joy-Con (L)" else "Joy-Con (R)",
                    "Gamepad", "Nintendo",
                    BluetoothHidDevice.SUBCLASS2_GAMEPAD,
                    HidDescriptor.bytes
                )
                val ok = h.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), callback)
                if (!ok) status("No se pudo registrar el perfil HID (¿otra app lo usa?)")
            }
            override fun onServiceDisconnected(profile: Int) { hid = null }
        }, BluetoothProfile.HID_DEVICE)

        loop = thread(name = "jc-report", isDaemon = true, priority = Thread.MAX_PRIORITY) { reportLoop() }
        refreshBonded()
    }

    fun stop() {
        running = false
        wifi?.stop(); wifi = null
        loop?.interrupt(); loop = null
        motion?.stop(); motion = null
        runCatching { hid?.unregisterApp() }
        hid?.let { h -> runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h) } }
        hid = null; host = null
        InputState.clear()
        state.value = UiState()
    }

    fun refreshBonded() {
        val list = runCatching {
            adapter?.bondedDevices?.map { Pair(it.name ?: it.address, it.address) } ?: emptyList()
        }.getOrDefault(emptyList())
        state.update { it.copy(bonded = list) }
    }

    fun connectTo(address: String) {
        val dev = adapter?.bondedDevices?.firstOrNull { it.address == address } ?: return
        val ok = hid?.connect(dev) ?: false
        status(if (ok) "Conectando con ${dev.name ?: address}…" else "No se pudo iniciar la conexión")
    }

    private fun send(id: Int, data: ByteArray) {
        synchronized(sendLock) {
            val d = host ?: return
            hid?.sendReport(d, id, data)
        }
    }

    private fun reportLoop() {
        var next = System.nanoTime()
        while (running) {
            next += 15_000_000L // ~66 Hz, igual que un Joy-Con real
            if (host != null) {
                val imu = motion?.takeSamples() ?: Array(3) { ShortArray(6) }
                send(0x30, protocol.input30(imu))
                if (protocol.consumeRumble()) buzz()
            }
            val rem = next - System.nanoTime()
            if (rem > 0) LockSupport.parkNanos(rem) else next = System.nanoTime()
        }
    }

    private fun buzz() {
        val now = System.currentTimeMillis()
        if (now - lastBuzz < 40) return
        lastBuzz = now
        runCatching {
            app.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(40, 150))
        }
    }

    private fun handleOutput(id: Int, data: ByteArray) {
        val resp = protocol.onOutput(id, data)
        if (resp != null) send(0x21, resp)
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            state.update {
                it.copy(
                    registered = registered,
                    status = if (registered)
                        "Listo. Pulsa «Visible» y empareja «Joy-Con» desde la PC, o elige tu PC en «PCs»."
                    else "Perfil HID no registrado"
                )
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, st: Int) {
            when (st) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    val n = device?.name ?: device?.address
                    state.update { it.copy(host = n, status = "Conectado a $n") }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device?.address == host?.address) host = null
                    state.update { it.copy(host = null, status = "Desconectado") }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            if (data != null) handleOutput(id.toInt() and 0xFF, data)
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) {
            if (data != null) handleOutput(reportId.toInt() and 0xFF, data)
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice?) {
            host = null
            state.update { it.copy(host = null, status = "El host desconectó el cable virtual") }
        }
    }
}
