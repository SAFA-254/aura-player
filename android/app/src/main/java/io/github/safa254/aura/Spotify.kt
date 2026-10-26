package io.github.safa254.aura

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Spotify Web API без WebView — для виджета, будильника и автозапуска.
 * Вход общий со страницей: она хранит токен здесь через AuraNative.saveToken().
 * Все методы блокирующие — вызывать только из фонового потока.
 */
class Spotify(private val ctx: Context) {

    class ApiException(val status: Int, message: String) : IOException(message)

    private val prefs = ctx.getSharedPreferences("aura", Context.MODE_PRIVATE)

    fun loggedIn(): Boolean = token() != null

    fun token(): JSONObject? = prefs.getString(KEY_TOKEN, null)?.let {
        try { JSONObject(it) } catch (_: Exception) { null }
    }

    /** Свежий токен для плеера (обновит при необходимости) или null. Блокирующий вызов. */
    fun accessTokenOrNull(): String? = try { accessToken() } catch (_: Exception) { null }

    private fun accessToken(): String {
        val t = token() ?: throw ApiException(401, "Нужно войти в Aura")
        if (System.currentTimeMillis() < t.optLong("expires_at") - 60_000) return t.getString("access_token")
        return refresh(t)
    }

    private fun refresh(old: JSONObject, force: Boolean = false): String = synchronized(LOCK) {
        // Пока ждали блокировку, токен мог обновить другой поток или страница
        val t = token() ?: throw ApiException(401, "Нужно войти в Aura")
        val updated = t.optString("access_token") != old.optString("access_token")
        if ((updated || !force) && System.currentTimeMillis() < t.optLong("expires_at") - 60_000) return t.getString("access_token")
        val clientId = prefs.getString(KEY_CLIENT_ID, null) ?: throw ApiException(401, "Нужно войти в Aura")
        val body = "grant_type=refresh_token&refresh_token=" + enc(t.getString("refresh_token")) + "&client_id=" + enc(clientId)
        val conn = open("https://accounts.spotify.com/api/token", "POST")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = read(conn)
        if (code != 200) {
            if (text.contains("invalid_grant")) prefs.edit().remove(KEY_TOKEN).apply()
            throw ApiException(code, "Не удалось обновить вход")
        }
        val d = JSONObject(text)
        val saved = JSONObject()
            .put("access_token", d.getString("access_token"))
            .put("refresh_token", d.optString("refresh_token").ifEmpty { t.getString("refresh_token") })
            .put("expires_at", System.currentTimeMillis() + d.optLong("expires_in", 3600) * 1000)
            .put("v", t.opt("v"))
            .put("scope", d.optString("scope").ifEmpty { t.optString("scope") })
        prefs.edit().putString(KEY_TOKEN, saved.toString()).apply()
        return saved.getString("access_token")
    }

    fun req(method: String, path: String, body: JSONObject? = null, retry: Boolean = true): JSONObject? {
        val conn = open(API + path, method)
        conn.setRequestProperty("Authorization", "Bearer " + accessToken())
        if (body != null) {
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
        } else if (method != "GET") {
            conn.setFixedLengthStreamingMode(0)
            conn.doOutput = true
        }
        val code = conn.responseCode
        if (code == 401 && retry) {
            token()?.let { refresh(it, force = true) }
            return req(method, path, body, false)
        }
        val text = read(conn)
        if (code !in 200..299) throw ApiException(code, "Spotify: ошибка $code")
        return if (text.isBlank()) null else JSONObject(text)
    }

    fun state(): JSONObject? = req("GET", "/me/player?additional_types=episode")

    fun togglePlay() {
        val s = state()
        if (s?.optBoolean("is_playing") == true) req("PUT", "/me/player/pause") else play()
    }

    fun next() = withWake { req("POST", "/me/player/next") }
    fun prev() = withWake { req("POST", "/me/player/previous") }

    /**
     * Продолжить музыку или включить context_uri. onPhone — обязательно на этом телефоне
     * (для будильника), иначе там, где Spotify играл последним. Спящий Spotify будим.
     */
    fun play(contextUri: String? = null, shuffle: Boolean = false, onPhone: Boolean = false) {
        val body = contextUri?.let { JSONObject().put("context_uri", it) }
        if (!onPhone) {
            try {
                req("PUT", "/me/player/play", body)
                return
            } catch (e: ApiException) {
                if (e.status != 404) throw e
            }
        }
        // Сначала свой плеер Aura, и только если его нет — приложение Spotify
        playOnEngine(body, shuffle) ?: playOnPhone(body, shuffle)
            ?: throw ApiException(404, "Spotify на телефоне не отвечает")
    }

    /**
     * device_id плеера Aura (фоновая служба), запустив его при необходимости.
     * Из фона Android разрешает запуск не всегда — тогда null.
     */
    fun engineDevice(timeoutMs: Long = 9000): String? {
        if (!NowBar.engineEnabled(ctx)) return null
        Engine.deviceId?.let { return it }
        Handler(Looper.getMainLooper()).post { NowBar.start(ctx) }
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            Thread.sleep(500)
            Engine.deviceId?.let { return it }
        }
        return null
    }

    /** Включить музыку в плеере Aura и дождаться, что она заиграла. Возвращает device_id или null. */
    fun playOnEngine(body: JSONObject?, shuffle: Boolean, timeoutMs: Long = 9000): String? {
        val id = engineDevice(timeoutMs) ?: return null
        tryPlay(id, body, shuffle)
        val until = SystemClock.elapsedRealtime() + 6000
        while (SystemClock.elapsedRealtime() < until) {
            if (Engine.playing) return id
            Thread.sleep(400)
        }
        return null
    }

    private fun withWake(action: () -> Unit) {
        try {
            action()
        } catch (e: ApiException) {
            if (e.status != 404) throw e
            (playOnEngine(null, false) ?: wakePhone()) ?: throw e
            action()
        }
    }

    /** Разбудить Spotify и продолжить музыку на телефоне. Возвращает device_id или null. */
    fun wakePhone(timeoutMs: Long = 12_000): String? = playOnPhone(null, false, timeoutMs)

    /**
     * Включает музыку на телефоне и проверяет, что она действительно заиграла:
     * уснувший Spotify может числиться в устройствах, но не реагировать на команды.
     */
    fun playOnPhone(body: JSONObject?, shuffle: Boolean, timeoutMs: Long = 12_000): String? {
        phoneDevice()?.let { id ->
            tryPlay(id, body, shuffle)
            waitPlaying(3500)?.let { return it }
        }
        // Будим кнопкой «Play», как это делают проводные наушники
        pressPlay()
        val id = waitPhone(timeoutMs) ?: return null
        if (body != null || shuffle) tryPlay(id, body, shuffle)
        waitPlaying(5000)?.let { return it }
        tryPlay(id, body, shuffle)
        return waitPlaying(4000)
    }

    fun pressPlay() {
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val i = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setPackage(SPOTIFY_PACKAGE)
                // Без этого флага Android не доставит сигнал Spotify, если его остановила система
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY))
            ctx.sendBroadcast(i)
        }
    }

    fun tryPlay(id: String, body: JSONObject?, shuffle: Boolean) {
        if (shuffle) try { req("PUT", "/me/player/shuffle?state=true&device_id=$id") } catch (_: Exception) {}
        try { req("PUT", "/me/player/play?device_id=$id", body) } catch (_: Exception) {}
    }

    /** Ждёт, пока телефон появится в списке устройств Spotify. */
    fun waitPhone(timeoutMs: Long): String? {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            Thread.sleep(1200)
            phoneDevice()?.let { return it }
        }
        return null
    }

    /** Ждёт, пока на телефоне реально заиграет трек. Возвращает device_id телефона. */
    fun waitPlaying(timeoutMs: Long): String? {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val s = try { state() } catch (_: Exception) { null }
            val dev = s?.optJSONObject("device")
            if (s?.optJSONObject("item") != null && s.optBoolean("is_playing") && dev?.optString("type") == "Smartphone") {
                return dev.optString("id")
            }
            if (SystemClock.elapsedRealtime() >= until) return null
            Thread.sleep(1000)
        }
    }

    private fun phoneDevice(): String? {
        val list = req("GET", "/me/player/devices")?.optJSONArray("devices") ?: return null
        var phone: String? = null
        for (k in 0 until list.length()) {
            val d = list.getJSONObject(k)
            if (d.optString("type") != "Smartphone" || d.optBoolean("is_restricted")) continue
            if (d.optBoolean("is_active")) return d.getString("id")
            if (phone == null) phone = d.getString("id")
        }
        return phone
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
        }

    private fun read(conn: HttpURLConnection): String = try {
        (if (conn.responseCode < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
    } catch (_: IOException) {
        ""
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val API = "https://api.spotify.com/v1"
        const val SPOTIFY_PACKAGE = "com.spotify.music"
        const val KEY_TOKEN = "token"
        const val KEY_CLIENT_ID = "clientId"
        private val LOCK = Any()
    }
}
