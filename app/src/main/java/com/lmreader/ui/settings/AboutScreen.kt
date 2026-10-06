package com.lmreader.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.core.api.ProjectLinks
import com.lmreader.core.api.UpdateStatus
import com.lmreader.core.model.UpdateCheckFrequency
import com.lmreader.di.AppContainer
import com.lmreader.updates.AppUpdates
import com.lmreader.updates.UpdateDownloadPhase
import com.lmreader.ui.i18n.Icon
import com.lmreader.ui.i18n.Text

internal fun updateFrequencyLabel(value: UpdateCheckFrequency): String = when(value) {
    UpdateCheckFrequency.EVERY_LAUNCH -> "每次启动时"
    UpdateCheckFrequency.DAILY -> "每日首次启动时"
    UpdateCheckFrequency.EVERY_THREE_DAYS -> "每三日"
    UpdateCheckFrequency.OFF -> "关闭"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(updates: AppUpdates? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val coordinator = updates ?: AppContainer.from(context).appUpdates
    val state by coordinator.state.collectAsStateWithLifecycle()
    val frequency by coordinator.preferences.frequency.collectAsStateWithLifecycle()
    var choosingFrequency by remember { mutableStateOf(false) }
    var browserFailed by remember { mutableStateOf(false) }
    fun openPage(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            browserFailed = false
        } catch (_: ActivityNotFoundException) { browserFailed = true }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("关于") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("LM-Reader", style = MaterialTheme.typography.headlineLarge, localize = false)
            Text("本地漫画阅读与翻译", style = MaterialTheme.typography.bodyLarge)
            Text("版本", style = MaterialTheme.typography.labelLarge)
            Text(coordinator.currentVersion, localize = false)
            OutlinedButton(onClick = { openPage(ProjectLinks.GITHUB) }, modifier = Modifier.fillMaxWidth()) {
                Text("GitHub 项目主页")
            }
            SelectionContainer { Text(ProjectLinks.GITHUB, localize = false, style = MaterialTheme.typography.bodySmall) }
            Text("自动检查更新", style = MaterialTheme.typography.titleSmall)
            Box {
                OutlinedButton(onClick = { choosingFrequency = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(updateFrequencyLabel(frequency))
                }
                DropdownMenu(expanded = choosingFrequency, onDismissRequest = { choosingFrequency = false }) {
                    UpdateCheckFrequency.entries.forEach { value ->
                        DropdownMenuItem(text = { Text(updateFrequencyLabel(value)) }, onClick = {
                            coordinator.preferences.setFrequency(value); choosingFrequency = false
                        })
                    }
                }
            }
            Button(enabled = !state.checking, modifier = Modifier.fillMaxWidth(), onClick = coordinator::check) {
                if (state.checking) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.checking) "正在检查更新" else "检查更新")
            }
            when (val status = state.result) {
                UpdateStatus.NoPublicRelease -> Text("尚无可公开访问的正式版本")
                UpdateStatus.AccessRestricted -> Text("GitHub 暂时限制访问，请稍后重试")
                is UpdateStatus.Release -> {
                    Text(when (status.isNewer) {
                        true -> "发现新版本"
                        false -> "当前已是最新版本"
                        null -> "已获取发行版本，请到 GitHub 确认"
                    })
                    Text(status.version, localize = false)
                    OutlinedButton(onClick = { if(status.isNewer == true) coordinator.showAvailable() else openPage(status.page) }) {
                        Text(if(status.isNewer == true) "查看更新日志" else "查看发行页面")
                    }
                }
                null -> Unit
            }
            state.download?.let { transfer ->
                Text(when(transfer.phase) {
                    UpdateDownloadPhase.DOWNLOADING -> "正在下载安装包"
                    UpdateDownloadPhase.VERIFYING -> "正在校验安装包"
                    UpdateDownloadPhase.READY -> "安装包已下载并校验通过"
                    UpdateDownloadPhase.FAILED -> transfer.error ?: "下载失败，请重试"
                })
                OutlinedButton(onClick = coordinator::showDownload) { Text("查看下载进度") }
            }
            state.error?.let { Text(it) }
            if (browserFailed) Text("无法打开浏览器，请手动访问 GitHub")
            Text("启动时按所选频率检查 GitHub 正式版本；关闭自动检查仍可手动检查。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
