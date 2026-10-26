package io.github.safa254.aura

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Виджет на главный экран: обложка, трек, кнопки и заряд наушников.
 * Пока Aura открыта, состояние присылает страница; когда закрыта — виджет
 * сам спрашивает Spotify по нажатию кнопок, в конце трека и раз в 15 минут.
 */
class AuraWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        render(ctx)
        refreshAsync(ctx)
    }

    override fun onEnabled(ctx: Context) = schedulePeriodic(ctx)

    override fun onDisabled(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        js.cancel(JOB_PERIODIC)
        js.cancel(JOB_TRACK_END)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action
        if (action !in listOf(ACTION_PREV, ACTION_TOGGLE, ACTION_NEXT)) {
            super.onReceive(ctx, intent)
            return
        }
        // Есть доступ к плееру Spotify на телефоне — управляем им напрямую, мгновенно
        MediaListener.controller()?.let { c ->
            when (action) {
                ACTION_PREV -> c.transportControls.skipToPrevious()
                ACTION_NEXT -> c.transportControls.skipToNext()
                ACTION_TOGGLE -> MediaListener.toggle(c)
            }
            return
        }
        if (action == ACTION_TOGGLE) {
            // Сразу меняем значок, не дожидаясь Spotify
            val s = cached(ctx)
            s.put("playing", !s.optBoolean("playing"))
            save(ctx, s)
            render(ctx)
        }
        val pending = goAsync()
        IO.execute {
            try {
                val sp = Spotify(ctx)
                when (action) {
                    ACTION_PREV -> sp.prev()
                    ACTION_TOGGLE -> sp.togglePlay()
                    ACTION_NEXT -> sp.next()
                }
                Thread.sleep(700)
                refresh(ctx)
            } catch (e: Exception) {
                MAIN.post { Toast.makeText(ctx, e.message ?: "Spotify не ответил", Toast.LENGTH_SHORT).show() }
                refresh(ctx)
            } finally {
                pending.finish()
            }
        }
    }

    /** Периодическое обновление, пока Aura закрыта. */
    class Job : JobService() {
        override fun onStartJob(params: JobParameters): Boolean {
            IO.execute {
                refresh(this)
                jobFinished(params, false)
            }
            return true
        }

        override fun onStopJob(params: JobParameters) = false
    }

    companion object {
        const val ACTION_PREV = "io.github.safa254.aura.widget.PREV"
        const val ACTION_TOGGLE = "io.github.safa254.aura.widget.TOGGLE"
        const val ACTION_NEXT = "io.github.safa254.aura.widget.NEXT"
        private const val JOB_PERIODIC = 100
        private const val JOB_TRACK_END = 101

        private val IO = Executors.newSingleThreadExecutor()
        private val MAIN = Handler(Looper.getMainLooper())

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("aura", Context.MODE_PRIVATE)
        private fun coverFile(ctx: Context) = File(ctx.filesDir, "widget-cover.jpg")

        private fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, AuraWidget::class.java))

        fun cached(ctx: Context): JSONObject =
            prefs(ctx).getString("widget", null)?.let { try { JSONObject(it) } catch (_: Exception) { null } } ?: JSONObject()

        private fun save(ctx: Context, s: JSONObject) = prefs(ctx).edit().putString("widget", s.toString()).apply()

        /** Состояние от страницы: {title, artist, cover, playing, accent, end}. */
        fun fromPage(ctx: Context, json: String) {
            if (ids(ctx).isEmpty()) return
            IO.execute {
                try {
                    val s = JSONObject(json)
                    val old = cached(ctx)
                    if (!s.has("accent")) s.put("accent", old.optString("accent"))
                    update(ctx, s, old)
                } catch (_: Exception) {
                }
            }
        }

        /** Состояние прямо от плеера Spotify на телефоне (через MediaListener). */
        fun fromMedia(ctx: Context, title: String, artist: String, art: Bitmap?, playing: Boolean, end: Long) {
            if (ids(ctx).isEmpty()) return
            IO.execute {
                val old = cached(ctx)
                val key = "media:$title|$artist"
                var cover = old.optString("cover")
                if (art != null && cover != key) {
                    try {
                        coverFile(ctx).outputStream().use { art.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                        cover = key
                    } catch (_: Exception) {
                    }
                }
                val s = JSONObject()
                    .put("title", title).put("artist", artist).put("cover", cover)
                    .put("playing", playing).put("accent", old.optString("accent")).put("end", end)
                save(ctx, s)
                render(ctx)
                scheduleTrackEnd(ctx, s)
            }
        }

        fun refreshAsync(ctx: Context) {
            if (ids(ctx).isEmpty()) return
            IO.execute { refresh(ctx) }
        }

        /** Спросить Spotify, что играет, и перерисовать. Только из фонового потока. */
        fun refresh(ctx: Context) {
            if (ids(ctx).isEmpty()) return
            MediaListener.instance?.let { l ->
                if (MediaListener.controller() != null) { MAIN.post { l.update() }; return }
            }
            val old = cached(ctx)
            val s = JSONObject().put("accent", old.optString("accent"))
            try {
                val sp = Spotify(ctx)
                if (!sp.loggedIn()) {
                    s.put("title", "Aura").put("artist", "Открой Aura и войди в Spotify")
                } else {
                    val st = sp.state()
                    val item = st?.optJSONObject("item")
                    if (item == null) {
                        s.put("title", "Ничего не играет").put("artist", "Нажми ▶, чтобы продолжить")
                    } else {
                        val artists = item.optJSONArray("artists")
                        val names = (0 until (artists?.length() ?: 0)).joinToString(", ") { artists!!.getJSONObject(it).optString("name") }
                        val images = item.optJSONObject("album")?.optJSONArray("images") ?: item.optJSONArray("images")
                        // Картинки идут от большой к маленькой — берём последнюю не меньше 300px
                        var cover = ""
                        for (k in 0 until (images?.length() ?: 0)) {
                            val img = images!!.getJSONObject(k)
                            if (cover.isEmpty() || img.optInt("width", 640) >= 300) cover = img.optString("url")
                        }
                        val playing = st.optBoolean("is_playing")
                        s.put("title", item.optString("name"))
                            .put("artist", names.ifEmpty { item.optJSONObject("show")?.optString("name") ?: "" })
                            .put("cover", cover)
                            .put("playing", playing)
                        if (playing) s.put("end", System.currentTimeMillis() + item.optLong("duration_ms") - st.optLong("progress_ms"))
                    }
                }
            } catch (_: Exception) {
                // Нет сети — оставляем то, что было
                old.put("playing", false)
                save(ctx, old)
                render(ctx)
                return
            }
            update(ctx, s, old)
        }

        private fun update(ctx: Context, s: JSONObject, old: JSONObject) {
            val url = s.optString("cover")
            if (url.isNotEmpty() && url != old.optString("cover")) download(url, coverFile(ctx))
            if (url.isEmpty()) coverFile(ctx).delete()
            save(ctx, s)
            render(ctx)
            scheduleTrackEnd(ctx, s)
        }

        private fun download(url: String, to: File) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                val bytes = conn.inputStream.use { it.readBytes() }
                to.writeBytes(bytes)
            } catch (_: Exception) {
                to.delete()
            }
        }

        fun render(ctx: Context) {
            val ids = ids(ctx)
            if (ids.isEmpty()) return
            val s = cached(ctx)
            val cover: Bitmap? = coverFile(ctx).takeIf { it.exists() }?.let {
                BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 2 })
            }
            val bg = cover?.let { blurred(it) }
            val hp = Headphones.connectedNow(ctx)?.let { Headphones.label(it) }
            val big = views(ctx, R.layout.widget, s, cover, bg, hp, small = false)
            val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Растянули в один ряд — компактный вид, иначе большой
                RemoteViews(
                    mapOf(
                        SizeF(180f, 40f) to views(ctx, R.layout.widget_small, s, cover, bg, hp, small = true),
                        SizeF(180f, 110f) to big,
                    ),
                )
            } else {
                big
            }
            AppWidgetManager.getInstance(ctx).updateAppWidget(ids, views)
        }

        private fun views(
            ctx: Context, layout: Int, s: JSONObject, cover: Bitmap?, bg: Bitmap?, hp: String?, small: Boolean,
        ): RemoteViews {
            val v = RemoteViews(ctx.packageName, layout)
            v.setTextViewText(R.id.title, s.optString("title").ifEmpty { "Aura" })
            v.setTextViewText(R.id.artist, s.optString("artist").ifEmpty { "Нажми, чтобы открыть" })
            if (cover != null) v.setImageViewBitmap(R.id.cover, cover) else v.setImageViewResource(R.id.cover, R.drawable.ic_note)
            if (bg != null) v.setImageViewBitmap(R.id.bg, bg) else v.setImageViewResource(R.id.bg, android.R.color.transparent)

            v.setImageViewResource(R.id.toggleIcon, if (s.optBoolean("playing")) R.drawable.ic_pause else R.drawable.ic_play)
            val accent = try { Color.parseColor(s.optString("accent")) } catch (_: Exception) { Color.parseColor("#C9A7FF") }
            v.setInt(R.id.toggleBg, "setColorFilter", accent)

            if (small) {
                v.setViewVisibility(R.id.headphones, if (hp != null) View.VISIBLE else View.GONE)
                v.setTextViewText(R.id.headphones, "🎧 " + (hp ?: ""))
            } else {
                v.setViewVisibility(R.id.hpRow, if (hp != null) View.VISIBLE else View.GONE)
                v.setTextViewText(R.id.headphones, hp ?: "")
                v.setOnClickPendingIntent(R.id.prev, button(ctx, ACTION_PREV, 1))
            }

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.cover, open)
            v.setOnClickPendingIntent(R.id.title, open)
            v.setOnClickPendingIntent(R.id.artist, open)
            v.setOnClickPendingIntent(R.id.toggle, button(ctx, ACTION_TOGGLE, 2))
            v.setOnClickPendingIntent(R.id.next, button(ctx, ACTION_NEXT, 3))
            return v
        }

        // Размытая обложка для фона, как в самой Aura: уменьшаем и несколько раз усредняем соседей,
        // при растягивании на весь виджет получается мягкое цветное пятно
        private fun blurred(src: Bitmap): Bitmap {
            val n = 36
            val small = Bitmap.createScaledBitmap(src, n, n, true)
            val px = IntArray(n * n)
            small.getPixels(px, 0, n, 0, 0, n, n)
            val tmp = IntArray(n * n)
            repeat(3) {
                boxPass(px, tmp, n, horizontal = true)
                boxPass(tmp, px, n, horizontal = false)
            }
            return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        }

        private fun boxPass(from: IntArray, to: IntArray, n: Int, horizontal: Boolean) {
            val r = 3
            for (line in 0 until n) {
                for (k in 0 until n) {
                    var a = 0; var rr = 0; var g = 0; var b = 0; var c = 0
                    for (d in -r..r) {
                        val j = (k + d).coerceIn(0, n - 1)
                        val p = if (horizontal) from[line * n + j] else from[j * n + line]
                        rr += (p shr 16) and 0xff; g += (p shr 8) and 0xff; b += p and 0xff; a += 1; c++
                    }
                    val out = (0xff shl 24) or ((rr / c) shl 16) or ((g / c) shl 8) or (b / c)
                    if (horizontal) to[line * n + k] = out else to[k * n + line] = out
                }
            }
        }

        private fun button(ctx: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, code, Intent(ctx, AuraWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        fun schedulePeriodic(ctx: Context) {
            val job = JobInfo.Builder(JOB_PERIODIC, ComponentName(ctx, Job::class.java))
                .setPeriodic(15 * 60_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(false)
                .build()
            try { ctx.getSystemService(JobScheduler::class.java).schedule(job) } catch (_: Exception) {}
        }

        // Обновиться, когда текущий трек закончится
        private fun scheduleTrackEnd(ctx: Context, s: JSONObject) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            val end = s.optLong("end")
            if (!s.optBoolean("playing") || end <= 0) {
                js.cancel(JOB_TRACK_END)
                return
            }
            val delay = (end - System.currentTimeMillis() + 2000).coerceAtLeast(2000)
            val job = JobInfo.Builder(JOB_TRACK_END, ComponentName(ctx, Job::class.java))
                .setMinimumLatency(delay)
                .setOverrideDeadline(delay + 60_000)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()
            try { js.schedule(job) } catch (_: Exception) {}
        }
    }
}
