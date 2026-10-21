package com.lmreader.ui.video.catalog

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmreader.di.AppContainer
import com.lmreader.ui.video.VideoActionDialog
import com.lmreader.ui.video.openVideoPlayer

@Composable
fun HomeMediaPane(container: AppContainer, shelf: Boolean, onOpenMenu: () -> Unit, images: @Composable () -> Unit) {
    val mode by container.preferences.homeMediaMode.collectAsStateWithLifecycle(null)
    val holder = rememberSaveableStateHolder()
    val context = LocalContext.current
    var action by remember { mutableStateOf<Pair<String, String>?>(null) }
    val selected = mode ?: return
    holder.SaveableStateProvider(selected) {
        if(selected == "IMAGE") images()
        else VideoCatalogScreen(container, shelf, onOpenMenu,
            onPlay = { id, ids -> openVideoPlayer(context, id, ids) },
            onAction = { id, command ->
                if(command in setOf("EDIT", "SUBTITLES")) openVideoPlayer(context, id, listOf(id), command)
                else action = id to command
            })
    }
    action?.let { (id, command) -> VideoActionDialog(container, id, command, onDismiss = { action = null }) }
}
