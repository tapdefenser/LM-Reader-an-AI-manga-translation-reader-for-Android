package com.lmreader.ui.reader.translation

import android.icu.util.ULocale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lmreader.core.model.*
import com.lmreader.di.AppContainer
import com.lmreader.ui.reader.ReaderItem
import com.lmreader.ui.translation.platformLanguageNames
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale
import java.io.IOException
import com.lmreader.core.workflow.*
import com.lmreader.ui.workflow.*
import com.lmreader.ui.queue.TranslationCacheBudget

enum class BubbleEditFailure { STORAGE, CHANGED, OTHER }

data class ReaderTranslationUiState(
    val pages: Map<String,ReaderPageTranslation> = emptyMap(), val originals: Set<String> = emptySet(),
    val progress: PageTranslationProgress? = null, val activePageId: String? = null, val activePageName: String = "",
    val source: LocalTranslationLanguage? = null, val target: LocalTranslationLanguage = LocalTranslationLanguage.CHINESE_SIMPLIFIED,
    val failure: String? = null, val cancelled: Boolean = false, val completedRegions: Int? = null,
    val editing: Boolean = false, val draft: PageBubbleDraft? = null, val savingEdits: Boolean = false,
    val confirmNavigation: Boolean = false, val editFailure: BubbleEditFailure? = null, val clearingPage: Boolean = false,
    val creatingBubble: Boolean = false,
)

class ReaderPageTranslationViewModel(private val container: AppContainer,private val mangaId: String): ViewModel() {
    private val mutable=MutableStateFlow(ReaderTranslationUiState())
    val state=mutable.asStateFlow()
    private var translating: Job?=null
    private var loadingCache: Job?=null
    private var currentPageId: String? = null
    private var currentItem: ReaderItem.PageItem? = null
    private var currentRender = BubbleRenderSettings()
    private val navigation = DraftNavigationGate()
    init { viewModelScope.launch {
        container.translationQueue.pagePreviews.collect { saved ->
            if(saved.pageId == currentPageId && container.translationQueue.isPreviewCurrent(saved)) mutable.update { it.copy(pages = rememberPage(it.pages, saved)) }
        }
    } }
    init {viewModelScope.launch {
        val locale=Locale.forLanguageTag(container.preferences.appLanguageTag)
        val preferences=try {container.mangaRepository.translationSettings(mangaId)}
            catch(cancelled: CancellationException) {throw cancelled}
            catch(_: Exception) {return@launch}
        fun match(value: String?,languages: List<LocalTranslationLanguage>)=value?.let {raw ->languages.firstOrNull {language ->
            raw.equals(language.tag,ignoreCase=true) || platformLanguageNames(language,locale).toList().any {it.equals(raw,ignoreCase=true)}
        }}
        val source=if(preferences.autoDetectSource) null else match(preferences.sourceLanguage,LocalPageTranslator.ocrSources)
        val targets=(container.translationCatalogs.state.value.catalog.languages+LocalPageTranslator.ocrSources).distinct()
        val target=match(preferences.targetLanguage,targets) ?: targets.firstOrNull {it.tag.equals(locale.toLanguageTag(),ignoreCase=true)}
            ?: targets.firstOrNull {it.tag==locale.language+"-"+ULocale.addLikelySubtags(ULocale.forLocale(locale)).script}
            ?: targets.firstOrNull {it.tag==locale.language} ?: LocalTranslationLanguage.CHINESE_SIMPLIFIED
        mutable.update {if(it.source!=null) it else it.copy(source=source,target=target)}
    }}
    fun showPage(item: ReaderItem.PageItem?,render: BubbleRenderSettings,neighbors: List<ReaderItem.PageItem> = emptyList()) {
        loadingCache?.cancel()
        currentPageId = item?.page?.pageId
        currentItem = item; currentRender = render
        mutable.update { state -> state.copy(draft = if (!state.editing) null
            else state.draft?.takeIf { it.saved.pageId==currentPageId } ?: state.pages[currentPageId]?.let(::PageBubbleDraft)) }
        if(item==null) return
        loadingCache=viewModelScope.launch {
            for (candidate in (listOf(item)+neighbors).distinctBy { it.page.pageId }) {
            try {
                val cached=container.localPageTranslator.cached(candidate.chapter.source,candidate.page,render)
                ensureActive()
                if(cached!=null) mutable.update {it.copy(pages=rememberPage(it.pages,cached),
                    source=it.source ?: cached.source.takeIf {candidate.page.pageId==currentPageId},
                    target=if(it.source==null && candidate.page.pageId==currentPageId) cached.target else it.target,
                    draft=if(it.editing && currentPageId==cached.pageId && it.draft?.dirty!=true && it.draft?.saved?.revision!=cached.revision)
                        PageBubbleDraft(cached) else it.draft)}
                else mutable.update {if(it.activePageId==candidate.page.pageId) it else it.copy(pages=it.pages-candidate.page.pageId,
                    draft=if(it.draft?.saved?.pageId==candidate.page.pageId && !it.draft.dirty) null else it.draft)}
            } catch(cancelled: CancellationException) {throw cancelled}
            catch(error: Exception) {if(candidate.page.pageId==currentPageId) mutable.update { it.copy(failure=error.message ?: "Cannot read page translation") } }
            }
        }
    }
    fun translate(item: ReaderItem.PageItem,source: LocalTranslationLanguage,target: LocalTranslationLanguage,render: BubbleRenderSettings) {
        val previous=translating;previous?.cancel();loadingCache?.cancel()
        translating=viewModelScope.launch {
            previous?.join()
            val queue = container.translationQueue
            var lease: String? = null
            queue.pauseForPriority()
            try {
                lease = container.taskService.acquire()
                queue.awaitCurrentPage()
                queue.setPriorityPage(item.chapter.title + " · " + item.page.displayName)
                mutable.update {it.copy(source=source,target=target,progress=PageTranslationProgress(PageTranslationStage.READING),
                    activePageId=item.page.pageId,activePageName=item.page.displayName,failure=null,cancelled=false,completedRegions=null)}
                val options=container.mangaRepository.translationSettings(mangaId)
                require(translationSetupComplete(options.sourceLanguage,options.targetLanguage,options.autoDetectSource)) {
                    "请先填写漫画的原文语言和目标语言"
                }
                val workflow=container.translationWorkflows.find(options.workflowId)
                    ?: error("所选翻译工作流已删除")
                val program = workflow.program.withApiOverride(options.apiProfileId)
                require(workflow.program.uses(WorkflowKind.SEG)) { "工作流需要 SEG 生成气泡" }
                val installed = container.translationModels.installedCatalog().languages
                val configuredSource = com.lmreader.ui.translation.matchEngineLanguage(options.sourceLanguage, installed) ?: LocalTranslationLanguage.fromTag(requireNotNull(options.sourceLanguage))
                val configuredTarget = com.lmreader.ui.translation.matchEngineLanguage(options.targetLanguage, installed) ?: LocalTranslationLanguage.fromTag(requireNotNull(options.targetLanguage))
                val routes = if(workflow.program.uses(WorkflowKind.TRANSLATE)) setOf(configuredSource to configuredTarget) else emptySet()
                routes.forEach { container.translationModels.installedCatalog().route(it.first, it.second) }
                container.shelfRepository.ensureOnShelf(mangaId)
                queue.prepareMangaResources(routes, workflow.program.uses(WorkflowKind.SEG), if(workflow.program.uses(WorkflowKind.OCR)) setOf(configuredSource) else emptySet())
                val categoryId = container.shelfRepository.categoryIdOf(mangaId)
                val categoryStyle = categoryId?.let { id -> container.shelfRepository.observeCategories().first().firstOrNull { it.categoryId == id }?.customStyle }
                val settings = WorkflowRunSettings(configuredSource, configuredTarget,
                    resolveTranslationStyle(options, categoryStyle, container.preferences.translationGlobalStyle.first()), render, options.effectiveSegThreshold(), container.apiProfiles.profiles.first(), options.segTextScope, options.effectiveTextDetectionThreshold(), options.effectiveFreeTextMergeGapRatio())
                val mangaName = container.mangaRepository.getCards(listOf(mangaId)).firstOrNull()?.displayName ?: mangaId
                val host = object : AndroidWorkflowHost(container.applicationContext, container, mangaId, mangaName,
                    listOf(WorkflowChapterInput(item.chapter.chapterId, item.chapter.title, item.chapter.source, listOf(item.page))), settings,
                    TranslationCacheBudget(container.translationCachePreferences.megabytes.value.toLong() * 1_048_576), reusePages = false, requestOrigin = "单页重译") {
                    override fun step(node: WorkflowNode, frame: WorkflowFrame) {
                        val stage = when(node.kind) { WorkflowKind.SEG -> PageTranslationStage.SEGMENTING; WorkflowKind.OCR -> PageTranslationStage.OCR; else -> PageTranslationStage.TRANSLATING }
                        val progress = PageTranslationProgress(stage)
                        mutable.update { it.copy(progress = progress) }; queue.setPriorityProgress(item.page.displayName, progress, node.label.ifBlank { WorkflowLabels.kind(node.kind) })
                    }
                    override fun progress(frame: WorkflowFrame, progress: PageTranslationProgress) {
                        mutable.update { it.copy(progress = progress) }; queue.setPriorityProgress(item.page.displayName, progress)
                    }
                    override suspend fun pagePreviewed(frame: WorkflowFrame, saved: ReaderPageTranslation) {
                        mutable.update { it.copy(pages = rememberPage(it.pages, saved)) }
                    }
                }
                val result = try { WorkflowRuntime(8, workflow.retries).execute(program, host); host.published[item.page.pageId] ?: error("工作流没有生成本页译文") }
                    finally {
                        host.close()
                        if(host.published[item.page.pageId] == null) showPage(item, render)
                    }
                ensureActive()
                queue.includePage(item.chapter.chapterId, item.page.pageId)
                mutable.update {it.copy(pages=rememberPage(it.pages,result), originals=it.originals-item.page.pageId,completedRegions=result.regions.size,
                    draft=if(it.editing && currentPageId==result.pageId) PageBubbleDraft(result) else it.draft)}
            } catch(cancelled: CancellationException) {mutable.update {it.copy(cancelled=true)};throw cancelled}
            catch(failure: Exception) {mutable.update {it.copy(failure=failure.message ?: failure.javaClass.simpleName)}}
            finally {
                queue.setPriorityPage(null)
                queue.resumeAfterPriority()
                lease?.let(container.taskService::release)
                mutable.update {it.copy(progress=null,activePageId=null)}
            }
        }
    }
    private fun rememberPage(pages: Map<String,ReaderPageTranslation>,page: ReaderPageTranslation)=
        retainReaderPage(pages, page.pageId, page, currentPageId)
    fun toggleOriginal(pageId: String) {mutable.update {it.copy(originals=if(pageId in it.originals) it.originals-pageId else it.originals+pageId)}}
    fun clearPage(item: ReaderItem.PageItem) {
        requestNavigation {
            if (mutable.value.clearingPage) return@requestNavigation
            loadingCache?.cancel()
            mutable.update { it.copy(clearingPage=true, failure=null) }
            viewModelScope.launch {
                try {
                    // Stop this reader's writer first; background writers are serialized by clearPage.
                    translating?.cancelAndJoin()
                    withContext(Dispatchers.IO) { container.translationQueue.clearPage(item.chapter.chapterId, item.page.pageId) }
                    mutable.update { it.copy(pages=it.pages-item.page.pageId, originals=it.originals-item.page.pageId,
                        draft=if(it.draft?.saved?.pageId==item.page.pageId) null else it.draft, completedRegions=null) }
                } catch(cancelled: CancellationException) { throw cancelled }
                catch(failure: Exception) { mutable.update { it.copy(failure=failure.message ?: "无法清除本页译文") } }
                finally { mutable.update { it.copy(clearingPage=false) } }
            }
        }
    }
    fun toggleEditing() {
        if (mutable.value.editing) requestNavigation { mutable.update { it.copy(editing=false,draft=null) } }
        else if (mutable.value.progress==null) mutable.update { it.copy(editing=true,originals=emptySet(),
            draft=it.pages[currentPageId]?.let(::PageBubbleDraft),editFailure=null) }
    }
    fun selectBubble(pageId: String, id: String?) { mutable.update { state ->
        if (!state.editing || state.savingEdits || state.progress!=null) state
        else {
            val draft = state.draft?.takeIf { it.saved.pageId==pageId } ?: state.pages[pageId]?.let(::PageBubbleDraft)
            state.copy(draft=draft?.select(id))
        }
    } }
    fun editText(text: String) { changeDraft { it.editText(text) } }
    fun scaleBubbleFont(delta: Int) { changeDraft { it.scaleFont(delta) } }
    fun editBubbleGesture(pageId: String, gesture: BubbleEditGesture) {
        if (pageId != currentPageId) return
        changeDraft { draft -> when (gesture) {
            is BubbleEditGesture.Begin -> draft.beginTransform(gesture.id)
            is BubbleEditGesture.Transform -> draft.transform(gesture.id, gesture.bounds, gesture.rotation)
            BubbleEditGesture.End -> draft.finishTransform()
        } }
    }
    fun addBubble() {
        val item = currentItem ?: return
        val state = mutable.value
        if (!state.editing || state.savingEdits || state.creatingBubble || state.progress != null) return
        loadingCache?.cancel()
        mutable.update { it.copy(creatingBubble = true, editFailure = null) }
        viewModelScope.launch {
            try {
                val draft = state.draft ?: PageBubbleDraft(container.localPageTranslator.editablePage(item.chapter.source,
                    item.page, state.source ?: LocalTranslationLanguage.ENGLISH, state.target, currentRender))
                if (currentPageId == item.page.pageId) mutable.update { it.copy(draft = draft.addBubble(),
                    pages = rememberPage(it.pages, draft.saved)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(editFailure = BubbleEditFailure.OTHER) } }
            finally { mutable.update { it.copy(creatingBubble = false) } }
        }
    }
    fun deleteBubble() { changeDraft(PageBubbleDraft::deleteSelected) }
    fun undoEdit() { changeDraft(PageBubbleDraft::undoChange) }
    private fun changeDraft(change: (PageBubbleDraft)->PageBubbleDraft) { mutable.update {
        if(it.savingEdits || it.creatingBubble || it.confirmNavigation || it.progress!=null) it else it.copy(draft=it.draft?.let(change),editFailure=null)
    } }

    /** All exits and page navigation ask here, before the reader changes its position/progress. */
    fun requestNavigation(action: () -> Unit): Boolean {
        val state = mutable.value
        val accepted = navigation.request(state.draft?.dirty==true,state.savingEdits || state.clearingPage || state.creatingBubble,action)
        if(!accepted && navigation.waiting && !state.confirmNavigation) {
            mutable.update { it.copy(confirmNavigation=true,editFailure=null) }
        }
        return accepted
    }
    fun cancelNavigation() {
        if(mutable.value.savingEdits) return
        navigation.cancel()
        mutable.update { if(it.savingEdits) it else it.copy(confirmNavigation=false,editFailure=null) }
    }
    fun discardAndNavigate() {
        if(mutable.value.savingEdits) return
        mutable.update { it.copy(draft=it.draft?.discarded(),confirmNavigation=false,editFailure=null) }
        navigation.resume()
    }
    fun saveAndNavigate() { saveEdits {
        mutable.update { it.copy(confirmNavigation=false) }
        navigation.resume()
    } }
    fun saveEdits(onSaved: () -> Unit = {}) {
        val draft=mutable.value.draft ?: return
        if(mutable.value.savingEdits) return
        if(!draft.dirty) { onSaved();return }
        loadingCache?.cancel()
        mutable.update { it.copy(savingEdits=true,editFailure=null) }
        viewModelScope.launch {
            try {
                val saved=withContext(Dispatchers.IO) {
                    val job=currentCoroutineContext()
                    container.localPageTranslator.artifacts.saveEdits(draft.saved,draft.regions) { job.ensureActive() }
                }
                mutable.update { it.copy(pages=rememberPage(it.pages,saved),draft=PageBubbleDraft(saved).select(draft.selectedId),savingEdits=false) }
                onSaved()
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) {
                android.util.Log.w("BubbleEditor","Cannot save page " + draft.saved.pageId,error)
                mutable.update { it.copy(editFailure=when(error) {
                    is IOException -> BubbleEditFailure.STORAGE
                    is PageTranslationRevisionConflict -> BubbleEditFailure.CHANGED
                    else -> BubbleEditFailure.OTHER
                }) }
            }
            finally { mutable.update { it.copy(savingEdits=false) } }
        }
    }
    fun cancel() {translating?.cancel()}
    fun clearMessage() {mutable.update {it.copy(failure=null,cancelled=false,completedRegions=null)}}
    fun clearEditFailure() {mutable.update {it.copy(editFailure=null)}}
}
