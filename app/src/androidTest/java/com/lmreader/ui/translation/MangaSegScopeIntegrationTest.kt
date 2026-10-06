package com.lmreader.ui.translation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.lmreader.core.model.*
import com.lmreader.di.AppContainer
import com.lmreader.ui.workflow.*
import com.lmreader.core.workflow.WorkflowProgramCodec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** Owns two generated manga rows. Run with an isolated test application ID. */
class MangaSegScopeIntegrationTest {
    @Test fun freeTextGapPersistsPerMangaAndFreshMaskDefaultIsSixty() {
        lateinit var vm: TranslationOptionsViewModel
        val id = mangaIds.first()
        compose.runOnIdle { vm = TranslationOptionsViewModel(id,container.mangaRepository,container.shelfRepository,container.preferences,container.bubbleRenderPreferences) }
        compose.setContent { MaterialTheme { TranslationOptionsScreen(container,id,onBack={},viewModel=vm) } }
        compose.waitUntil(5000) { !vm.state.value.loading }
        assertEquals(60,vm.state.value.settings.effectiveBubbleRender(BubbleRenderSettings()).opacityPercent)
        assertEquals(0f,vm.state.value.settings.effectiveFreeTextMergeGapRatio(),.001f)
        compose.onNodeWithTag("translation-option:游离文字行间合并距离").performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(35f) }
        compose.waitUntil(5000) { runBlocking { container.mangaRepository.translationSettings(id).freeTextMergeGapRatio == .35f } }
        val captured = JSONObject(translationTaskSnapshot(TranslationWorkflow.LOCAL_MACHINE,runBlocking {
            container.mangaRepository.translationSettings(id) },"en","zh-Hans","",BubbleRenderSettings()))
        assertEquals(.35,captured.getDouble("freeTextMergeGapRatio"),.0001)
        assertEquals(60,captured.getInt("opacity"))
        compose.runOnIdle { vm.setFreeTextMergeGap(0f) }
        compose.waitUntil(5000) { runBlocking { container.mangaRepository.translationSettings(id).freeTextMergeGapRatio == 0f } }
        compose.runOnIdle { vm.reload() }
        compose.waitUntil(5000) { vm.state.value.settings.freeTextMergeGapRatio == 0f }
        assertEquals(.35,captured.getDouble("freeTextMergeGapRatio"),.0001)
        val other = runBlocking { container.mangaRepository.translationSettings(mangaIds.last()) }
        assertEquals(0f,other.effectiveFreeTextMergeGapRatio(),.001f)
    }
    @Test fun qualityOptionsPersistPerMangaAndQueuedSnapshotStaysFixed() {
        val id=mangaIds.first()
        lateinit var vm: TranslationOptionsViewModel
        compose.runOnIdle { vm=TranslationOptionsViewModel(id,container.mangaRepository,container.shelfRepository,container.preferences,container.bubbleRenderPreferences) }
        compose.setContent { MaterialTheme { TranslationOptionsScreen(container,id,onBack={},viewModel=vm) } }
        compose.waitUntil(5000) { !vm.state.value.loading }
        compose.onNodeWithTag("translation-option:文字检测置信度阈值").performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(25f) }
        compose.onNodeWithTag("translation-option:游离文字遮罩扩张").performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(12f) }
        compose.waitUntil(5000) { runBlocking {
            val saved=container.mangaRepository.translationSettings(id)
            saved.textDetectionThreshold==.25f && saved.freeTextMaskExpansionPercent==12
        } }
        val saved=runBlocking { container.mangaRepository.translationSettings(id) }
        val snapshot=JSONObject(translationTaskSnapshot(TranslationWorkflow.LOCAL_MACHINE,saved,"en","zh-Hans","",BubbleRenderSettings()))
        assertEquals(.25,snapshot.getDouble("textDetectionThreshold"),.0001)
        assertEquals(12,snapshot.getInt("freeTextMaskExpansion"))
        compose.runOnIdle { vm.setTextDetectionThreshold(.7f); vm.setFreeTextMaskExpansion(3) }
        compose.waitUntil(5000) { runBlocking {
            val next=container.mangaRepository.translationSettings(id)
            next.textDetectionThreshold==.7f && next.freeTextMaskExpansionPercent==3
        } }
        compose.runOnIdle { vm.reload() }
        compose.waitUntil(5000) { vm.state.value.settings.textDetectionThreshold==.7f && vm.state.value.settings.freeTextMaskExpansionPercent==3 }
        assertEquals(.25,snapshot.getDouble("textDetectionThreshold"),.0001)
        assertEquals(12,snapshot.getInt("freeTextMaskExpansion"))
        val other=runBlocking { container.mangaRepository.translationSettings(mangaIds.last()) }
        assertEquals(.45f,other.effectiveTextDetectionThreshold(),.0001f)
        assertEquals(6,other.effectiveBubbleRender(BubbleRenderSettings()).freeTextMaskExpansionPercent)
    }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val container = AppContainer.from(ApplicationProvider.getApplicationContext())
    private val token = UUID.randomUUID().toString()
    private val sourceId = "seg-source-$token"
    private val mangaIds = listOf("seg-manga-a-$token", "seg-manga-b-$token")

    @Before fun createFixtures() = runBlocking {
        val db = container.database.openHelper.writableDatabase
        db.execSQL("INSERT INTO library_sources (sourceId, kind, treeUri, displayPath, recursive, mode, orderIndex, permission, revision) VALUES (?, 'IMAGE_DIRECTORY', ?, 'SEG fixture', 1, 'MULTI_CHAPTER', 0, 'OK', 1)", arrayOf(sourceId, "content://fixture/$token"))
        mangaIds.forEach { id ->
            db.execSQL("INSERT INTO mangas (mangaId, anchorDocumentId, sourceId, sourceKind, layoutMode, displayName, sortKey, sourceOrderIndex, hasMetadata, chapterCountKnown, availability, discoveryGeneration, discoveredAt, updatedAt, translationAutoDetectSource) VALUES (?, ?, ?, 'IMAGE_DIRECTORY', 'MULTI_CHAPTER', 'SEG fixture', 'fixture', 0, 0, 1, 'AVAILABLE', 1, 0, 0, 0)", arrayOf(id, id, sourceId))
        }
    }
    @After fun deleteFixtures() = runBlocking {
        val db = container.database.openHelper.writableDatabase
        mangaIds.forEach { db.execSQL("DELETE FROM mangas WHERE mangaId = ?", arrayOf(it)) }
        db.execSQL("DELETE FROM library_sources WHERE sourceId = ?", arrayOf(sourceId))
    }

    @Test fun scopeLivesInMangaOptionsPersistsAndDoesNotChangeExistingSnapshot() {
        val id = mangaIds.first()
        lateinit var vm: TranslationOptionsViewModel
        compose.runOnIdle { vm = TranslationOptionsViewModel(id, container.mangaRepository, container.shelfRepository, container.preferences, container.bubbleRenderPreferences) }
        compose.setContent { MaterialTheme { TranslationOptionsScreen(container, id, onBack = {}, viewModel = vm) } }
        compose.waitUntil(5000) { !vm.state.value.loading }
        compose.onNodeWithText("SEG 提取范围").performScrollTo()
        compose.onNode(hasText("气泡+游离文字") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("游离文字") and isSelectable()).performScrollTo().assertIsDisplayed().performClick()
        compose.onNode(hasText("游离文字") and isSelectable()).assertIsSelected()
        compose.waitUntil(5000) { runBlocking { container.mangaRepository.translationSettings(id).segTextScope == SegTextScope.FREE_TEXT } }
        val saved = runBlocking { container.mangaRepository.translationSettings(id) }
        val snapshot = translationTaskSnapshot(TranslationWorkflow.LOCAL_MACHINE, saved, "en", "zh-Hans", "", BubbleRenderSettings())
        assertEquals("FREE_TEXT", JSONObject(snapshot).getString("segTextScope"))
        val snapProgram = WorkflowProgramCodec.decode(JSONObject(snapshot).getJSONObject("program").toString())
        assertTrue(snapProgram.allNodes().filter { it.kind == WorkflowKind.SEG }.all { it.inputs.keys == setOf("image") })
        compose.runOnIdle { vm.reload() }
        compose.waitUntil(5000) { vm.state.value.settings.segTextScope == SegTextScope.FREE_TEXT }
        compose.onNode(hasText("游离文字") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("气泡") and isSelectable()).performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5000) { runBlocking { container.mangaRepository.translationSettings(id).segTextScope == SegTextScope.BUBBLES } }
        assertEquals("FREE_TEXT", JSONObject(snapshot).getString("segTextScope"))
        assertEquals(SegTextScope.ALL, runBlocking { container.mangaRepository.translationSettings(mangaIds.last()).segTextScope })
    }

    @Test fun oldSettingsAndSnapshotsDefaultToBothKinds() {
        assertEquals(SegTextScope.ALL, runBlocking { container.mangaRepository.translationSettings(mangaIds.first()).segTextScope })
        assertEquals(SegTextScope.ALL, SegTextScope.fromValue(null))
        for (scope in SegTextScope.entries) {
            assertEquals(scope, SegTextScope.fromValue(scope.name))
            val snapshot = JSONObject(translationTaskSnapshot(TranslationWorkflow.LOCAL_MACHINE,
                MangaTranslationSettings(segTextScope = scope), "en", "zh-Hans", "", BubbleRenderSettings()))
            assertEquals(scope.name, snapshot.getString("segTextScope"))
        }
    }
}
