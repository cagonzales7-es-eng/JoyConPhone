package com.joydroid.remote

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Lee acelerómetro + giroscopio y los convierte a las unidades "crudas" del IMU del Joy-Con.
 * AJUSTA aquí las constantes si el movimiento va invertido, escalado o rotado en el emulador.
 */
class MotionSource(ctx: Context, private val side: Side) : SensorEventListener {

    companion object {
        const val ACC_LSB_PER_MS2 = 4096f / 9.80665f   // ±8G ≈ 4096 LSB por G
        const val GYRO_LSB_PER_DPS = 16.4f             // ±2000 °/s sobre 16 bits ≈ 0.061 °/s por LSB
        const val FLIP_YZ_FOR_RIGHT = false            // pon true si en el Joy-Con derecho Y/Z salen invertidos
    }

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val queue = ConcurrentLinkedQueue<ShortArray>()
    private val accel = FloatArray(3)
    private var thread: HandlerThread? = null
    private var last = ShortArray(6)
    @Volatile private var raw = FloatArray(6)

    /** accel (g) y gyro (°/s) en el sistema de ejes del teléfono: x derecha, y arriba, z hacia ti. */
    fun latestRaw(): FloatArray = raw

    fun start() {
        val t = HandlerThread("jc-sensors").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, 5000, h) }
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, 5000, h) }
    }

    fun stop() {
        sm.unregisterListener(this)
        thread?.quitSafely(); thread = null
        queue.clear()
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> { e.values.copyInto(accel, 0, 0, 3) }
            Sensor.TYPE_GYROSCOPE -> {
                val gr = 9.80665f
                raw = floatArrayOf(accel[0] / gr, accel[1] / gr, accel[2] / gr,
                    e.values[0] * 57.29578f, e.values[1] * 57.29578f, e.values[2] * 57.29578f)
                // Teléfono en vertical -> Joy-Con en vertical: X = arriba del teléfono, Y = izquierda, Z = hacia el usuario
                var ax = accel[1]; var ay = -accel[0]; var az = accel[2]
                var gx = e.values[1]; var gy = -e.values[0]; var gz = e.values[2]
                if (side == Side.RIGHT && FLIP_YZ_FOR_RIGHT) { ay = -ay; az = -az; gy = -gy; gz = -gz }
                val dps = 57.29578f
                val s = ShortArray(6)
                s[0] = clamp(ax * ACC_LSB_PER_MS2); s[1] = clamp(ay * ACC_LSB_PER_MS2); s[2] = clamp(az * ACC_LSB_PER_MS2)
                s[3] = clamp(gx * dps * GYRO_LSB_PER_DPS); s[4] = clamp(gy * dps * GYRO_LSB_PER_DPS); s[5] = clamp(gz * dps * GYRO_LSB_PER_DPS)
                queue.add(s)
                while (queue.size > 64) queue.poll()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun clamp(v: Float): Short = v.toInt().coerceIn(-32768, 32767).toShort()

    /** Devuelve 3 muestras (promedio de lo acumulado desde el último reporte). */
    fun takeSamples(): Array<ShortArray> {
        val list = ArrayList<ShortArray>()
        while (true) { list.add(queue.poll() ?: break) }
        if (list.isEmpty()) return Array(3) { last }
        val out = Array(3) { ShortArray(6) }
        for (i in 0..2) {
            val from = list.size * i / 3
            val to = maxOf(from + 1, list.size * (i + 1) / 3).coerceAtMost(list.size)
            for (k in 0..5) {
                var sum = 0
                for (j in from until to) sum += list[j][k]
                out[i][k] = (sum / (to - from)).toShort()
            }
        }
        last = out[2]
        return out
    }
}
