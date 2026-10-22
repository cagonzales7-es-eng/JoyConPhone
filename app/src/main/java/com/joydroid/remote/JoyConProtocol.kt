package com.joydroid.remote

import java.util.concurrent.atomic.AtomicIntegerArray

enum class Side { LEFT, RIGHT }

/** Botones del Joy-Con: byte del reporte (3 = derecha, 4 = compartidos, 5 = izquierda) y máscara. */
enum class JcButton(val byteIdx: Int, val mask: Int) {
    Y(3, 0x01), X(3, 0x02), B(3, 0x04), A(3, 0x08), RSR(3, 0x10), RSL(3, 0x20), R(3, 0x40), ZR(3, 0x80),
    Minus(4, 0x01), Plus(4, 0x02), RStick(4, 0x04), LStick(4, 0x08), Home(4, 0x10), Capture(4, 0x20),
    Down(5, 0x01), Up(5, 0x02), Right(5, 0x04), Left(5, 0x08), LSR(5, 0x10), LSL(5, 0x20), L(5, 0x40), ZL(5, 0x80)
}

/** Estado de entrada compartido entre la UI (hilo principal) y el bucle de envío. */
object InputState {
    private val bytes = AtomicIntegerArray(3)
    @Volatile var stickX = 0f   // -1..1 (derecha positivo)
    @Volatile var stickY = 0f   // -1..1 (arriba positivo)

    fun set(b: JcButton, pressed: Boolean) {
        bytes.updateAndGet(b.byteIdx - 3) { old ->
            if (pressed) old or b.mask else old and b.mask.inv()
        }
    }

    fun snapshot() = intArrayOf(bytes.get(0), bytes.get(1), bytes.get(2))

    fun clear() {
        for (i in 0..2) bytes.set(i, 0)
        stickX = 0f; stickY = 0f
    }
}

/** Memoria SPI virtual del Joy-Con (calibraciones). 0xFF = "sin dato". */
object SpiFlash {
    private val mem = ByteArray(0x10000) { 0xFF.toByte() }

    private fun w16(addr: Int, v: Int) {
        mem[addr] = v.toByte(); mem[addr + 1] = (v shr 8).toByte()
    }

    private fun pack12(addr: Int, x: Int, y: Int) {
        mem[addr] = (x and 0xFF).toByte()
        mem[addr + 1] = (((x shr 8) and 0x0F) or ((y and 0x0F) shl 4)).toByte()
        mem[addr + 2] = ((y shr 4) and 0xFF).toByte()
    }

    init {
        // Calibración de fábrica del IMU @0x6020: origen accel, escala accel, origen gyro, escala gyro
        for (i in 0..2) {
            w16(0x6020 + i * 2, 0)
            w16(0x6026 + i * 2, 16384)
            w16(0x602C + i * 2, 0)
            w16(0x6032 + i * 2, 13371)
        }
        // Calibración de sticks de fábrica
        val range = StickScale.RANGE
        val c = StickScale.CENTER
        // Izquierdo @0x603D: arriba/derecha, centro, abajo/izquierda
        pack12(0x603D, range, range); pack12(0x6040, c, c); pack12(0x6043, range, range)
        // Derecho @0x6046: centro, abajo/izquierda, arriba/derecha
        pack12(0x6046, c, c); pack12(0x6049, range, range); pack12(0x604C, range, range)
        // Colores @0x6050: cuerpo, botones
        val colors = intArrayOf(0x82, 0x82, 0x82, 0x0F, 0x0F, 0x0F)
        for (i in colors.indices) mem[0x6050 + i] = colors[i].toByte()
    }

    fun read(addr: Int, len: Int): ByteArray {
        if (addr < 0 || len < 0 || addr + len > mem.size) return ByteArray(len.coerceAtLeast(0)) { 0xFF.toByte() }
        return mem.copyOfRange(addr, addr + len)
    }
}

object StickScale {
    const val CENTER = 2048
    const val RANGE = 1500
}

/** Construye los reportes HID del Joy-Con y responde a subcomandos del host. */
class JoyConProtocol(private val side: Side) {
    private var timer = 0
    @Volatile private var rumbleFlag = false

    companion object {
        private val NEUTRAL_RUMBLE = byteArrayOf(0x00, 0x01, 0x40, 0x40)
        // MAC ficticia usada en la respuesta de "device info"
        private val FAKE_MAC = byteArrayOf(0x7C, 0xBB.toByte(), 0x8A.toByte(), 0x12, 0x34, 0x56)
    }

    fun consumeRumble(): Boolean { val r = rumbleFlag; rumbleFlag = false; return r }

    /** Reporte 0x30 (modo estándar con IMU). Devuelve 48 bytes SIN el byte de ID. */
    fun input30(imu: Array<ShortArray>): ByteArray {
        val r = ByteArray(49)
        r[0] = 0x30
        fillCommon(r)
        for (i in 0 until 3) {
            val o = 13 + i * 12
            for (k in 0 until 6) le16(r, o + k * 2, imu[i][k].toInt())
        }
        return r.copyOfRange(1, 49)
    }

    private fun reply21(ack: Int, sub: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val r = ByteArray(49)
        r[0] = 0x21
        fillCommon(r)
        r[13] = ack.toByte()
        r[14] = sub.toByte()
        data.copyInto(r, 15, 0, minOf(data.size, 34))
        return r.copyOfRange(1, 49)
    }

    private fun fillCommon(r: ByteArray) {
        r[1] = (timer++ and 0xFF).toByte()
        r[2] = 0x8E.toByte() // batería llena + conexión por Bluetooth
        val b = InputState.snapshot()
        r[3] = b[0].toByte(); r[4] = b[1].toByte(); r[5] = b[2].toByte()
        val sx = (StickScale.CENTER + InputState.stickX * StickScale.RANGE).toInt().coerceIn(0, 4095)
        val sy = (StickScale.CENTER + InputState.stickY * StickScale.RANGE).toInt().coerceIn(0, 4095)
        // Stick presente en su ranura; el otro queda a 0
        pack12(r, if (side == Side.LEFT) 6 else 9, sx, sy)
    }

    /**
     * Procesa un reporte de salida del host (rumble / subcomandos).
     * [data] NO incluye el ID. Devuelve el cuerpo de un reporte 0x21 a enviar, o null.
     */
    fun onOutput(reportId: Int, data: ByteArray): ByteArray? {
        if ((reportId == 0x01 || reportId == 0x10) && data.size >= 9) {
            val o = if (side == Side.LEFT) 1 else 5
            for (i in 0..3) if (data[o + i] != NEUTRAL_RUMBLE[i]) { rumbleFlag = true; break }
        }
        if (reportId != 0x01 || data.size < 10) return null
        val sub = data[9].toInt() and 0xFF
        val args = data.copyOfRange(10, data.size)
        return when (sub) {
            0x01 -> reply21(0x81, sub, byteArrayOf(0x03))
            0x02 -> reply21(0x82, sub, deviceInfo())
            0x04 -> reply21(0x83, sub)
            0x10 -> spiRead(args)
            else -> reply21(0x80, sub) // 0x03 modo, 0x08, 0x30 luces, 0x38, 0x40 IMU, 0x41, 0x48 vibración...
        }
    }

    private fun deviceInfo(): ByteArray {
        val d = ByteArray(12)
        d[0] = 0x03; d[1] = 0x48                       // versión de firmware
        d[2] = if (side == Side.LEFT) 0x01 else 0x02   // tipo de mando
        d[3] = 0x02
        FAKE_MAC.copyInto(d, 4)
        d[10] = 0x01; d[11] = 0x01                     // usar colores del SPI
        return d
    }

    private fun spiRead(args: ByteArray): ByteArray {
        if (args.size < 5) return reply21(0x80, 0x10)
        val addr = (args[0].toInt() and 0xFF) or ((args[1].toInt() and 0xFF) shl 8) or
            ((args[2].toInt() and 0xFF) shl 16) or ((args[3].toInt() and 0xFF) shl 24)
        val len = (args[4].toInt() and 0xFF).coerceAtMost(29)
        val out = ByteArray(5 + len)
        args.copyInto(out, 0, 0, 5)
        SpiFlash.read(addr, len).copyInto(out, 5)
        return reply21(0x90, 0x10, out)
    }

    private fun le16(r: ByteArray, o: Int, v: Int) { r[o] = v.toByte(); r[o + 1] = (v shr 8).toByte() }

    private fun pack12(r: ByteArray, o: Int, x: Int, y: Int) {
        r[o] = (x and 0xFF).toByte()
        r[o + 1] = (((x shr 8) and 0x0F) or ((y and 0x0F) shl 4)).toByte()
        r[o + 2] = ((y shr 4) and 0xFF).toByte()
    }
}
