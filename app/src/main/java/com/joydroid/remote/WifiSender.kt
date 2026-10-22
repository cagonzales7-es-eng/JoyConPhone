package com.joydroid.remote

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/**
 * Envía el estado por UDP al servidor de la PC (~125 Hz).
 * Formato (little-endian, 40 bytes): "JCP1", lado u8 (0=izq,1=der), 3 bytes de botones (bytes 3,4,5 del
 * reporte del Joy-Con), stickX i16, stickY i16, secuencia u32, accel xyz (g) f32, gyro xyz (°/s) f32.
 */
class WifiSender(
    private val side: Side,
    private val motion: MotionSource,
    private val host: String,
    private val port: Int
) {
    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    private var th: Thread? = null

    fun start(onError: (String) -> Unit) {
        running = true
        th = thread(name = "jc-wifi", isDaemon = true, priority = Thread.MAX_PRIORITY) {
            try {
                val addr = InetAddress.getByName(host)
                val s = DatagramSocket()
                socket = s
                val buf = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
                var seq = 0
                var next = System.nanoTime()
                while (running) {
                    next += 8_000_000L
                    val b = InputState.snapshot()
                    val r = motion.latestRaw()
                    buf.clear()
                    buf.put("JCP1".toByteArray(Charsets.US_ASCII))
                    buf.put(if (side == Side.LEFT) 0 else 1)
                    buf.put(b[0].toByte()).put(b[1].toByte()).put(b[2].toByte())
                    buf.putShort((InputState.stickX * 32767).toInt().coerceIn(-32767, 32767).toShort())
                    buf.putShort((InputState.stickY * 32767).toInt().coerceIn(-32767, 32767).toShort())
                    buf.putInt(seq++)
                    for (i in 0 until 6) buf.putFloat(r[i])
                    s.send(DatagramPacket(buf.array(), 40, addr, port))
                    val rem = next - System.nanoTime()
                    if (rem > 0) LockSupport.parkNanos(rem) else next = System.nanoTime()
                }
            } catch (e: Exception) {
                if (running) onError(e.message ?: "error de red")
            } finally {
                socket?.close()
            }
        }
    }

    fun stop() {
        running = false
        th?.interrupt()
        socket?.close()
    }
}
