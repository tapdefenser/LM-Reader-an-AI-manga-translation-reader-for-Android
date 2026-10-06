package com.lmreader.updates

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.ui.i18n.Text
import java.util.Locale

@Composable
fun UpdateDialog(updates: AppUpdates) {
    val state by updates.state.collectAsStateWithLifecycle()
    val release = state.offer ?: return
    val context = LocalContext.current
    val apk = updates.compatibleApk(release)
    val transfer = state.download?.takeIf { it.version == release.version }
    var installError by remember(release.version) { mutableStateOf<String?>(null) }
    fun install() {
        val file = transfer?.file ?: return
        try { context.startActivity(UpdateApkDownloader(context).installerIntent(file)); installError = null }
        catch(_: ActivityNotFoundException) { installError = "无法打开安装程序，请稍后重试" }
        catch(_: Exception) { installError = "安装包无法打开，请重新下载" }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(context.packageManager.canRequestPackageInstalls()) install()
        else installError = "请允许本应用安装更新包后重试"
    }
    AlertDialog(onDismissRequest = updates::dismiss, title = { Text("发现新版本") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${updates.currentVersion} → ${release.version}", localize = false)
                Text("更新日志", style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Column {
                        if(release.notes.isBlank()) Text("此版本未提供更新日志")
                        else Text(release.notes, localize = false, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if(apk == null) Text("此版本没有适用于本设备的安装包，可查看发行页面")
                else if(transfer == null) Text(String.format(Locale.ROOT, "%.1f MB", apk.bytes / 1048576.0), localize = false)
                when(transfer?.phase) {
                    UpdateDownloadPhase.DOWNLOADING -> {
                        if(transfer.total > 0) LinearProgressIndicator(progress = { (transfer.completed.toDouble() / transfer.total).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(String.format(Locale.ROOT, "%.1f / %.1f MB", transfer.completed / 1048576.0, transfer.total / 1048576.0), localize = false)
                    }
                    UpdateDownloadPhase.VERIFYING -> Text("正在校验安装包")
                    UpdateDownloadPhase.READY -> Text("安装包已下载并校验通过")
                    UpdateDownloadPhase.FAILED -> transfer.error?.let { Text(it) }
                    null -> Unit
                }
                installError?.let { Text(it) }
                TextButton(onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.page))) }
                    catch(_: ActivityNotFoundException) { installError = "无法打开浏览器，请手动访问 GitHub" }
                }) { Text("查看发行页面") }
            }
        },
        confirmButton = {
            if(apk != null) TextButton(enabled = transfer?.phase !in setOf(UpdateDownloadPhase.DOWNLOADING, UpdateDownloadPhase.VERIFYING), onClick = {
                if(transfer?.phase == UpdateDownloadPhase.READY) {
                    if(context.packageManager.canRequestPackageInstalls()) install()
                    else permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                } else updates.download(release)
            }) { Text(when(transfer?.phase) {
                UpdateDownloadPhase.DOWNLOADING -> "正在下载"
                UpdateDownloadPhase.VERIFYING -> "正在校验"
                UpdateDownloadPhase.READY -> "安装更新"
                UpdateDownloadPhase.FAILED -> "重新下载"
                null -> "下载更新安装包"
            }) }
        }, dismissButton = { TextButton(onClick = updates::dismiss) { Text("稍后再说") } })
}
