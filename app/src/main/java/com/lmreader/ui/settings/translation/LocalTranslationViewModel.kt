package com.lmreader.ui.settings.translation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lmreader.core.model.*
import com.lmreader.di.AppContainer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class TranslationTestState(
    val source: LocalTranslationLanguage = LocalTranslationLanguage.JAPANESE,
    val target: LocalTranslationLanguage = LocalTranslationLanguage.CHINESE_SIMPLIFIED,
    val input: String = "今日はいい天気ですね。明日、学校で会いましょう。",
    val running: Boolean = false, val cancelling: Boolean = false,
    val progress: String = "", val result: LocalTranslationResult? = null, val error: String? = null,
)

internal class LocalTranslationViewModel(private val container: AppContainer): ViewModel() {
    private val mutable = MutableStateFlow(TranslationTestState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var catalogJob: Job? = null
    init {
        viewModelScope.launch {
            container.translationSources.settings.collectLatest { settings ->
                if (settings.engine == TranslationModelEngine.OPUS_MT) {
                    catalogJob?.cancelAndJoin()
                    container.translationCatalogs.select(settings)
                    val fetchedAt = container.translationCatalogs.state.value.catalog.fetchedAt ?: 0L
                    if (System.currentTimeMillis() - fetchedAt > 24 * 60 * 60 * 1000L) refreshCatalog()
                }
            }
        }
    }
    fun refreshCatalog() {
        if(catalogJob?.isActive==true) return
        catalogJob=viewModelScope.launch {
            try {container.translationCatalogs.refresh(container.translationSources.settings.first())}
            catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) { /* The catalog store keeps the old list and exposes this error. */ }
        }
    }
    fun cancelCatalog() {catalogJob?.cancel()}
    fun engine(value: TranslationModelEngine) {
        if (state.value.running) return
        cancelCatalog()
        mutable.update { it.copy(result = null, error = null) }
        viewModelScope.launch {
            val settings = container.translationSources.settings.first()
            container.translationSources.save(settings.copy(engine = value,
                source = if (value == TranslationModelEngine.OPUS_MT && settings.source == TranslationDownloadSource.MOZILLA)
                    TranslationDownloadSource.HUGGING_FACE else settings.source))
        }
    }
    fun source(value: LocalTranslationLanguage) { if (!state.value.running) mutable.update { it.copy(source=value,result=null,error=null) } }
    fun target(value: LocalTranslationLanguage) { if (!state.value.running) mutable.update { it.copy(target=value,result=null,error=null) } }
    fun text(value: String) { if (!state.value.running) mutable.update { it.copy(input=value,result=null,error=null) } }
    fun sample(language: LocalTranslationLanguage) {
        if (state.value.running) return
        mutable.update { it.copy(source=language, result=null, error=null, input=when(language) {
            LocalTranslationLanguage.JAPANESE -> "今日はいい天気ですね。明日、学校で会いましょう。"
            LocalTranslationLanguage.KOREAN -> "오늘 날씨가 좋네요. 내일 학교에서 만나요."
            else -> "The weather is nice today. Let's meet at school tomorrow."
        }) }
    }
    fun run() {
        val snapshot = state.value
        if (snapshot.running) return
        mutable.update { it.copy(running=true,cancelling=false,result=null,error=null,progress="校验并加载模型…") }
        job = viewModelScope.launch {
            try {
                val engine = container.translationSources.settings.first().engine
                val result = container.localTranslator.translate(engine,snapshot.source,snapshot.target,
                    listOf(LocalTranslationText("test-1", snapshot.input))) { done,total -> mutable.update { it.copy(progress="$done / $total") } }
                ensureActive(); mutable.update { it.copy(result=result) }
            } catch (cancelled: CancellationException) { mutable.update { it.copy(error="已取消") }; throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(error=failure.message ?: "机翻失败") } }
            finally { mutable.update { it.copy(running=false,cancelling=false,progress="") } }
        }
    }
    fun cancel() { if (state.value.running) { mutable.update { it.copy(cancelling=true) }; job?.cancel() } }
    fun remove(pack: TranslationModelPack) {
        viewModelScope.launch {
            try { container.translationModels.remove(pack,container.localTranslator) }
            catch (failure: Exception) { mutable.update { it.copy(error=failure.message) } }
        }
    }
    override fun onCleared() {
        job?.cancel()
        // Retained models belong to the app resource list and unload on 全部暂停.
    }
}
