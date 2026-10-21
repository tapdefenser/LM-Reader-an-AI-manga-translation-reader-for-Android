package com.lmreader

import android.app.Application
import com.lmreader.di.AppContainer
import com.lmreader.di.LmReaderApplicationHolder

/**
 * 应用入口：只持有依赖容器，不执行目录 IO 或翻译请求（开发文档 15.2）。
 */
class LmReaderApplication : Application(), LmReaderApplicationHolder {

    override val container: AppContainer by lazy { AppContainer(this) }
    override fun onCreate() {
        super.onCreate()
        com.lmreader.ui.video.player.VideoPlayerToolRegistry.host = com.lmreader.ui.video.VideoPlayerTools
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                container.taskService.setVisible(true, activity)
                container.readerPageTasks; container.videoTasks; container.videoExports; container.videoResultImports
                container.sourceMaintenanceTasks; container.metadataMaintenanceTasks
                container.taskScheduler.poke()
            }
            override fun onActivityStopped(activity: android.app.Activity) { container.taskService.setVisible(false, activity) }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
    }
}
