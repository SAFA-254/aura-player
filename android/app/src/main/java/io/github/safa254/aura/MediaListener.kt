package io.github.safa254.aura

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService

/**
 * Видит плеер Spotify прямо на телефоне (нужен «Доступ к уведомлениям») и показывает
 * его копию от имени Aura — в Now Bar и шторке. Нажатие на карточку открывает Aura,
 * кнопки управляют Spotify. Заодно мгновенно обновляет виджет.
 */
class MediaListener : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var spotify: MediaController? = null
    private var mirror: MediaSession? = null
    private var shownKey = ""

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = update()
        override fun onPlaybackStateChanged(state: PlaybackState?) = update()
        override fun onSessionDestroyed() = attach(null)
    }

    override fun onListenerConnected() {
        instance = this
        val m = getSystemService(MediaSessionManager::class.java)
        manager = m
        val me = ComponentName(this, MediaListener::class.java)
        try {
            m.addOnActiveSessionsChangedListener(sessions, me, main)
            pick(m.getActiveSessions(me))
        } catch (_: SecurityException) {
        }
    }

    override fun onListenerDisconnected() {
        try { manager?.removeOnActiveSessionsChangedListener(sessions) } catch (_: Exception) {}
        attach(null)
        mirror?.release()
        mirror = null
        if (instance === this) instance = null
    }

    private fun pick(list: List<MediaController>?) {
        val c = list?.firstOrNull { it.packageName == Spotify.SPOTIFY_PACKAGE }
        if (c?.sessionToken != spotify?.sessionToken) attach(c)
    }

    private fun attach(c: MediaController?) {
        spotify?.unregisterCallback(callback)
        spotify = c
        c?.registerCallback(callback, main)
        update()
    }

    /** Перерисовать карточку и виджет по текущему состоянию Spotify. */
    fun update() {
        val c = spotify
        val meta = c?.metadata
        val st = c?.playbackState
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val art = meta?.let { art(it) }
        val playing = st?.state == PlaybackState.STATE_PLAYING || st?.state == PlaybackState.STATE_BUFFERING

        if (title != null) {
            val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
            val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
            val end = if (playing && duration > 0 && st != null) System.currentTimeMillis() + duration - position(st) else 0
            AuraWidget.fromMedia(this, title, artist, art, playing, end)
        }

        if (c == null || meta == null || st == null || title == null || !enabled(this) ||
            st.state == PlaybackState.STATE_NONE || st.state == PlaybackState.STATE_STOPPED
        ) {
            hide()
            return
        }
        val s = mirror ?: MediaSession(this, "Aura").also { s ->
            s.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { spotify?.transportControls?.play() }
                override fun onPause() { spotify?.transportControls?.pause() }
                override fun onStop() { spotify?.transportControls?.pause() }
                override fun onSkipToNext() { spotify?.transportControls?.skipToNext() }
                override fun onSkipToPrevious() { spotify?.transportControls?.skipToPrevious() }
                override fun onSeekTo(pos: Long) { spotify?.transportControls?.seekTo(pos) }
            })
            s.setSessionActivity(openAura(this))
            mirror = s
        }
        val copy = MediaMetadata.Builder(meta)
        if (art != null) {
            copy.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
            copy.putBitmap(MediaMetadata.METADATA_KEY_ART, art)
        }
        s.setMetadata(copy.build())
        s.setPlaybackState(PlaybackState.Builder(st).setActions(ACTIONS).build())
        s.isActive = true
        notify(s, meta, art, playing)
    }

    private fun position(st: PlaybackState): Long {
        if (st.state != PlaybackState.STATE_PLAYING) return st.position
        return st.position + ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
    }

    // Обложка не больше 512px — большие картинки не пролезают между процессами
    private fun art(meta: MediaMetadata): Bitmap? {
        val b = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return null
        val max = maxOf(b.width, b.height)
        if (max <= 512) return b
        val k = 512f / max
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true)
    }

    private fun notify(s: MediaSession, meta: MediaMetadata, art: Bitmap?, playing: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Что играет", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(meta.getString(MediaMetadata.METADATA_KEY_TITLE))
            .setContentText(meta.getString(MediaMetadata.METADATA_KEY_ARTIST))
            .setLargeIcon(art)
            .setContentIntent(openAura(this))
            .setOngoing(playing)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .addAction(action(R.drawable.ic_prev, "Назад", ACTION_PREV, 1))
            .addAction(
                if (playing) action(R.drawable.ic_pause, "Пауза", ACTION_TOGGLE, 2)
                else action(R.drawable.ic_play, "Играть", ACTION_TOGGLE, 2),
            )
            .addAction(action(R.drawable.ic_next, "Вперёд", ACTION_NEXT, 3))
            .build()
        try {
            nm.notify(NOTIF_ID, n)
            shownKey = "shown"
        } catch (_: SecurityException) {
        }
    }

    private fun action(icon: Int, label: String, name: String, code: Int): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, icon), label,
            PendingIntent.getBroadcast(
                this, code, Intent(this, Buttons::class.java).setAction(name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()

    private fun hide() {
        if (shownKey.isEmpty() && mirror == null) return
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        mirror?.isActive = false
        shownKey = ""
    }

    /** Кнопки в уведомлении. */
    class Buttons : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val c = instance?.spotify ?: return
            when (intent.action) {
                ACTION_PREV -> c.transportControls.skipToPrevious()
                ACTION_NEXT -> c.transportControls.skipToNext()
                ACTION_TOGGLE -> toggle(c)
            }
        }
    }

    companion object {
        private const val CHANNEL = "nowplaying"
        private const val NOTIF_ID = 9
        private const val ACTION_PREV = "io.github.safa254.aura.media.PREV"
        private const val ACTION_TOGGLE = "io.github.safa254.aura.media.TOGGLE"
        private const val ACTION_NEXT = "io.github.safa254.aura.media.NEXT"
        private val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP

        @Volatile
        var instance: MediaListener? = null

        /** Управление Spotify напрямую на телефоне (мгновенно, без интернета) или null. */
        fun controller(): MediaController? = instance?.spotify

        fun toggle(c: MediaController) {
            val st = c.playbackState?.state
            if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) c.transportControls.pause()
            else c.transportControls.play()
        }

        fun hasAccess(ctx: Context): Boolean =
            ctx.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(ComponentName(ctx, MediaListener::class.java))

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("aura", Context.MODE_PRIVATE)

        fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("mirror", true)

        fun setEnabled(ctx: Context, on: Boolean) {
            prefs(ctx).edit().putBoolean("mirror", on).apply()
            instance?.let { l -> l.main.post { l.update() } }
        }

        private fun openAura(ctx: Context): PendingIntent =
            PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
