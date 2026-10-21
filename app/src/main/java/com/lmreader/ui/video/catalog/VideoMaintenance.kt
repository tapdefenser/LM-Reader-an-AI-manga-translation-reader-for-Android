package com.lmreader.ui.video.catalog

import com.lmreader.core.model.ResourceGovernor
import com.lmreader.di.AppContainer
import com.lmreader.tasks.TaskWaiting
import kotlinx.coroutines.*
import org.json.JSONObject

class VideoMaintenance(private val container: AppContainer) {
    init {
        container.taskScheduler.register("MEDIA_SCAN") { execution ->
            // Scan even without the media permission: the repository still performs the
            // local NONE validation (generation > 0 rows become inaccessible) before the
            // task reports WAITING ACCESS. No early return may skip that revocation.
            execution.progress(0, 0, "正在同步视频元数据")
            ResourceGovernor.shared.use("io") { container.videoCatalog.scan(JSONObject(execution.payload).optBoolean("full")) }
            container.videoCatalog.error.value?.let { throw TaskWaiting("ACCESS", it) }
            if (container.videoCatalog.permissionState() == VideoCatalogRepository.NONE) {
                throw TaskWaiting("ACCESS", "请授予视频读取权限")
            }
        }
        // The observer only asks for the normal scheduling path. It must not scan from a
        // background process: canStart() is the public foreground/visibility gate, and
        // MainActivity keeps its separate foreground refresh.
        container.videoCatalog.start(container.backgroundScope) {
            if(container.taskService.canStart()) refresh()
        }
        container.backgroundScope.launch {
            container.startupReady.await()
            // Import one verified old project at a time, without modifying its result files.
            for(p in container.videoLibrary.projects.value) container.videoCatalog.importLegacy(p.id, p.uri, p.name,
                p.sha256, p.bytes, p.durationMs, p.width, p.height, p.rotation, p.positionMs)
        }
    }
    fun refresh(full: Boolean = false) = container.backgroundScope.launch {
        val key = "media-scan:$full"
        container.taskScheduler.ready.await()
        val old = container.taskScheduler.dao.active(key)
        if(old?.state in setOf("WAITING", "INTERRUPTED")) container.taskScheduler.resume(old!!.taskId)
        else container.taskScheduler.submit("MEDIA_SCAN", if(full) "刷新视频库" else "发现新增视频", "media-scan", key,
            JSONObject().put("full", full).toString(), priority = ResourceGovernor.MAINTENANCE)
    }
}
