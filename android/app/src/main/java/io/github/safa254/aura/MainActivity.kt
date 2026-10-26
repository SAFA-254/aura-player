package io.github.safa254.aura

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * Aura для Android: тот же плеер с сайта внутри WebView плюс то, чего нет у браузера, —
 * модель и заряд Bluetooth-наушников, цвет системных панелей, «поделиться» картинкой.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var headphones: Headphones
    private var pendingMic: PermissionRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        catchCrashes()
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#120e18"))
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "$userAgentString AuraApp/1"
        }
        web.addJavascriptInterface(Bridge(), "AuraNative")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (isOwnPage(request.url)) return false
                openExternal(request.url.toString())
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { onMicRequest(request) }
            }
        }

        headphones = Headphones(this) { json ->
            runOnUiThread { web.evaluateJavascript("window.auraHeadphones && window.auraHeadphones($json)", null) }
        }

        web.loadUrl(pageFor(intent))
        AuraWidget.schedulePeriodic(this)
        try { Alarm.schedule(this) } catch (_: Exception) {}
        showLastCrash()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !headphones.allowed()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BLUETOOTH)
        } else {
            headphones.start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Вернулись из браузера после входа в Spotify
        if (intent.data?.scheme == "aura") web.loadUrl(pageFor(intent))
    }

    // Если Aura упала — запоминаем причину и показываем её при следующем запуске
    private fun catchCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val text = "Aura ${packageManager.getPackageInfo(packageName, 0).versionName}, Android ${Build.VERSION.RELEASE}\n" +
                    android.util.Log.getStackTraceString(e)
                getSharedPreferences("aura", MODE_PRIVATE).edit().putString("crash", text).commit()
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, e)
        }
    }

    private fun showLastCrash() {
        val crash = prefs.getString("crash", null) ?: return
        prefs.edit().remove("crash").apply()
        AlertDialog.Builder(this)
            .setTitle("Aura закрылась с ошибкой")
            .setMessage("Скопируй текст и отправь разработчику — так проще всё починить.\n\n" + crash.take(1500))
            .setPositiveButton("Скопировать") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Aura", crash))
                Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun pageFor(intent: Intent?): String {
        val data = intent?.data
        return if (data?.scheme == "aura" && data.host == "auth" && data.encodedQuery != null) {
            "$HOME?${data.encodedQuery}"
        } else {
            HOME
        }
    }

    private fun isOwnPage(uri: Uri): Boolean =
        uri.scheme == "https" && uri.host == HOST && (uri.path ?: "").startsWith(PATH)

    private fun openExternal(url: String) {
        try {
            val intent = if (url.startsWith("intent:")) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "Нечем открыть ссылку", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onMicRequest(request: PermissionRequest) {
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE !in request.resources) {
            request.deny()
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            pendingMic?.deny()
            pendingMic = request
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_BLUETOOTH -> headphones.start()
            REQ_NOTIFY -> {}
            REQ_MIC -> {
                pendingMic?.let { if (granted) it.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else it.deny() }
                pendingMic = null
            }
        }
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        NowBar.start(this)
        // Разрешение могли выдать в настройках, пока Aura была свёрнута
        headphones.start()
    }

    override fun onPause() {
        web.onPause()
        AuraWidget.refreshAsync(this)
        super.onPause()
    }

    override fun onDestroy() {
        headphones.stop()
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Шторки в Aura закрываются через историю страницы
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun shareImage(dataUrl: String, title: String): Boolean {
        val bytes = Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "aura-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Aura")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, title))
        return true
    }

    /** Методы, которые страница вызывает как window.AuraNative.*() */
    inner class Bridge {
        @JavascriptInterface
        fun headphones(): String = headphones.json()

        @JavascriptInterface
        fun openExternal(url: String) = runOnUiThread { this@MainActivity.openExternal(url) }

        @JavascriptInterface
        fun setBarColor(status: String, nav: String) = runOnUiThread {
            try {
                window.statusBarColor = Color.parseColor(status)
                window.navigationBarColor = Color.parseColor(nav)
            } catch (_: IllegalArgumentException) {
            }
        }

        @JavascriptInterface
        fun keepScreenOn(on: Boolean) = runOnUiThread {
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        @JavascriptInterface
        fun shareImage(dataUrl: String, title: String): Boolean = try {
            this@MainActivity.shareImage(dataUrl, title)
        } catch (_: Exception) {
            false
        }

        // Вход в Spotify хранится в приложении, чтобы им пользовались виджет, будильник и автозапуск
        @JavascriptInterface
        fun getToken(): String = prefs.getString(Spotify.KEY_TOKEN, null) ?: ""

        @JavascriptInterface
        fun saveToken(json: String) {
            if (json.isBlank()) prefs.edit().remove(Spotify.KEY_TOKEN).apply()
            else prefs.edit().putString(Spotify.KEY_TOKEN, json).apply()
        }

        @JavascriptInterface
        fun setClientId(id: String) = prefs.edit().putString(Spotify.KEY_CLIENT_ID, id).apply()

        @JavascriptInterface
        fun nowPlaying(json: String) {
            AuraWidget.fromPage(this@MainActivity, json)
            NowBar.running?.let { runOnUiThread { it.poke() } }
        }

        // Карточка Aura в Now Bar через интернет
        @JavascriptInterface
        fun getNowBar(): Boolean = NowBar.enabled(this@MainActivity)

        @JavascriptInterface
        fun setNowBar(on: Boolean) {
            askNotifications()
            runOnUiThread { NowBar.setEnabled(this@MainActivity, on) }
        }

        // Остальные настройки Sony и Galaxy Buds; ответ придёт в window.auraHeadset()
        @JavascriptInterface
        fun headset(json: String) {
            val dev = headphones.current()
            Thread {
                val res = try {
                    val cmd = org.json.JSONObject(json)
                    when {
                        dev == null -> org.json.JSONObject().put("ok", false).put("error", "Наушники не подключены")
                        SonyAnc.isSony(dev) -> SonyAnc.command(dev, cmd)
                        else -> BudsAnc.command(dev, cmd)
                    }.put("op", cmd.optString("op"))
                } catch (e: Exception) {
                    org.json.JSONObject().put("ok", false).put("error", e.message ?: "Ошибка")
                }
                runOnUiThread { web.evaluateJavascript("window.auraHeadset && window.auraHeadset($res)", null) }
            }.start()
        }

        // Обновления: ответы придут в window.auraUpdate() и window.auraUpdateProgress()
        @JavascriptInterface
        fun checkUpdate() {
            Thread {
                val res = Updater.check(this@MainActivity)
                runOnUiThread { web.evaluateJavascript("window.auraUpdate && window.auraUpdate($res)", null) }
            }.start()
        }

        @JavascriptInterface
        fun canInstall(): Boolean = Updater.canInstall(this@MainActivity)

        @JavascriptInterface
        fun openInstallSettings() = openSettings(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
        )

        @JavascriptInterface
        fun installUpdate(url: String) {
            Thread {
                val js = { code: String -> runOnUiThread { web.evaluateJavascript(code, null) } }
                try {
                    Updater.install(this@MainActivity, url) { pct -> js("window.auraUpdateProgress && window.auraUpdateProgress($pct)") }
                } catch (e: Exception) {
                    js("window.auraUpdateProgress && window.auraUpdateProgress(-1)")
                }
            }.start()
        }

        @JavascriptInterface
        fun getAlarm(): String = Alarm.get(this@MainActivity).toString()

        /** Возвращает время следующего звонка в мс (строкой) или "0". */
        @JavascriptInterface
        fun setAlarm(json: String): String {
            askNotifications()
            return Alarm.set(this@MainActivity, json).toString()
        }

        @JavascriptInterface
        fun getAuto(): String = AutoStart.get(this@MainActivity).toString()

        @JavascriptInterface
        fun setAuto(json: String) {
            askNotifications()
            AutoStart.set(this@MainActivity, json)
        }

        @JavascriptInterface
        fun overlayAllowed(): Boolean = Settings.canDrawOverlays(this@MainActivity)

        @JavascriptInterface
        fun openOverlaySettings() = runOnUiThread {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
            }
        }

        /**
         * Поднять Spotify на телефоне, чтобы Aura могла играть без ручного запуска Spotify.
         * Сначала будим в фоне кнопкой «Play»; не вышло — на секунду открываем Spotify и возвращаемся.
         * Ответ — device_id телефона или null — придёт в window.auraWoke().
         */
        @JavascriptInterface
        fun wakeSpotify() {
            Thread {
                val sp = Spotify(this@MainActivity)
                var id = try { sp.playOnPhone(null, false, 7000) } catch (_: Exception) { null }
                val launch = packageManager.getLaunchIntentForPackage(Spotify.SPOTIFY_PACKAGE)
                if (id == null && launch != null) {
                    runOnUiThread { try { startActivity(launch) } catch (_: Exception) {} }
                    val dev = try { sp.waitPhone(12_000) } catch (_: Exception) { null }
                    // Возвращаемся сразу, как Spotify запустился: так Android охотнее пускает обратно.
                    // Из фона гарантированно можно только с разрешением «Поверх других приложений».
                    runOnUiThread {
                        try {
                            startActivity(
                                Intent(this@MainActivity, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                            )
                        } catch (_: Exception) {
                        }
                    }
                    if (dev != null) {
                        sp.tryPlay(dev, null, false)
                        id = sp.waitPlaying(5000) ?: dev
                    }
                }
                val arg = if (id == null) "null" else org.json.JSONObject.quote(id)
                runOnUiThread { web.evaluateJavascript("window.auraWoke && window.auraWoke($arg)", null) }
            }.start()
        }

        // Карточка Aura в Now Bar вместо Spotify
        /** Объявлен ли в этой сборке сервис доступа к уведомлениям. */
        @JavascriptInterface
        fun mediaSupported(): Boolean = try {
            packageManager.getServiceInfo(android.content.ComponentName(this@MainActivity, MediaListener::class.java), 0)
            true
        } catch (_: Exception) {
            false
        }

        @JavascriptInterface
        fun mediaAccess(): Boolean = MediaListener.hasAccess(this@MainActivity)

        @JavascriptInterface
        fun openMediaAccess() = openSettings(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                android.content.ComponentName(this@MainActivity, MediaListener::class.java).flattenToString(),
            ),
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        )

        @JavascriptInterface
        fun openAppInfo() = openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

        @JavascriptInterface
        fun openSpotifyNotifications() = openSettings(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, Spotify.SPOTIFY_PACKAGE),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + Spotify.SPOTIFY_PACKAGE)),
        )

        @JavascriptInterface
        fun getMirror(): Boolean = MediaListener.enabled(this@MainActivity)

        @JavascriptInterface
        fun setMirror(on: Boolean) {
            askNotifications()
            MediaListener.setEnabled(this@MainActivity, on)
        }

        /** mode: "" — только узнать режим, "nc" | "ambient" | "off". Ответ придёт в window.auraSony(). */
        @JavascriptInterface
        fun sony(mode: String, level: Int, voice: String) {
            val dev = headphones.current()
            Thread {
                val res = if (dev == null) {
                    org.json.JSONObject().put("ok", false).put("error", "Наушники не подключены")
                } else {
                    SonyAnc.run(
                        dev,
                        mode.ifEmpty { null },
                        level.takeIf { it > 0 },
                        voice.takeIf { it.isNotEmpty() }?.let { it == "1" },
                    )
                }
                runOnUiThread { web.evaluateJavascript("window.auraSony && window.auraSony($res)", null) }
            }.start()
        }
    }

    private fun openSettings(vararg intents: Intent) = runOnUiThread {
        for (i in intents) {
            try {
                startActivity(i)
                return@runOnUiThread
            } catch (_: Exception) {
            }
        }
    }

    private val prefs by lazy { getSharedPreferences("aura", MODE_PRIVATE) }

    private fun askNotifications() = runOnUiThread {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
    }


    companion object {
        const val HOST = "safa-254.github.io"
        const val PATH = "/aura-player"
        const val HOME = "https://$HOST$PATH/"
        const val REQ_BLUETOOTH = 1
        const val REQ_MIC = 2
        const val REQ_NOTIFY = 3
    }
}
