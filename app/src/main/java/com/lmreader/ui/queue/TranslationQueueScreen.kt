package com.lmreader.ui.queue

import androidx.compose.runtime.Composable
import com.lmreader.di.AppContainer
import com.lmreader.tasks.UnifiedTaskScreen

/** Queue route and unified task route share one page so their controls cannot diverge. */
@Composable
fun TranslationQueueScreen(container: AppContainer, onBack: () -> Unit) {
    UnifiedTaskScreen(container, onResolve = null, onBack = onBack)
}
