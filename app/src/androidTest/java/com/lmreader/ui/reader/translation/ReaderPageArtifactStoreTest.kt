package com.lmreader.ui.reader.translation

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import kotlinx.coroutines.CancellationException
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderPageArtifactStoreTest {
    @Test fun newBubbleFontScaleRotationAndGeometrySurviveSaving() {
        val base=ReaderPageTranslation("manual","",File(root,"new-draft"),LocalTranslationLanguage.ENGLISH,
            LocalTranslationLanguage.CHINESE_SIMPLIFIED,"hash",200,300,emptyList(),emptyList(),0)
        var draft=PageBubbleDraft(base).addBubble().editText("Created text").scaleFont(20)
        val id=draft.selectedId!!
        draft=draft.beginTransform(id).transform(id,PixelRect(15f,20f,180f,200f),35f).finishTransform()
        store.saveEdits(base,draft.regions)
        val loaded=store.load("manual","hash")!!.regions.single()
        assertEquals("Created text",loaded.translatedText);assertEquals(120,loaded.fontScalePercent)
        assertEquals(35f,loaded.rotationDegrees);assertEquals(PixelRect(15f,20f,180f,200f),loaded.region.bounds)
        assertThrows(PageTranslationRevisionConflict::class.java) { store.saveEdits(base,draft.regions) }
    }
    @Test fun maskExpansionSurvivesReloadAndOldTranslationsUseDefault() {
        val saved=store.save("page","source-hash",LocalTranslationLanguage.ENGLISH,
            LocalTranslationLanguage.CHINESE_SIMPLIFIED,200,300,regions("page"),emptyList(),0,
            BubbleRenderSettings(freeTextMaskExpansionPercent=12))
        assertEquals(12,store.load("page","source-hash")!!.renderSettings.freeTextMaskExpansionPercent)
        val envelope=JSONObject(saved.dataFile.readText())
        val document=JSONObject(envelope.getString("document"))
        document.getJSONObject("render").remove("freeTextMaskExpansion")
        val encoded=document.toString()
        saved.dataFile.writeText(envelope.put("document",encoded).put("sha256",ReaderPageArtifactStore.sha256(encoded.toByteArray(Charsets.UTF_8))).toString())
        val old=store.load("page","source-hash")!!
        assertEquals(6,old.renderSettings.freeTextMaskExpansionPercent)
        assertEquals(saved.regions,old.regions)
    }
    private val context=ApplicationProvider.getApplicationContext<android.content.Context>()
    private val root=File(context.cacheDir,"artifact-fixture-"+UUID.randomUUID())
    private val store=ReaderPageArtifactStore(root)
    private fun regions(id: String)=listOf(PageTranslatedRegion(PageTextRegion("$id:a",RegionKind.BUBBLE,
        PixelRect(10f,10f,100f,100f),emptyList(),"hello",listOf(PixelRect(30f,30f,80f,60f))),"你好 🙂"))
    private fun save(id: String,check: ()->Unit={})=store.save(id,"source-hash",LocalTranslationLanguage.ENGLISH,
        LocalTranslationLanguage.CHINESE_SIMPLIFIED,200,300,regions(id),emptyList(),0,checkCancelled=check)
    @After fun cleanOnlyOwnFiles() {check(root.canonicalFile.parentFile==context.cacheDir.canonicalFile);root.deleteRecursively()}
    @Test fun cancellationAfterDataWriteRetainsPreviousCompleteRevision() {
        val before=save("page");val bytes=before.dataFile.readBytes();var checks=0
        assertThrows(CancellationException::class.java) {save("page") {if(++checks==2) throw CancellationException()}}
        assertEquals(before.revision,store.load("page","source-hash")!!.revision)
        assertArrayEquals(bytes,before.dataFile.readBytes())
        assertEquals(1,root.listFiles().orEmpty().size)
    }
    @Test fun unicodeEditsAndDeletionSurviveReloadWithoutAnyPng() {
        val saved=save("page")
        val changed=store.saveEdits(saved,saved.regions.map {it.copy(translatedText="Edited\n第二行 😀")})
        assertEquals("Edited\n第二行 😀",store.load("page","source-hash")!!.regions.single().translatedText)
        assertNotEquals(saved.revision,changed.revision)
        assertTrue(root.walkTopDown().none {it.extension=="png"})
        val deleted=store.saveEdits(changed,emptyList())
        assertEquals(deleted,store.load("page","source-hash"));assertTrue(deleted.regions.isEmpty())
    }
    @Test fun staleDraftCannotOverwriteANewerSavedTranslation() {
        val before=save("page");val current=store.saveEdits(before,emptyList())
        assertThrows(IllegalStateException::class.java) {store.saveEdits(before,before.regions)}
        assertEquals(current.revision,store.load("page","source-hash")!!.revision)
    }
    @Test fun modifiedOriginalAndDamagedDataCannotBeReused() {
        val saved=save("page");assertNull(store.load("page","changed-source"))
        val contents=JSONObject(saved.dataFile.readText()).put("sha256","damaged")
        saved.dataFile.writeText(contents.toString())
        assertThrows(IllegalArgumentException::class.java) {store.load("page","source-hash")}
    }
    @Test fun savedEditsAreNeverEvictedWhenMorePagesAreTranslated() {
        val first=save("page-0")
        repeat(70) {save("page-"+(it+1))}
        assertEquals(first.revision,store.load("page-0","source-hash")!!.revision)
        assertEquals(71,root.listFiles().orEmpty().size)
    }
    @Test fun legacyTextMigratesEvenWhenItsPngWasClearedAndMigrationIsIdempotent() {
        val saved=save("page")
        val legacy=File(root,"legacy").apply {mkdirs()}
        val revision=UUID.randomUUID().toString();val folder=File(legacy,revision).apply {mkdirs()}
        val document=JSONObject(JSONObject(saved.dataFile.readText()).getString("document")).put("schema",1).put("imageSha256","missing-png")
        File(folder,"metadata.json").writeText(document.toString())
        val pointer=File(legacy,ReaderPageArtifactStore.sha256("page".toByteArray())+".json")
        pointer.writeText(JSONObject().put("revision",revision).toString())
        val migrated=ReaderPageArtifactStore(File(root,"durable"),legacy)
        migrated.migrateLegacy()
        val data=migrated.load("page","source-hash")!!
        assertEquals(saved.regions,data.regions);assertEquals(saved.source,data.source)
        assertFalse(pointer.exists());assertFalse(folder.exists())
        migrated.migrateLegacy();assertEquals(data.revision,migrated.load("page","source-hash")!!.revision)
    }
}
