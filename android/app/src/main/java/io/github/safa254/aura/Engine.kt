package io.github.safa254.aura

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * Плеер Spotify, который живёт в фоновой службе (NowBar), а не в окне Aura:
 * окно можно закрыть или смахнуть — музыка продолжит играть.
 * Внутри — невидимый WebView со страницей player.html (официальный Web Playback SDK).
 */
@SuppressLint("SetJavaScriptEnabled")
object Engine {
    private const val PAGE = "https://safa-254.github.io/aura-player/player.html"
    private val main = Handler(Looper.getMainLooper())

    private var web: WebView? = null
    private var attached = false
    private lateinit var app: Context

    @Volatile var status = "off"
    @Volatile var error = ""
    @Volatile var deviceId: String? = null
    @Volatile var playing = false
    @Volatile var track = ""
    @Volatile var position = 0L
    @Volatile var duration = 0L
    @Volatile var trackUri = ""
    @Volatile var artists = ""
    @Volatile var album = ""
    @Volatile var image = ""
    @Volatile var stateAt = 0L

    /** Что играет в плеере Aura — в том же виде, что /me/player у Spotify (без запроса в сеть). */
    fun snapshot(): JSONObject? {
        if (deviceId == null || trackUri.isEmpty()) return null
        val images = org.json.JSONArray().apply { if (image.isNotEmpty()) put(JSONObject().put("url", image).put("width", 300)) }
        val names = org.json.JSONArray().apply { artists.split(", ").filter { it.isNotBlank() }.forEach { put(JSONObject().put("name", it)) } }
        val pos = position + if (playing) android.os.SystemClock.elapsedRealtime() - stateAt else 0
        return JSONObject()
            .put("is_playing", playing)
            .put("progress_ms", pos.coerceAtMost(duration))
            .put("device", JSONObject().put("id", deviceId))
            .put("item", JSONObject().put("name", track).put("uri", trackUri).put("duration_ms", duration)
                .put("artists", names).put("album", JSONObject().put("name", album).put("images", images)))
    }

    /** Окно Aura, если открыто: сюда отправляем события плеера. */
    var listener: ((String) -> Unit)? = null

    val running: Boolean get() = web != null

    fun json(): String = JSONObject()
        .put("status", status).put("error", error).put("deviceId", deviceId ?: JSONObject.NULL)
        .put("playing", playing).put("track", track).put("position", position).put("duration", duration)
        .toString()

    /** Запустить плеер. Только из главного потока. */
    fun start(ctx: Context) {
        if (web != null) return
        app = ctx.applicationContext
        status = "loading"
        error = ""
        AuraLog.add(app, "плеер в фоне: запускаю")
        val w = WebView(app)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        // Процесс страницы — важный, даже когда окна нет: иначе Android его заморозит
        w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        w.addJavascriptInterface(Bridge(), "AuraEngine")
        w.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val host = request.origin.host ?: ""
                if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in request.resources &&
                    (host.endsWith("scdn.co") || host.endsWith("spotify.com") || host == "safa-254.github.io")
                ) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                } else {
                    request.deny()
                }
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                AuraLog.add(app, if (detail.didCrash()) "плеер в фоне: процесс упал" else "плеер в фоне: Android выгрузил процесс")
                main.post { stop(); start(app) }
                return true
            }
        }
        attach(w)
        web = w
        w.loadUrl(PAGE)
        emit("status")
    }

    fun stop() {
        val w = web ?: return
        web = null
        if (attached) try { app.getSystemService(WindowManager::class.java).removeView(w) } catch (_: Exception) {}
        attached = false
        w.destroy()
        status = "off"
        deviceId = null
        if (playing) { playing = false; NowBar.localPlayback = false }
        emit("status")
    }

    /** Команда плееру: "toggle" | "play" | "pause" | "next" | "prev" | "seek:<мс>" | "volume:<0..1>". */
    fun command(cmd: String) = main.post {
        val js = when {
            cmd == "toggle" -> "player.togglePlay()"
            cmd == "play" -> "player.resume()"
            cmd == "pause" -> "player.pause()"
            cmd == "next" -> "player.nextTrack()"
            cmd == "prev" -> "player.previousTrack()"
            cmd.startsWith("seek:") -> "player.seek(${cmd.substringAfter(':').toLongOrNull() ?: 0})"
            cmd.startsWith("volume:") -> "player.setVolume(${cmd.substringAfter(':').toDoubleOrNull() ?: 1.0})"
            else -> return@post
        }
        web?.evaluateJavascript("window.player && $js", null)
    }

    /**
     * Невидимое окно 1×1 поверх всего (если есть разрешение «Поверх других приложений»):
     * так WebView считает себя видимым и ничего не тормозит. Без разрешения — работаем без окна.
     */
    private fun attach(w: WebView) {
        if (!Settings.canDrawOverlays(app)) {
            AuraLog.add(app, "плеер в фоне: без окна (нет «Поверх других приложений»)")
            return
        }
        try {
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                alpha = 0f
            }
            app.getSystemService(WindowManager::class.java).addView(w, lp)
            attached = true
        } catch (e: Exception) {
            AuraLog.add(app, "плеер в фоне: не удалось создать окно")
        }
    }

    private fun emit(type: String) {
        val payload = JSONObject(json()).put("type", type).toString()
        main.post { listener?.invoke(payload) }
    }

    /** Методы для player.html как window.AuraEngine.*() */
    private class Bridge {
        @JavascriptInterface
        fun token(): String = try {
            Spotify(app).accessTokenOrNull() ?: ""
        } catch (_: Exception) {
            ""
        }

        @JavascriptInterface
        fun ready(id: String) {
            deviceId = id
            status = "ready"
            error = ""
            AuraLog.add(app, "плеер в фоне: готов")
            emit("ready")
        }

        @JavascriptInterface
        fun notReady() {
            deviceId = null
            status = "offline"
            AuraLog.add(app, "плеер в фоне: отключился")
            emit("status")
        }

        @JavascriptInterface
        fun fail(kind: String, message: String) {
            AuraLog.add(app, "плеер в фоне: $kind $message")
            if (kind == "playback_error" && status == "ready") { emit("playback_error"); return }
            status = "error"
            error = when (kind) {
                "initialization_error" -> "этот телефон не поддерживает плеер Spotify"
                "authentication_error" -> "нужно войти заново"
                "account_error" -> "нужен Spotify Premium"
                "load" -> "не удалось загрузить плеер Spotify"
                else -> "ошибка плеера"
            } + if (message.isNotBlank()) " ($message)" else ""
            emit("status")
        }

        @JavascriptInterface
        fun state(json: String) {
            val st = try { if (json.isBlank()) null else JSONObject(json) } catch (_: Exception) { null }
            val now = st != null && !st.optBoolean("paused", true)
            track = st?.optString("track") ?: ""
            position = st?.optLong("position") ?: 0
            duration = st?.optLong("duration") ?: 0
            trackUri = st?.optString("uri") ?: ""
            artists = st?.optString("artists") ?: ""
            album = st?.optString("album") ?: ""
            image = st?.optString("image") ?: ""
            stateAt = android.os.SystemClock.elapsedRealtime()
            NowBar.running?.let { nb -> main.post { nb.poke() } }
            if (now != playing) {
                playing = now
                AuraLog.add(app, (if (now) "играет" else "пауза") + " · $track ${position / 60000}:${"%02d".format(position / 1000 % 60)}")
                main.post {
                    NowBar.localPlayback = now
                    if (now) NowBar.start(app)
                    NowBar.running?.poke()
                }
            }
            emit("state")
        }
    }
}

/** Короткий журнал для «Журнала плеера» в настройках Aura. */
object AuraLog {
    fun add(ctx: Context, text: String) {
        val prefs = ctx.getSharedPreferences("aura", Context.MODE_PRIVATE)
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        synchronized(this) {
            val lines = (prefs.getString("log", "") ?: "").lines().filter { it.isNotBlank() }.takeLast(59)
            prefs.edit().putString("log", (lines + "$time  [Android] $text").joinToString("\n")).apply()
        }
    }
}
