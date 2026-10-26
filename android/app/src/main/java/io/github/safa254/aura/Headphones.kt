package io.github.safa254.aura

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Следит за подключёнными Bluetooth-наушниками: название и заряд.
 * Заряд Android узнаёт от самих наушников (Sony WH-1000XM5/XM6, Galaxy Buds и др.
 * его передают) — тот же процент, что виден в настройках Bluetooth.
 */
@SuppressLint("MissingPermission")
class Headphones(private val ctx: Context, private val onChange: (String) -> Unit) {

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val proxies = ConcurrentHashMap<Int, BluetoothProfile>()
    private val battery = ConcurrentHashMap<String, Int>()
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var last = ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            @Suppress("DEPRECATION")
            val dev = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            when (i.action) {
                ACTION_BATTERY_LEVEL_CHANGED -> {
                    val level = i.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    if (dev != null && level >= 0) battery[dev.address] = level
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> dev?.let { battery.remove(it.address) }
            }
            push()
            // Профили A2DP/гарнитуры обновляют список устройств чуть позже события
            handler.postDelayed({ push() }, 1500)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            push()
            handler.postDelayed(this, 60_000)
        }
    }

    fun allowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (started || adapter == null || !allowed()) {
            push()
            return
        }
        started = true
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                proxies[profile] = proxy
                push()
            }

            override fun onServiceDisconnected(profile: Int) {
                proxies.remove(profile)
                push()
            }
        }
        adapter.getProfileProxy(ctx, listener, BluetoothProfile.A2DP)
        adapter.getProfileProxy(ctx, listener, BluetoothProfile.HEADSET)

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
        }
        // Эти события присылает системное приложение Bluetooth, поэтому приёмник открытый
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, filter)
        }
        handler.post(tick)
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacksAndMessages(null)
        try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
        proxies.forEach { (profile, proxy) -> adapter?.closeProfileProxy(profile, proxy) }
        proxies.clear()
    }

    /** Текущее состояние в JSON для страницы: {connected, name, battery, address}. */
    fun json(): String {
        val dev = current() ?: return """{"connected":false}"""
        val o = JSONObject()
        o.put("connected", true)
        o.put("name", name(dev))
        o.put("address", dev.address)
        val level = level(dev)
        o.put("battery", if (level >= 0) level else JSONObject.NULL)
        return o.toString()
    }

    private fun push() {
        val j = json()
        if (j != last) {
            last = j
            onChange(j)
        }
    }

    private fun current(): BluetoothDevice? {
        if (adapter == null || !allowed()) return null
        return try {
            if (!adapter.isEnabled) return null
            val a2dp = proxies[BluetoothProfile.A2DP]
            // Если подключено двое наушников — берём те, в которые сейчас идёт звук
            val active = a2dp?.let { hidden<BluetoothDevice>(it, "getActiveDevice") }
            val all = a2dp?.connectedDevices.orEmpty() + proxies[BluetoothProfile.HEADSET]?.connectedDevices.orEmpty()
            active?.takeIf { a -> all.any { it.address == a.address } } ?: all.firstOrNull()
        } catch (_: SecurityException) {
            null
        }
    }

    private fun name(dev: BluetoothDevice): String {
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) dev.alias else null
        return alias ?: dev.name ?: "Наушники"
    }

    private fun level(dev: BluetoothDevice): Int {
        val fromSystem = hidden<Int>(dev, "getBatteryLevel") ?: -1
        if (fromSystem >= 0) battery[dev.address] = fromSystem
        return battery[dev.address] ?: -1
    }

    // Скрытые методы Android: есть на всех телефонах, но не в открытом SDK
    @Suppress("UNCHECKED_CAST")
    private fun <T> hidden(target: Any, method: String): T? = try {
        target.javaClass.getMethod(method).invoke(target) as T?
    } catch (_: Exception) {
        null
    }

    companion object {
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }
}
