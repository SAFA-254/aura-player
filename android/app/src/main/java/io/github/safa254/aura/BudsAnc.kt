package io.github.safa254.aura

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Шумоподавление и эквалайзер Galaxy Buds (Buds2 и новее, в том числе Buds3 FE).
 * Протокол разобран проектом GalaxyBudsClient: кадр FD … DD, внутри длина, номер команды,
 * данные и CRC16-CCITT. Ответов ждать не нужно — наушники просто применяют команду.
 */
@SuppressLint("MissingPermission")
object BudsAnc {
    private val UUID_SPP: UUID = UUID.fromString("2e73a4ad-332d-41fc-90e2-16bef06523f2")
    private val NAME = Regex("""Buds""", RegexOption.IGNORE_CASE)

    private const val NOISE_CONTROLS = 120
    private const val EQUALIZER = 134

    fun isBuds(dev: BluetoothDevice): Boolean =
        dev.uuids?.any { it.uuid == UUID_SPP } == true || NAME.containsMatchIn(dev.name ?: "")

    /**
     * cmd.op: "anc" — {mode: "off" | "nc" | "ambient" | "adaptive"};
     * "eq" — {preset: -1 выкл, 0 басы, 1 мягкий, 2 динамичный, 3 чистый, 4 высокие}.
     */
    @Synchronized
    fun command(dev: BluetoothDevice, cmd: JSONObject): JSONObject {
        closing?.cancel(false)
        return try {
            val payload = when (cmd.optString("op")) {
                "anc" -> byteArrayOf(
                    NOISE_CONTROLS.toByte(),
                    when (cmd.optString("mode")) { "nc" -> 1; "ambient" -> 2; "adaptive" -> 3; else -> 0 }.toByte(),
                )
                "eq" -> byteArrayOf(EQUALIZER.toByte(), (cmd.optInt("preset", -1) + 1).coerceIn(0, 5).toByte())
                else -> throw IOException("Неизвестная команда")
            }
            try {
                socket(dev).outputStream.apply { write(frame(payload)); flush() }
            } catch (e: IOException) {
                // Соединение могло тихо оборваться — пробуем один раз заново
                drop()
                socket(dev).outputStream.apply { write(frame(payload)); flush() }
            }
            JSONObject().put("ok", true)
        } catch (e: Exception) {
            drop()
            JSONObject().put("ok", false).put("error", e.message ?: "Наушники не ответили")
        } finally {
            // Отпускаем соединение, чтобы Galaxy Wearable мог снова подключиться
            closing = timer.schedule(Runnable { synchronized(BudsAnc) { drop() } }, 20, TimeUnit.SECONDS)
        }
    }

    private val timer = Executors.newSingleThreadScheduledExecutor()
    private var closing: ScheduledFuture<*>? = null
    private var sock: BluetoothSocket? = null
    private var address: String? = null

    private fun socket(dev: BluetoothDevice): BluetoothSocket {
        sock?.takeIf { address == dev.address && it.isConnected }?.let { return it }
        drop()
        try { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() } catch (_: SecurityException) {}
        val s = dev.createRfcommSocketToServiceRecord(UUID_SPP)
        try {
            s.connect()
        } catch (e: IOException) {
            throw IOException("Не удалось подключиться к Buds. Закрой Galaxy Wearable и попробуй ещё раз")
        }
        // Читаем и выбрасываем всё, что присылают наушники, чтобы их буфер не переполнился
        Thread({
            val buf = ByteArray(512)
            try { while (s.inputStream.read(buf) >= 0) Unit } catch (_: IOException) {}
        }, "buds-read").apply { isDaemon = true }.start()
        sock = s
        address = dev.address
        return s
    }

    private fun drop() {
        try { sock?.close() } catch (_: IOException) {}
        sock = null
        address = null
    }

    /** [FD] [длина: 2 байта, младший первым] [номер и данные] [CRC16, младший первым] [DD] */
    private fun frame(idAndPayload: ByteArray): ByteArray {
        val size = idAndPayload.size + 2
        val crc = crc16(idAndPayload)
        return byteArrayOf(0xFD.toByte(), size.toByte(), (size shr 8).toByte()) +
            idAndPayload + byteArrayOf(crc.toByte(), (crc shr 8).toByte(), 0xDD.toByte())
    }

    private fun crc16(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xff) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xffff
        }
        return crc
    }
}
