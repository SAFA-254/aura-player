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
import android.os.Handler
import android.os.Looper
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

        fun refreshAsync(ctx: Context) {
            if (ids(ctx).isEmpty()) return
            IO.execute { refresh(ctx) }
        }

        /** Спросить Spotify, что играет, и перерисовать. Только из фонового потока. */
        fun refresh(ctx: Context) {
            if (ids(ctx).isEmpty()) return
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
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            v.setTextViewText(R.id.title, s.optString("title").ifEmpty { "Aura" })
            v.setTextViewText(R.id.artist, s.optString("artist").ifEmpty { "Нажми, чтобы открыть" })

            val cover: Bitmap? = coverFile(ctx).takeIf { it.exists() }?.let {
                BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 2 })
            }
            if (cover != null) v.setImageViewBitmap(R.id.cover, cover) else v.setImageViewResource(R.id.cover, R.drawable.ic_note)

            v.setImageViewResource(R.id.toggleIcon, if (s.optBoolean("playing")) R.drawable.ic_pause else R.drawable.ic_play)
            val accent = try { Color.parseColor(s.optString("accent")) } catch (_: Exception) { Color.parseColor("#C9A7FF") }
            v.setInt(R.id.toggleBg, "setColorFilter", accent)

            val hp = Headphones.connectedNow(ctx)
            if (hp != null) {
                v.setViewVisibility(R.id.hpRow, View.VISIBLE)
                v.setTextViewText(R.id.headphones, Headphones.label(hp))
            } else {
                v.setViewVisibility(R.id.hpRow, View.GONE)
            }

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.cover, open)
            v.setOnClickPendingIntent(R.id.title, open)
            v.setOnClickPendingIntent(R.id.artist, open)
            v.setOnClickPendingIntent(R.id.prev, button(ctx, ACTION_PREV, 1))
            v.setOnClickPendingIntent(R.id.toggle, button(ctx, ACTION_TOGGLE, 2))
            v.setOnClickPendingIntent(R.id.next, button(ctx, ACTION_NEXT, 3))
            AppWidgetManager.getInstance(ctx).updateAppWidget(ids, v)
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
