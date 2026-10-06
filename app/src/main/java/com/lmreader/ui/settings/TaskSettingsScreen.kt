package com.lmreader.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.di.AppContainer
import com.lmreader.ui.i18n.Icon
import com.lmreader.ui.i18n.Text
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(container.taskNotifications.enabled()) }
    var message by remember { mutableStateOf<String?>(null) }
    val failure by container.taskService.failure.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        enabled = container.taskNotifications.enabled()
        if(enabled) container.taskNotifications.refresh(includeResults = true)
    }
    LifecycleResumeEffect(Unit) {
        enabled = container.taskNotifications.enabled()
        container.taskNotifications.refresh()
        onPauseOrDispose { }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("后台任务与通知") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("翻译和导出分别显示任务通知，包含漫画、章节和页数进度。可以单独暂停或继续，点击通知打开对应队列；完成或失败后保留结果通知。切到后台或锁屏可继续处理。")
            Text("暂停翻译会停止调度并保留当前页的完成结果；整部漫画的 API 请求会等响应处理完毕。导出暂停会停止写入并保留快照，继续时复用已完成页面。")
            Text("系统终止进程后，运行中的任务标为中断，已保存译文和导出快照保留，需要手动重试。系统后台运行时限到达时会暂停任务，请打开应用手动继续。")
            Text(if (enabled) "通知已允许" else "通知未允许；后台任务仍可运行，但通知栏不显示进度")
            OutlinedButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    context.getSharedPreferences("task-notification-permission", android.content.Context.MODE_PRIVATE).edit().putBoolean("requested", true).apply()
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else context.startActivity(container.taskNotifications.settingsIntent())
            }) { Text("通知设置") }
            OutlinedButton(onClick = { scope.launch {
                try { container.exportQueue.pauseAndAwait(); container.exportQueue.cleanTemporaryFiles(); message = "临时文件清理完成；未完成任务的快照保留" }
                catch (error: Exception) { message = "清理失败：${error.message}" }
            } }) { Text("暂停导出并清理临时文件") }
            (failure ?: message)?.let { Text(it) }
        }
    }
}
