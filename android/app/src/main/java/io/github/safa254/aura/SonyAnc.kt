package io.github.safa254.aura

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Шумоподавление / «Звуковой фон» Sony (WH-1000XM5 и другие с протоколом v2).
 * Протокол разобран проектом Gadgetbridge: кадр 3E … 3C, внутри тип, номер, длина, данные
 * и контрольная сумма; на каждую команду наушники отвечают ACK, и мы им тоже.
 */
@SuppressLint("MissingPermission")
object SonyAnc {
    private val UUID_V1: UUID = UUID.fromString("96CC203E-5068-46ad-B32D-E316F5E069BA")
    private val UUID_V2: UUID = UUID.fromString("956C7B26-D49A-4BA8-B03F-B17D393CB6E2")
    private val NAME = Regex("""\b(WH|WF|WI)-\w+|LinkBuds|ULT WEAR""", RegexOption.IGNORE_CASE)

    private const val ACK = 0x01
    private const val COMMAND = 0x0c
    private const val GET = 0x66
    private const val RET = 0x67
    private const val SET = 0x68
    private const val NOTIFY = 0x69

    fun isSony(dev: BluetoothDevice): Boolean =
        dev.uuids?.any { it.uuid == UUID_V1 || it.uuid == UUID_V2 } == true || NAME.containsMatchIn(dev.name ?: "")

    /**
     * Читает (mode == null) или меняет режим: "nc" — шумоподавление, "ambient" — звуковой фон,
     * "off" — выключено. level — сила звукового фона 1…20. Блокирующий вызов.
     */
    @Synchronized
    fun run(dev: BluetoothDevice, mode: String?, level: Int?, voice: Boolean?): JSONObject {
        closing?.cancel(false)
        return try {
            val reused = session?.takeIf { it.address == dev.address && it.alive } != null
            try {
                attempt(dev, mode, level, voice)
            } catch (e: Exception) {
                drop()
                // Старое соединение могло тихо оборваться — пробуем один раз заново
                if (!reused) throw e
                attempt(dev, mode, level, voice)
            }
        } catch (e: Exception) {
            drop()
            JSONObject().put("ok", false).put("error", e.message ?: "Наушники не ответили")
        } finally {
            // Наушники держат только одно управляющее соединение, а Sound Connect тоже хочет его —
            // поэтому через 20 секунд без нажатий отпускаем
            closing = timer.schedule(Runnable { synchronized(SonyAnc) { drop() } }, IDLE_SEC, TimeUnit.SECONDS)
        }
    }

    /**
     * Остальные настройки Sony. cmd.op:
     *  "read" — прочитать всё; "eq" — {preset} или {bands: [clearBass, полосы…]} от -10 до 10;
     *  "pause" / "stc" / "apo" — {on}: пауза, когда сняли; Speak-to-Chat; выключаться, когда сняли.
     * Ответ: {ok, anc?, eq?: {preset, bands}, pause?, stc?, apo?}. Блокирующий вызов.
     */
    @Synchronized
    fun command(dev: BluetoothDevice, cmd: JSONObject): JSONObject {
        closing?.cancel(false)
        return try {
            val reused = session?.takeIf { it.address == dev.address && it.alive } != null
            try {
                attemptCommand(dev, cmd)
            } catch (e: Exception) {
                drop()
                if (!reused) throw e
                attemptCommand(dev, cmd)
            }
        } catch (e: Exception) {
            drop()
            JSONObject().put("ok", false).put("error", e.message ?: "Наушники не ответили")
        } finally {
            closing = timer.schedule(Runnable { synchronized(SonyAnc) { drop() } }, IDLE_SEC, TimeUnit.SECONDS)
        }
    }

    private fun attemptCommand(dev: BluetoothDevice, cmd: JSONObject): JSONObject {
        val s = session?.takeIf { it.address == dev.address && it.alive }
            ?: Session(dev).also { it.init(); session = it; last = null }
        val out = JSONObject().put("ok", true)
        val on = cmd.optBoolean("on")
        when (cmd.optString("op")) {
            "read" -> {
                s.get().let { last = it; out.put("anc", state(it)) }
                readEq(s)?.let { out.put("eq", it) }
                readFlag(s, 0x01)?.let { out.put("pause", it) }
                readFlag(s, 0x0c)?.let { out.put("stc", it) }
                s.request(byteArrayOf(0x26, 0x05), 0x27, 0x05)?.let { out.put("apo", it[2].toInt() == 0x10) }
            }
            "eq" -> {
                val bands = cmd.optJSONArray("bands")
                val payload = if (bands != null) {
                    // 0xa0 — «Вручную», дальше число значений и сами значения со сдвигом +10
                    byteArrayOf(0x58, 0x00, 0xa0.toByte(), bands.length().toByte()) +
                        ByteArray(bands.length()) { (bands.getInt(it).coerceIn(-10, 10) + 10).toByte() }
                } else {
                    byteArrayOf(0x58, 0x00, cmd.optInt("preset").toByte(), 0x00)
                }
                s.request(payload, 0x59, 0x00, 800)
                readEq(s)?.let { out.put("eq", it) }
            }
            // У этих настроек Sony «0» значит «включено»
            "pause" -> { s.request(byteArrayOf(0xf8.toByte(), 0x01, if (on) 0 else 1), 0xf9, 0x01, 600); out.put("pause", on) }
            "stc" -> { s.request(byteArrayOf(0xf8.toByte(), 0x0c, if (on) 0 else 1, 0x01), 0xf9, 0x0c, 600); out.put("stc", on) }
            "apo" -> { s.request(byteArrayOf(0x28, 0x05, if (on) 0x10 else 0x11, 0x00), 0x29, 0x05, 600); out.put("apo", on) }
        }
        return out
    }

    private fun readEq(s: Session): JSONObject? {
        val p = s.request(byteArrayOf(0x56, 0x00), 0x57, 0x00) ?: return null
        if (p.size < 5) return null
        val n = (p[3].toInt() and 0xff).coerceAtMost(p.size - 4)
        val bands = org.json.JSONArray()
        for (k in 0 until n) bands.put(p[4 + k] - 10)
        return JSONObject().put("preset", p[2].toInt() and 0xff).put("bands", bands)
    }

    private fun readFlag(s: Session, kind: Int): Boolean? {
        val p = s.request(byteArrayOf(0xf6.toByte(), kind.toByte()), 0xf7, kind)
            ?: s.request(byteArrayOf(0x26, kind.toByte()), 0x27, kind)
            ?: return null
        return p.size > 2 && p[2].toInt() == 0
    }

    private const val IDLE_SEC = 20L
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private var closing: ScheduledFuture<*>? = null
    private var session: Session? = null
    private var last: ByteArray? = null

    private fun attempt(dev: BluetoothDevice, mode: String?, level: Int?, voice: Boolean?): JSONObject {
        val s = session?.takeIf { it.address == dev.address && it.alive }
            ?: Session(dev).also { it.init(); session = it; last = null }
        var cur = if (mode == null) s.get() else last ?: s.get()
        if (mode != null) cur = s.set(cur, mode, level, voice)
        last = cur
        return state(cur).put("ok", true)
    }

    private fun drop() {
        session?.close()
        session = null
        last = null
    }

    private fun state(p: ByteArray): JSONObject {
        val wind = p[1].toInt() == 0x17 && p.size > 7
        val mode = when {
            p[3].toInt() == 0 -> "off"
            wind && (p[5].toInt() == 3 || p[5].toInt() == 5) -> "wind"
            p[4].toInt() == 1 -> "ambient"
            else -> "nc"
        }
        val i = if (wind) 6 else 5
        return JSONObject().put("mode", mode).put("voice", p[i].toInt() == 1).put("level", p[i + 1].toInt())
    }

    private class Msg(val type: Int, val seq: Int, val payload: ByteArray)

    private class Session(dev: BluetoothDevice) : Closeable {
        val address: String = dev.address
        @Volatile var alive = true
        private val socket: BluetoothSocket
        private val acks = LinkedBlockingQueue<Msg>()
        private val commands = LinkedBlockingQueue<Msg>()
        private var seq = 0

        init {
            // Поиск устройств мешает подключению, но на новых Android для его остановки нужно ещё одно разрешение
            try { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() } catch (_: SecurityException) {}
            val v2 = dev.uuids?.any { it.uuid == UUID_V2 } ?: true
            socket = dev.createRfcommSocketToServiceRecord(if (v2) UUID_V2 else UUID_V1)
            try {
                socket.connect()
            } catch (e: IOException) {
                throw IOException("Не удалось подключиться к наушникам. Закрой Sony Sound Connect и попробуй ещё раз")
            }
            Thread(::readLoop, "sony-anc").apply { isDaemon = true }.start()
        }

        fun init() {
            send(byteArrayOf(0x00, 0x00))
            val reply = await { it[0].toInt() == 0x01 } ?: throw IOException("Наушники не ответили")
            if (reply.size == 4) throw IOException("Эта модель Sony пока не поддерживается")
        }

        fun get(): ByteArray {
            for (kind in intArrayOf(0x15, 0x17)) {
                commands.clear()
                send(byteArrayOf(GET.toByte(), kind.toByte()))
                await { it[0].toInt() == RET && it.size >= 7 }?.let { return it }
            }
            throw IOException("Наушники не сообщили режим шумоподавления")
        }

        fun set(cur: ByteArray, mode: String, level: Int?, voice: Boolean?): ByteArray {
            val p = cur.copyOf()
            val wind = p[1].toInt() == 0x17 && p.size > 7
            p[0] = SET.toByte()
            p[2] = 0x01
            p[3] = if (mode == "off") 0 else 1
            p[4] = if (mode == "ambient") 1 else 0
            if (wind) p[5] = 0x02
            val i = if (wind) 6 else 5
            if (voice != null) p[i] = if (voice) 1 else 0
            if (level != null) p[i + 1] = level.coerceIn(1, 20).toByte()
            commands.clear()
            send(p)
            // Наушники присылают новое состояние; если нет — считаем, что применилось как просили
            return await(800) { it[0].toInt() == NOTIFY || it[0].toInt() == RET }?.takeIf { it.size >= 7 }
                ?: p.also { it[0] = RET.toByte() }
        }

        /** Отправить команду и дождаться ответа с кодом ret (и подкодом sub во втором байте). */
        fun request(payload: ByteArray, ret: Int, sub: Int?, ms: Long = 1500): ByteArray? {
            commands.clear()
            send(payload)
            return await(ms) { (it[0].toInt() and 0xff) == ret && (sub == null || (it.size > 1 && (it[1].toInt() and 0xff) == sub)) }
        }

        private fun send(payload: ByteArray) {
            write(frame(COMMAND, seq, payload))
            val ack = acks.poll(2, TimeUnit.SECONDS) ?: throw IOException("Наушники не ответили")
            seq = ack.seq
        }

        // ACK из потока чтения и команды из основного не должны перемешаться
        @Synchronized
        private fun write(bytes: ByteArray) {
            socket.outputStream.write(bytes)
            socket.outputStream.flush()
        }

        private fun await(ms: Long = 2500, match: (ByteArray) -> Boolean): ByteArray? {
            val until = System.currentTimeMillis() + ms
            while (true) {
                val left = until - System.currentTimeMillis()
                if (left <= 0) return null
                val m = commands.poll(left, TimeUnit.MILLISECONDS) ?: return null
                if (m.payload.isNotEmpty() && match(m.payload)) return m.payload
            }
        }

        private fun readLoop() {
            val input = try { socket.inputStream } catch (_: IOException) { return }
            val buf = ByteArrayOutputStream()
            try {
                while (true) {
                    val b = input.read()
                    if (b < 0) break
                    if (b == 0x3e) buf.reset()
                    buf.write(b)
                    if (b != 0x3c) continue
                    val m = parse(buf.toByteArray()) ?: continue
                    if (m.type == ACK) {
                        acks.offer(m)
                    } else {
                        write(frame(ACK, 1 - m.seq, ByteArray(0)))
                        commands.offer(m)
                    }
                }
            } catch (_: IOException) {
            } finally {
                alive = false
            }
        }

        override fun close() {
            alive = false
            try { socket.close() } catch (_: IOException) {}
        }
    }

    private fun frame(type: Int, seq: Int, payload: ByteArray): ByteArray {
        val n = payload.size
        val raw = byteArrayOf(type.toByte(), seq.toByte(), (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + payload
        val sum = raw.fold(0) { a, b -> a + (b.toInt() and 0xff) }.toByte()
        val out = ByteArrayOutputStream()
        out.write(0x3e)
        for (b in raw + sum) {
            val v = b.toInt() and 0xff
            if (v == 0x3c || v == 0x3d || v == 0x3e) {
                out.write(0x3d)
                out.write(v and 0xef)
            } else {
                out.write(v)
            }
        }
        out.write(0x3c)
        return out.toByteArray()
    }

    private fun parse(bytes: ByteArray): Msg? {
        if (bytes.size < 3 || bytes.first().toInt() != 0x3e) return null
        val out = ByteArrayOutputStream()
        var k = 1
        while (k < bytes.size - 1) {
            val v = bytes[k].toInt() and 0xff
            if (v == 0x3d && k + 1 < bytes.size - 1) {
                out.write((bytes[k + 1].toInt() and 0xff) or 0x10)
                k += 2
            } else {
                out.write(v)
                k++
            }
        }
        val m = out.toByteArray()
        if (m.size < 7) return null
        val sum = m.copyOfRange(0, m.size - 1).fold(0) { a, b -> a + (b.toInt() and 0xff) }.toByte()
        if (sum != m.last()) return null
        val len = ((m[2].toInt() and 0xff) shl 24) or ((m[3].toInt() and 0xff) shl 16) or ((m[4].toInt() and 0xff) shl 8) or (m[5].toInt() and 0xff)
        if (len != m.size - 7) return null
        return Msg(m[0].toInt() and 0xff, m[1].toInt(), m.copyOfRange(6, 6 + len))
    }
}
