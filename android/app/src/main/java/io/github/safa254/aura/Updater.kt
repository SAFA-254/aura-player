package io.github.safa254.aura

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Обновления внутри Aura: смотрит последний релиз на GitHub и ставит его
 * через системный установщик — без ручного скачивания APK.
 */
object Updater {
    private const val RELEASES = "https://api.github.com/repos/SAFA-254/aura-player/releases/latest"

    fun currentVersion(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    /** {available, version, current, url, notes} или {error}. Блокирующий вызов. */
    fun check(ctx: Context): JSONObject = try {
        val conn = (URL(RELEASES).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        val d = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        // Теги вида v1.0.12, а versionCode — то же последнее число
        val tag = d.optString("tag_name")
        val latest = tag.substringAfterLast('.').toLongOrNull() ?: 0
        val assets = d.optJSONArray("assets")
        var url = ""
        for (k in 0 until (assets?.length() ?: 0)) {
            val a = assets!!.getJSONObject(k)
            if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
        }
        val current = currentVersion(ctx)
        JSONObject()
            .put("available", latest > current && url.isNotEmpty())
            .put("version", tag.removePrefix("v"))
            .put("current", "1.0.$current")
            .put("url", url)
    } catch (e: Exception) {
        JSONObject().put("error", "Не удалось проверить обновления")
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Скачивает APK (progress 0..100) и запускает установку. Блокирующий вызов. */
    fun install(ctx: Context, url: String, progress: (Int) -> Unit) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                session.openWrite("aura.apk", 0, if (total > 0) total else -1).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != shown) { shown = pct; progress(pct) }
                    }
                    session.fsync(out)
                }
            }
            val status = PendingIntent.getBroadcast(
                ctx, 0, Intent(ctx, Status::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(status.intentSender)
        }
    }

    /** Ответ установщика: показать окно подтверждения или сообщить об ошибке. */
    class Status : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> {}
                PackageInstaller.STATUS_FAILURE_ABORTED -> {}
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                    Toast.makeText(ctx, "Не удалось обновить Aura. $msg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
