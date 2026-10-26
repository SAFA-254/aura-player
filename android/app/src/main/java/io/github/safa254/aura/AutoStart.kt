package io.github.safa254.aura

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.json.JSONObject

/**
 * Наушники подключились → по желанию продолжаем музыку и открываем Aura.
 * Открыть приложение из фона Android разрешает, только если у Aura есть
 * «Поверх других приложений»; без него показываем уведомление «Открыть Aura».
 * Настройки: {open, resume}.
 */
class AutoStart : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        AuraWidget.refreshAsync(ctx)
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        @Suppress("DEPRECATION")
        val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        val cfg = get(ctx)
        val open = cfg.optBoolean("open")
        val resume = cfg.optBoolean("resume")
        if (!open && !resume) return
        val audio = try { Headphones.isAudio(dev) } catch (_: SecurityException) { false }
        if (!audio) return

        val pending = goAsync()
        Thread {
            try {
                // Даём наушникам пару секунд, чтобы звук переключился на них
                Thread.sleep(2500)
                if (resume) {
                    try {
                        Spotify(ctx).play(onPhone = true)
                    } catch (_: Exception) {
                    }
                }
                if (open) openAura(ctx, dev)
                AuraWidget.refresh(ctx)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun openAura(ctx: Context, dev: BluetoothDevice) {
        val launch = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Settings.canDrawOverlays(ctx)) {
            try {
                ctx.startActivity(launch)
                return
            } catch (_: Exception) {
            }
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Наушники", NotificationManager.IMPORTANCE_DEFAULT))
        val label = try { Headphones.label(dev) } catch (_: SecurityException) { "Наушники" }
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle("Подключены $label")
            .setContentText("Нажми, чтобы открыть Aura")
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setContentIntent(
                PendingIntent.getActivity(ctx, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            .build()
        try { nm.notify(NOTIF_ID, n) } catch (_: SecurityException) {}
    }

    companion object {
        private const val CHANNEL = "headphones"
        private const val NOTIF_ID = 8

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("aura", Context.MODE_PRIVATE)

        fun get(ctx: Context): JSONObject =
            prefs(ctx).getString("auto", null)?.let { try { JSONObject(it) } catch (_: Exception) { null } }
                ?: JSONObject().put("open", false).put("resume", false)

        fun set(ctx: Context, json: String) = prefs(ctx).edit().putString("auto", json).apply()
    }
}
