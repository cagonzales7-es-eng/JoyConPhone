package com.joydroid.remote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private var pending: Triple<Side, Mode, String>? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val p = pending; pending = null
            val ok = Build.VERSION.SDK_INT < 31 || res[Manifest.permission.BLUETOOTH_CONNECT] == true
            if (p != null && ok) startController(p.first, p.second, p.third)
            else Toast.makeText(this, "Se necesita el permiso de Bluetooth", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            JoyConApp(
                onStart = ::requestStart,
                onStop = ::stopController,
                onDiscoverable = ::makeDiscoverable
            )
        }
    }

    private fun requestStart(side: Side, mode: Mode, ip: String) {
        if (mode == Mode.WIFI && ip.isBlank()) {
            Toast.makeText(this, "Escribe la IP de tu PC", Toast.LENGTH_LONG).show()
            return
        }
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startController(side, mode, ip)
        else { pending = Triple(side, mode, ip); permLauncher.launch(missing.toTypedArray()) }
    }

    private fun startController(side: Side, mode: Mode, ip: String) {
        if (mode == Mode.BLUETOOTH) {
            val bt = getSystemService(BluetoothManager::class.java)?.adapter
            if (bt == null || !bt.isEnabled) {
                Toast.makeText(this, "Activa el Bluetooth primero", Toast.LENGTH_LONG).show()
                return
            }
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, JoyConService::class.java)
                .putExtra("side", side.name).putExtra("mode", mode.name).putExtra("ip", ip)
        )
    }

    private fun stopController() {
        stopService(Intent(this, JoyConService::class.java))
        JoyConEngine.stop()
    }

    private fun makeDiscoverable() {
        try {
            startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            )
        } catch (e: SecurityException) {
            Toast.makeText(this, "Falta el permiso de Bluetooth (anunciar)", Toast.LENGTH_LONG).show()
        }
    }
}
