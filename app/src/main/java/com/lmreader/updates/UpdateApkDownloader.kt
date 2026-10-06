package com.lmreader.updates

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.lmreader.core.api.ReleaseApk
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class ApkTransfer(val completed: Long, val total: Long, val finished: Boolean, val failed: Boolean)

class UpdateApkDownloader(private val context: Context) {
    private val manager get() = context.getSystemService(DownloadManager::class.java)
    private val folder get() = File(checkNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)), "updates").apply { mkdirs() }
    fun newFile(version: String) = fileFor("LM-Reader-${version.replace(Regex("[^a-zA-Z0-9._-]"), "_")}-${UUID.randomUUID()}.apk")
    fun fileFor(name: String): File {
        require(name.isNotBlank() && name == File(name).name && name.endsWith(".apk"))
        return File(folder, name)
    }
    fun enqueue(apk: ReleaseApk, file: File): Long = manager.enqueue(DownloadManager.Request(Uri.parse(apk.url))
        .setTitle(apk.name).setMimeType("application/vnd.android.package-archive")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true).setDestinationUri(Uri.fromFile(file)))
    fun remove(id: Long) { manager.remove(id) }
    fun query(id: Long): ApkTransfer = manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
        if(!cursor.moveToFirst()) return@use ApkTransfer(0, 0, false, true)
        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        ApkTransfer(cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            status == DownloadManager.STATUS_SUCCESSFUL, status == DownloadManager.STATUS_FAILED)
    }

    @Suppress("DEPRECATION")
    fun verify(file: File, apk: ReleaseApk, version: String) {
        require(file.isFile && file.length() == apk.bytes) { "Incomplete package" }
        apk.sha256?.let { expected ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while(true) { val size = input.read(buffer); if(size < 0) break; digest.update(buffer, 0, size) } }
            require(digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, true)) { "Package hash mismatch" }
        }
        val flags = if(Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val downloaded = checkNotNull(context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)) { "Invalid APK" }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        require(downloaded.packageName == context.packageName && downloaded.versionName == version.removePrefix("v")) { "Wrong package/version" }
        fun code(info: PackageInfo) = if(Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        require(code(downloaded) > code(installed)) { "Package is not an upgrade" }
        fun signatures(info: PackageInfo): Set<String> {
            val certificates = if(Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return certificates.orEmpty().map { certificate -> MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        }
        val trusted = signatures(installed)
        require(trusted.isNotEmpty() && signatures(downloaded) == trusted) { "Wrong signing certificate" }
    }

    fun installerIntent(file: File): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(FileProvider.getUriForFile(context, context.packageName + ".updates", file), "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
