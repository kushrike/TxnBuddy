package com.kushrike.txnbuddy

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * In-app updates from the public GitHub releases. This is the only file allowed to touch the network (CI fails the
 * build if another one does). It only downloads: the release list and the APK. Nothing from the app is sent, and the
 * User-Agent is fixed so the phone model isn't either.
 * Android installs the APK only if it's signed with the same key as this app, so a tampered file can't replace it.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/kushrike/TxnBuddy/releases/latest"
    private const val ACTION = "com.kushrike.txnbuddy.INSTALL_STATUS"

    data class Release(val version: String, val code: Long, val apk: String)

    var latest by mutableStateOf<Release?>(null); private set // only set when newer than this build
    var busy by mutableStateOf(false); private set
    var progress by mutableStateOf<Float?>(null); private set
    var status by mutableStateOf<String?>(null); private set
    var resumeInstall = false // waiting for the user to allow "Install unknown apps" (MainActivity.onResume)

    private fun get(url: String) = (URL(url).openConnection() as HttpsURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000; useCaches = false
        setRequestProperty("User-Agent", "TxnBuddy")
    }

    /** Returns the newest release if it's newer than this build. */
    suspend fun check(): Release? {
        busy = true; status = null
        val r = runCatching { withContext(Dispatchers.IO) { parse(get(LATEST).inputStream.bufferedReader().use { it.readText() }) } }
        latest = r.getOrNull()?.takeIf { it.code > BuildConfig.VERSION_CODE }
        status = if (r.isFailure) "Couldn't reach GitHub" else if (latest == null) "You're on the latest version" else null
        Store.lastUpdateCheck = System.currentTimeMillis()
        busy = false
        return latest
    }

    // Tags are v1.0.<versionCode> (see .github/workflows/build.yml).
    private fun parse(json: String): Release? {
        val j = JSONObject(json)
        val version = j.getString("tag_name").removePrefix("v")
        val assets = j.getJSONArray("assets")
        val apk = (0 until assets.length()).map(assets::getJSONObject).firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
        return Release(version, version.substringAfterLast('.').toLongOrNull() ?: return null, apk.getString("browser_download_url"))
    }

    /** Streams the APK into a system install session. Android then shows its own "Update TxnBuddy?" dialog. */
    suspend fun install(activity: MainActivity) {
        val r = latest ?: return
        resumeInstall = false
        if (!activity.packageManager.canRequestPackageInstalls()) { // one-time system permission
            resumeInstall = true; activity.expectingReturn = true
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        busy = true; status = null; progress = 0f
        if (Nav.tab != 3) Nav.snack = Snack("Downloading TxnBuddy ${r.version}…") // progress row is in Settings
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(activity.packageName)
            // Lets Android skip the dialog once TxnBuddy is its own installer (after the first in-app update).
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        runCatching {
            installer.openSession(id).use { s ->
                withContext(Dispatchers.IO) {
                    val c = get(r.apk)
                    val total = c.contentLengthLong
                    c.inputStream.use { input ->
                        s.openWrite("TxnBuddy.apk", 0, total).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf).takeIf { it >= 0 } ?: break
                                out.write(buf, 0, n); done += n
                                if (total > 0) progress = done.toFloat() / total
                            }
                            s.fsync(out)
                        }
                    }
                }
                listen(activity)
                val intent = Intent(ACTION).setPackage(activity.packageName)
                s.commit(PendingIntent.getBroadcast(activity, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).intentSender)
            }
        }.onFailure {
            runCatching { installer.abandonSession(id) }
            status = "Download failed. Try again."
        }
        busy = false; progress = null
    }

    private fun listen(activity: MainActivity) {
        ContextCompat.registerReceiver(activity, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                        activity.expectingReturn = true
                        activity.startActivity(confirm)
                        return // the final result comes in a second broadcast
                    }
                    PackageInstaller.STATUS_SUCCESS -> {} // Android closes the app to replace it; it opens on the new version
                    PackageInstaller.STATUS_FAILURE_ABORTED -> {} // cancelled
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        status = "This copy is signed with a different key. Back up, uninstall, and install the release once."
                    else -> status = "Install failed: ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}"
                }
                activity.unregisterReceiver(this)
            }
        }, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
