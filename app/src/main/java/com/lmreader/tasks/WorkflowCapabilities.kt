package com.lmreader.tasks

import com.lmreader.core.api.ApiProtocol
import com.lmreader.core.model.*
import com.lmreader.core.workflow.*
import com.lmreader.di.AppContainer
import kotlinx.coroutines.flow.first

/** Readiness is checked by the executor: a missing model/profile remains a manageable task. */
suspend fun requireWorkflowCapabilities(container: AppContainer, program: WorkflowProgram,
    source: LocalTranslationLanguage, target: LocalTranslationLanguage, captured: List<ApiProfile>) {
    val live = container.apiProfiles.profiles.first()
    for(node in program.allNodes()) try {
        if(node.kind in setOf(WorkflowKind.API, WorkflowKind.API_STREAM)) {
            val id = (node.inputs["profile"] as? WorkflowExpression.Text)?.value ?: error("请选择 API 配置")
            val profile = captured.firstOrNull { it.id == id } ?: live.firstOrNull { it.id == id } ?: error("API 配置不存在，请更新翻译选项")
            check(live.any { it.id == id }) { "API 配置已删除，请恢复配置或更新选项" }
            ApiProtocol.validate(profile)
        }
        if(node.kind == WorkflowKind.TRANSLATE) {
            val routes = program.copy(rows = listOf(node)).localTranslationRoutes(source, target)
            require(routes.isNotEmpty()) { "本地机翻的语言方向需要明确绑定" }
            routes.forEach { container.translationModels.installedCatalog().route(it.source, it.target, it.engine) }
        }
    } catch(error: Exception) {
        if(error is kotlinx.coroutines.CancellationException) throw error
        throw TaskWaiting("CAPABILITY", "${node.label.ifBlank { WorkflowLabels.kind(node.kind) }}：${error.message}")
    }
}
