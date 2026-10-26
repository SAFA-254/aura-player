package io.github.safa254.aura

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Будильник с музыкой: в нужное время включает плейлист в Spotify на этом телефоне
 * и плавно прибавляет громкость. Если Spotify не ответил — звенит обычный будильник.
 *
 * Настройки: {enabled, hour, minute, days: [1..7, где 1 — понедельник; пусто — один раз],
 * uri, name, volume: 0..100, shuffle}.
 */
object Alarm {
    private const val REQ_ALARM = 10
    private const val REQ_SNOOZE = 11
    const val SNOOZE_MIN = 9

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("aura", Context.MODE_PRIVATE)

    fun get(ctx: Context): JSONObject =
        prefs(ctx).getString("alarm", null)?.let { try { JSONObject(it) } catch (_: Exception) { null } }
            ?: JSONObject().put("enabled", false).put("hour", 7).put("minute", 30).put("days", JSONArray("[1,2,3,4,5]"))
                .put("volume", 60).put("shuffle", true)

    /** Сохраняет настройки и возвращает время следующего звонка (мс) или 0. */
    fun set(ctx: Context, json: String): Long {
        prefs(ctx).edit().putString("alarm", json).apply()
        return schedule(ctx)
    }

    fun next(a: JSONObject, from: Long = System.currentTimeMillis()): Long {
        if (!a.optBoolean("enabled")) return 0
        val days = a.optJSONArray("days")
        val wanted = (0 until (days?.length() ?: 0)).map { days!!.getInt(it) }.toSet()
        val c = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, a.optInt("hour"))
            set(Calendar.MINUTE, a.optInt("minute"))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        for (i in 0..7) {
            if (c.timeInMillis > from) {
                // Calendar: воскресенье = 1 → переводим в 1 = понедельник … 7 = воскресенье
                val dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
                if (wanted.isEmpty() || dow in wanted) return c.timeInMillis
            }
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return 0
    }

    fun schedule(ctx: Context): Long {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = fireIntent(ctx, REQ_ALARM)
        am.cancel(pi)
        val at = next(get(ctx))
        if (at > 0) setExact(ctx, am, at, pi)
        return at
    }

    fun snooze(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        setExact(ctx, am, System.currentTimeMillis() + SNOOZE_MIN * 60_000L, fireIntent(ctx, REQ_SNOOZE))
    }

    private fun setExact(ctx: Context, am: AlarmManager, at: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            return
        }
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
    }

    private fun fireIntent(ctx: Context, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, Receiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Срабатывание будильника, а также перезагрузка/смена времени — переставить будильник. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_FIRE) {
                val a = get(ctx)
                val days = a.optJSONArray("days")
                if (days == null || days.length() == 0) {
                    // Одноразовый — выключаем, чтобы не звонил завтра
                    prefs(ctx).edit().putString("alarm", a.put("enabled", false).toString()).apply()
                }
                ctx.startForegroundService(Intent(ctx, Ring::class.java))
            }
            schedule(ctx)
            if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                AuraWidget.schedulePeriodic(ctx)
            }
        }
    }

    /** Звонок: музыка или запасная мелодия, плавная громкость, уведомление на экране блокировки. */
    class Ring : Service() {
        private val main = Handler(Looper.getMainLooper())
        private var player: MediaPlayer? = null
        private var startVolume = -1
        private var ramping = true

        override fun onBind(intent: Intent?): IBinder? = null

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            when (intent?.action) {
                ACTION_SNOOZE -> { stopMusic(); snooze(this); finish(); return START_NOT_STICKY }
                ACTION_STOP -> { stopMusic(); finish(); return START_NOT_STICKY }
                ACTION_KEEP -> { finish(); return START_NOT_STICKY }
            }
            if (status != null) return START_NOT_STICKY // уже звеним
            status = "Включаю музыку…"
            val n = notification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, n)
            }
            // На заблокированном экране уведомление само откроет экран будильника, а так — пробуем открыть сразу
            try { startActivity(Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            Thread(::start, "aura-alarm").start()
            // Через полчаса заканчиваем сами
            main.postDelayed({ finish() }, 30 * 60_000L)
            return START_NOT_STICKY
        }

        private fun start() {
            val a = get(this)
            val audio = getSystemService(AudioManager::class.java)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (max * a.optInt("volume", 60) / 100).coerceIn(1, max)
            startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (target / 6).coerceAtLeast(1), 0)
            val uri = a.optString("uri").ifEmpty { null }
            try {
                Spotify(this).play(uri, a.optBoolean("shuffle"), onPhone = true)
                status = a.optString("name").ifEmpty { "Музыка" }
            } catch (e: Exception) {
                status = "Spotify не ответил — звенит обычный будильник"
                main.post { ringtone() }
            }
            main.post { updateNotification() }
            // Плавно: от 1/6 до нужной громкости за минуту
            val steps = 20
            for (i in 1..steps) {
                if (!ramping) return
                Thread.sleep(3000)
                if (!ramping) return
                val v = target / 6 + (target - target / 6) * i / steps
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
            }
        }

        private fun ringtone() {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                setDataSource(this@Ring, uri)
                isLooping = true
                prepare()
                start()
            }
        }

        private fun stopMusic() {
            ramping = false
            player?.run { stop(); release() }
            player = null
            Thread {
                try { Spotify(this).req("PUT", "/me/player/pause") } catch (_: Exception) {}
            }.start()
            if (startVolume >= 0) getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, startVolume, 0)
        }

        private fun finish() {
            ramping = false
            player?.run { stop(); release() }
            player = null
            status = null
            main.removeCallbacksAndMessages(null)
            RingActivity.current?.finish()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        private fun updateNotification() {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
        }

        private fun notification(): Notification {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Будильник", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
            val screen = PendingIntent.getActivity(
                this, 0, Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle("Будильник Aura · " + SimpleDateFormat("HH:mm", Locale("ru")).format(Date()))
                .setContentText(status ?: "")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setFullScreenIntent(screen, true)
                .setContentIntent(screen)
                .addAction(Notification.Action.Builder(null, "Отложить $SNOOZE_MIN мин", action(ACTION_SNOOZE, 1)).build())
                .addAction(Notification.Action.Builder(null, "Стоп", action(ACTION_STOP, 2)).build())
                .addAction(Notification.Action.Builder(null, "Пусть играет", action(ACTION_KEEP, 3)).build())
                .build()
        }

        private fun action(name: String, code: Int): PendingIntent =
            PendingIntent.getService(
                this, code, Intent(this, Ring::class.java).setAction(name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        override fun onDestroy() {
            ramping = false
            player?.release()
            status = null
            super.onDestroy()
        }
    }

    /** Экран будильника поверх блокировки. */
    class RingActivity : Activity() {
        private val main = Handler(Looper.getMainLooper())
        private lateinit var info: TextView

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            current = this
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.statusBarColor = Color.parseColor("#120E18")
            window.navigationBarColor = Color.parseColor("#120E18")

            val d = resources.displayMetrics.density
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding((28 * d).toInt(), 0, (28 * d).toInt(), 0)
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.parseColor("#3A2A55"), Color.parseColor("#120E18")),
                )
            }
            val clock = TextView(this).apply {
                text = SimpleDateFormat("HH:mm", Locale("ru")).format(Date())
                textSize = 76f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            val title = TextView(this).apply {
                text = "Доброе утро ☀"
                textSize = 22f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            info = TextView(this).apply {
                textSize = 16f
                setTextColor(Color.parseColor("#B3FFFFFF"))
                gravity = Gravity.CENTER
                setPadding(0, (10 * d).toInt(), 0, (40 * d).toInt())
            }
            root.addView(clock)
            root.addView(title)
            root.addView(info)
            root.addView(button("Пусть играет", "#C9A7FF", "#120E18") { send(ACTION_KEEP) })
            root.addView(button("Отложить на $SNOOZE_MIN мин", "#26FFFFFF", "#FFFFFF") { send(ACTION_SNOOZE) })
            root.addView(button("Стоп", "#26FFFFFF", "#FFFFFF") { send(ACTION_STOP) })
            setContentView(root)
            tick()
        }

        private fun tick() {
            info.text = status ?: ""
            if (status == null && !isFinishing) { finish(); return }
            main.postDelayed(::tick, 1000)
        }

        private fun button(label: String, bg: String, fg: String, onClick: () -> Unit): Button {
            val d = resources.displayMetrics.density
            return Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 17f
                setTextColor(Color.parseColor(fg))
                background = GradientDrawable().apply {
                    cornerRadius = 30 * d
                    setColor(Color.parseColor(bg))
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (56 * d).toInt()).apply {
                    topMargin = (12 * d).toInt()
                }
                setOnClickListener { onClick() }
            }
        }

        private fun send(action: String) {
            startService(Intent(this, Ring::class.java).setAction(action))
            finish()
        }

        override fun onDestroy() {
            main.removeCallbacksAndMessages(null)
            if (current === this) current = null
            super.onDestroy()
        }

        companion object {
            var current: RingActivity? = null
        }
    }

    @Volatile
    var status: String? = null

    const val ACTION_FIRE = "io.github.safa254.aura.alarm.FIRE"
    const val ACTION_SNOOZE = "io.github.safa254.aura.alarm.SNOOZE"
    const val ACTION_STOP = "io.github.safa254.aura.alarm.STOP"
    const val ACTION_KEEP = "io.github.safa254.aura.alarm.KEEP"
    private const val CHANNEL = "alarm"
    private const val NOTIF_ID = 7
}
