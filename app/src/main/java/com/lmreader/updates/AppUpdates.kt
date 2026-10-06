package com.lmreader.updates

import android.content.Context
import android.os.Build
import com.lmreader.core.api.GitHubUpdates
import com.lmreader.core.api.ReleaseApk
import com.lmreader.core.api.ProjectLinks
import com.lmreader.core.api.UpdateStatus
import com.lmreader.core.storage.settings.UpdatePreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrl

enum class UpdateDownloadPhase { DOWNLOADING, VERIFYING, READY, FAILED }
data class UpdateDownload(val version: String, val phase: UpdateDownloadPhase,
    val completed: Long = 0, val total: Long = 0, val file: File? = null, val error: String? = null)
data class AppUpdateState(val checking: Boolean = false, val result: UpdateStatus? = null,
    val error: String? = null, val offer: UpdateStatus.Release? = null, val download: UpdateDownload? = null)

/** One app-owned check/dialog/download state is shared by startup and About. */
class AppUpdates(private val context: Context, private val scope: CoroutineScope,
    val preferences: UpdatePreferences = UpdatePreferences(context),
    private val checkRelease: suspend (String) -> UpdateStatus = GitHubUpdates()::check,
    private val downloader: UpdateApkDownloader = UpdateApkDownloader(context),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    private val _state = MutableStateFlow(AppUpdateState())
    val state = _state.asStateFlow()
    private var visible = false
    private var downloadedRelease: UpdateStatus.Release? = null
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    init {
        val store = preferences.store
        val id = store.getLong("download-id", -1)
        val version = store.getString("download-version", null)
        if(id >= 0 && version != null) {
            if(version.removePrefix("v") == currentVersion.removePrefix("v")) {
                // The installed update no longer needs its downloaded package.
                scope.launch(Dispatchers.IO) { runCatching { downloader.remove(id) } }
                clearDownloadRecord()
            }
            else runCatching {
                val apk = ReleaseApk(store.getString("download-name", "")!!, store.getString("download-url", "")!!,
                    store.getLong("download-size", 0), store.getString("download-sha", null))
                val file = downloader.fileFor(store.getString("download-file", "")!!)
                downloadedRelease = UpdateStatus.Release(version, ProjectLinks.RELEASES.toHttpUrl().newBuilder().addPathSegment("tag").addPathSegment(version).build().toString(),
                    true, store.getString("download-notes", "").orEmpty(), listOf(apk))
                downloadJob = scope.launch { followDownload(id, version, apk, file) }
            }.onFailure { clearDownloadRecord() }
        }
    }

    /** Recreating an activity does not count as another app launch. */
    fun onAppLaunch() {
        if(visible) return
        visible = true
        if(preferences.frequency.value.isDue(preferences.lastAttempt, clock())) check()
    }
    fun onAppBackground(changingConfigurations: Boolean) { if(!changingConfigurations) visible = false }

    fun check() {
        if(checkJob?.isActive == true) return
        preferences.attempted(clock())
        _state.update { it.copy(checking = true, result = null, error = null) }
        checkJob = scope.launch {
            try {
                val result = checkRelease(currentVersion)
                _state.update { it.copy(result = result, offer = (result as? UpdateStatus.Release)?.takeIf { release -> release.isNewer == true } ?: it.offer) }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { _state.update { it.copy(error = "检查更新失败，请检查网络后重试") } }
            finally { _state.update { it.copy(checking = false) } }
        }
    }

    fun dismiss() { _state.update { it.copy(offer = null) } }
    fun showAvailable() {
        val result = state.value.result as? UpdateStatus.Release ?: return
        if(result.isNewer == true) _state.update { it.copy(offer = result) }
    }
    fun showDownload() { downloadedRelease?.let { release -> _state.update { it.copy(offer = release) } } }
    fun compatibleApk(release: UpdateStatus.Release) = release.apkFor(Build.SUPPORTED_ABIS.toList())

    fun download(release: UpdateStatus.Release) {
        if(downloadJob?.isActive == true) return
        val apk = compatibleApk(release) ?: return
        downloadedRelease = release
        _state.update { it.copy(download = UpdateDownload(release.version, UpdateDownloadPhase.DOWNLOADING, total = apk.bytes)) }
        downloadJob = scope.launch {
            try {
                val old = preferences.store.getLong("download-id", -1)
                if(old >= 0) withContext(Dispatchers.IO) { downloader.remove(old) }
                val file = downloader.newFile(release.version)
                val id = withContext(Dispatchers.IO) { downloader.enqueue(apk, file) }
                preferences.store.edit().putLong("download-id", id).putString("download-version", release.version)
                    .putString("download-name", apk.name).putString("download-url", apk.url).putLong("download-size", apk.bytes)
                    .putString("download-sha", apk.sha256).putString("download-file", file.name).putString("download-notes", release.notes).apply()
                followDownload(id, release.version, apk, file)
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { _state.update { it.copy(download = UpdateDownload(release.version, UpdateDownloadPhase.FAILED, error = "下载失败，请检查网络和存储空间后重试")) } }
        }
    }

    private suspend fun followDownload(id: Long, version: String, apk: ReleaseApk, file: File) {
        try {
            while(currentCoroutineContext().isActive) {
                val progress = withContext(Dispatchers.IO) { downloader.query(id) }
                if(progress.failed) error("Download failed")
                if(progress.finished) {
                    _state.update { it.copy(download = UpdateDownload(version, UpdateDownloadPhase.VERIFYING, apk.bytes, apk.bytes)) }
                    withContext(Dispatchers.IO) { downloader.verify(file, apk, version) }
                    _state.update { it.copy(download = UpdateDownload(version, UpdateDownloadPhase.READY, apk.bytes, apk.bytes, file)) }
                    return
                }
                _state.update { it.copy(download = UpdateDownload(version, UpdateDownloadPhase.DOWNLOADING, progress.completed, progress.total.takeIf { total -> total > 0 } ?: apk.bytes)) }
                delay(1000)
            }
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) {
            _state.update { it.copy(download = UpdateDownload(version, UpdateDownloadPhase.FAILED,
                error = "下载或校验失败，请重新下载安装包")) }
        }
    }

    private fun clearDownloadRecord() { preferences.store.edit().remove("download-id").remove("download-version").apply() }
}
