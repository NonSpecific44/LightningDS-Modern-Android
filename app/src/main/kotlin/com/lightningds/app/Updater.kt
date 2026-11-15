package com.lightningds.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.lightningds.core.update.Versions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.coroutines.coroutineContext

data class AppUpdate(val version: String, val notes: String, val apkUrl: String, val apkName: String, val size: Long, val pageUrl: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AppUpdate) : UpdateState
    data class Downloading(val update: AppUpdate, val progress: Float) : UpdateState
    data class Installing(val update: AppUpdate) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Updates the app from this project's GitHub releases. Works when the repository is public
 * (the releases API isn't available anonymously for private repositories).
 */
class Updater(private val context: Context, private val http: OkHttpClient) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String = BuildConfigCompat.versionName(context)
    private val isDebugBuild = context.packageName.endsWith(".debug")

    fun dismiss() { _state.value = UpdateState.Idle }

    /** Checks GitHub; [quiet] keeps failures and "up to date" out of the UI (automatic checks). */
    suspend fun check(quiet: Boolean): AppUpdate? {
        if (!quiet) _state.value = UpdateState.Checking
        return try {
            val update = fetchLatest()
            _state.value = when {
                update != null -> UpdateState.Available(update)
                quiet -> UpdateState.Idle
                else -> UpdateState.UpToDate
            }
            update
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = if (quiet) UpdateState.Idle else UpdateState.Failed(e.message ?: context.getString(R.string.update_failed))
            null
        }
    }

    private suspend fun fetchLatest(): AppUpdate? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "LightningDS-Android/$currentVersion")
            .build()
        val body = http.newCall(req).execute().use { r ->
            if (r.code == 404) error(context.getString(R.string.update_not_public))
            if (!r.isSuccessful) error("GitHub: HTTP ${r.code}")
            r.body!!.string()
        }
        val j = Json.parseToJsonElement(body) as JsonObject
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        val tag = j.str("tag_name") ?: return@withContext null
        if (!Versions.isNewer(tag, currentVersion)) return@withContext null
        val assets = (j["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { it.str("name")?.endsWith(".apk", true) == true }
        val asset = assets.firstOrNull { (it.str("name")!!.contains("-debug", true)) == isDebugBuild } ?: return@withContext null
        AppUpdate(
            version = tag.removePrefix("v"),
            notes = j.str("body").orEmpty(),
            apkUrl = asset.str("browser_download_url") ?: return@withContext null,
            apkName = asset.str("name")!!,
            size = (asset["size"] as? JsonPrimitive)?.longOrNull ?: 0,
            pageUrl = j.str("html_url").orEmpty(),
        )
    }

    /** Downloads the APK and hands it to Android's installer, which asks the user to confirm. */
    suspend fun downloadAndInstall(update: AppUpdate) {
        try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val apk = File(dir, update.apkName)
            withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(update.apkUrl).header("User-Agent", "LightningDS-Android/$currentVersion").build()).execute().use { r ->
                    if (!r.isSuccessful) error("Download failed: HTTP ${r.code}")
                    val total = r.body!!.contentLength().takeIf { it > 0 } ?: update.size
                    r.body!!.byteStream().use { input ->
                        apk.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var read = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                read += n
                                if (total > 0) _state.value = UpdateState.Downloading(update, (read.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            _state.value = UpdateState.Installing(update)
            withContext(Dispatchers.IO) { install(apk) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: context.getString(R.string.update_failed))
        }
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("app.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) } ; session.fsync(out) }
            val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    internal fun onInstallResult(status: Int, message: String?) {
        val current = _state.value
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.Idle
            else -> _state.value = UpdateState.Failed(message ?: context.getString(R.string.update_failed))
        }
        if (current is UpdateState.Installing && status == PackageInstaller.STATUS_FAILURE_ABORTED) _state.value = UpdateState.Available(current.update)
    }

    companion object {
        const val REPO = "NonSpecific44/LightningDS-Modern-Android"
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}

/** Receives the installer's status; shows Android's confirmation screen when it asks for one. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
        }
        (context.applicationContext as? LightningApp)?.controller?.updater?.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
