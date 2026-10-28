package io.github.safa254.aura

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Карточка Aura в Now Bar и шторке — без доступа к уведомлениям (его блокирует Play Защита).
 * Что играет, узнаём у Spotify через интернет: пока музыка играет — раз в 10 секунд
 * и в конце трека, на паузе — раз в 30 секунд. Нажатие на карточку открывает Aura.
 * Если 15 минут ничего не играет — служба выключается сама.
 */
class NowBar : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var session: MediaSession
    private var state: JSONObject? = null
    private var artUrl = ""
    private var art: Bitmap? = null
    private var idleSince = 0L

    private val tick = Runnable { poll() }
    private var wake: PowerManager.WakeLock? = null

    // Пока музыка играет в самой Aura, не даём процессору уснуть
    private fun holdWake(on: Boolean) {
        if (on && wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aura:playback")
                .apply { setReferenceCounted(false); acquire(3 * 60 * 60_000L) }
        } else if (!on) {
            wake?.takeIf { it.isHeld }?.release()
            wake = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
        session = MediaSession(this, "Aura").apply {
            setCallback(object : MediaSession.Callback() {
                // Музыка играет в плеере Aura — командуем ему напрямую, без интернета
                override fun onPlay() = act(true, "play") { it.play() }
                override fun onPause() = act(false, "pause") { it.req("PUT", "/me/player/pause") }
                override fun onStop() = act(false, "pause") { it.req("PUT", "/me/player/pause") }
                override fun onSkipToNext() = act(null, "next") { it.next() }
                override fun onSkipToPrevious() = act(null, "prev") { it.prev() }
                override fun onSeekTo(pos: Long) = act(null, "seek:$pos") { it.req("PUT", "/me/player/seek?position_ms=$pos") }
            })
            setSessionActivity(openAura())
        }
        if (engineEnabled(this)) Engine.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Кнопки уведомления приходят сюда как команды службе
        val tc = session.controller.transportControls
        when (intent?.action) {
            "play" -> tc.play()
            "pause" -> tc.pause()
            "next" -> tc.skipToNext()
            "prev" -> tc.skipToPrevious()
            else -> poke()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        Engine.stop()
        holdWake(false)
        session.isActive = false
        session.release()
        if (running === this) running = null
        super.onDestroy()
    }

    /** Спросить Spotify прямо сейчас. */
    fun poke() {
        main.removeCallbacks(tick)
        main.post(tick)
    }

    // Нажали кнопку на карточке: сразу меняем значок, потом говорим Spotify и перепроверяем
    private fun act(playing: Boolean?, engineCmd: String, call: (Spotify) -> Unit) {
        if (playing != null) state?.put("is_playing", playing)?.also { apply() }
        val device = state?.optJSONObject("device")?.optString("id")
        if (Engine.deviceId != null && (device == Engine.deviceId || Engine.playing)) {
            Engine.command(engineCmd)
            main.postDelayed({ poke() }, 700)
            return
        }
        io.execute {
            try { call(Spotify(this)) } catch (_: Exception) {}
            main.postDelayed({ poke() }, 700)
        }
    }

    private var apiAt = 0L

    private fun poll() {
        main.removeCallbacks(tick)
        io.execute {
            // Музыка играет в плеере Aura — всё знаем и так, в Spotify ходим лишь раз в минуту
            // (проверить, не переключили ли музыку на другое устройство)
            val local = if (Engine.playing && SystemClock.elapsedRealtime() - apiAt < 60_000) Engine.snapshot() else null
            val st = local ?: try { Spotify(this).state().also { apiAt = SystemClock.elapsedRealtime() } } catch (_: Exception) { null }
            val item = st?.optJSONObject("item")
            if (item != null) {
                val url = cover(item)
                if (url != artUrl) { artUrl = url; art = download(url) }
            }
            main.post {
                state = st?.also { it.put("_at", SystemClock.elapsedRealtime()) }
                apply()
                val playing = st?.optBoolean("is_playing") == true
                if (playing) idleSince = 0 else if (idleSince == 0L) idleSince = SystemClock.elapsedRealtime()
                holdWake(localPlayback)
                if ((!enabled(this) && !localPlayback && !engineEnabled(this)) || (idleSince > 0 && SystemClock.elapsedRealtime() - idleSince > IDLE_MS)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@post
                }
                var delay = if (playing) 10_000L else 30_000L
                if (playing && item != null) {
                    val left = item.optLong("duration_ms") - st.optLong("progress_ms")
                    if (left in 0 until delay) delay = left + 1500
                }
                main.postDelayed(tick, delay)
            }
        }
    }

    private fun apply() {
        val st = state
        val item = st?.optJSONObject("item")
        if (item == null) {
            session.isActive = false
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
            return
        }
        val artists = item.optJSONArray("artists")
        val artist = (0 until (artists?.length() ?: 0)).joinToString(", ") { artists!!.getJSONObject(it).optString("name") }
            .ifEmpty { item.optJSONObject("show")?.optString("name") ?: "" }
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, item.optString("name"))
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, item.optJSONObject("album")?.optString("name") ?: "")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, item.optLong("duration_ms"))
        art?.let { meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(meta.build())

        val playing = st.optBoolean("is_playing")
        val pos = st.optLong("progress_ms") +
            if (playing) SystemClock.elapsedRealtime() - st.optLong("_at") else 0
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, pos, if (playing) 1f else 0f)
                .build(),
        )
        session.isActive = true
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(item.optString("name"), artist, playing))
    }

    private fun notification(title: String? = null, artist: String = "", playing: Boolean = false): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Aura в Now Bar", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(title ?: "Aura")
            .setContentText(if (title == null) "Ничего не играет" else artist)
            .setLargeIcon(art)
            .setContentIntent(openAura())
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .addAction(action(R.drawable.ic_prev, "Назад", "prev", 1))
            .addAction(if (playing) action(R.drawable.ic_pause, "Пауза", "pause", 2) else action(R.drawable.ic_play, "Играть", "play", 2))
            .addAction(action(R.drawable.ic_next, "Вперёд", "next", 3))
        return b.build()
    }

    private fun action(icon: Int, label: String, cmd: String, code: Int): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, icon), label,
            PendingIntent.getService(
                this, code, Intent(this, NowBar::class.java).setAction(cmd),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()

    private fun cover(item: JSONObject): String {
        val images = item.optJSONObject("album")?.optJSONArray("images") ?: item.optJSONArray("images") ?: return ""
        var url = ""
        for (k in 0 until images.length()) {
            val img = images.getJSONObject(k)
            if (url.isEmpty() || img.optInt("width", 640) >= 300) url = img.optString("url")
        }
        return url
    }

    private fun download(url: String): Bitmap? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 10_000 }
        conn.inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }

    private fun openAura(): PendingIntent =
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        private const val CHANNEL = "nowbar"
        private const val NOTIF_ID = 10
        private const val IDLE_MS = 15 * 60_000L
        private val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO

        @Volatile
        var running: NowBar? = null

        /** Музыка играет во встроенном плеере Aura — служба нужна, даже если карточка выключена. */
        @Volatile
        var localPlayback = false

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("aura", Context.MODE_PRIVATE)

        fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("nowbar", false)

        /** Включён ли плеер в фоне (Engine) — тогда служба нужна всегда, пока что-то играет. */
        fun engineEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("engine", false)

        fun setEngine(ctx: Context, on: Boolean) {
            prefs(ctx).edit().putBoolean("engine", on).apply()
            if (on) {
                running?.let { Engine.start(it) } ?: start(ctx)
            } else {
                Engine.stop()
                running?.poke()
            }
        }

        fun setEnabled(ctx: Context, on: Boolean) {
            prefs(ctx).edit().putBoolean("nowbar", on).apply()
            if (on) start(ctx) else running?.poke()
        }

        /** Запустить (можно только пока Aura на экране) или подтолкнуть уже работающую. */
        fun start(ctx: Context) {
            if ((!enabled(ctx) && !localPlayback && !engineEnabled(ctx)) || !Spotify(ctx).loggedIn()) return
            if (engineEnabled(ctx)) running?.let { Engine.start(it) }
            running?.let { it.poke(); return }
            try { ctx.startForegroundService(Intent(ctx, NowBar::class.java)) } catch (_: Exception) {}
        }
    }
}
