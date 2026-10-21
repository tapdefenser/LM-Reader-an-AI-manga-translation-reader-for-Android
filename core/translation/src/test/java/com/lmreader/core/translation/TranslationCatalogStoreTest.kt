package com.lmreader.core.translation

import com.lmreader.core.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class TranslationCatalogStoreTest {
    @get:Rule val temp=TemporaryFolder()
    private val fallback get()=TranslationModelCatalog(File("src/main/assets/translation/catalog.json").readText())
    private val mozilla=TranslationSourceSettings()
    private val custom=TranslationSourceSettings(TranslationDownloadSource.CUSTOM,"https://example.org/models")
    private fun result(settings:TranslationSourceSettings)=TranslationModelCatalog.fromPacks(listOf(fallback.pack("ja-en").copy(id="id-en",version="2.1",source=LocalTranslationLanguage.fromTag("id"))),settings,100)
    @Test fun refreshedLanguagesAndVersionsPersistWithoutModelDownloads()=runBlocking {
        val folder=temp.newFolder();val store=TranslationCatalogStore(fallback,folder,::result)
        store.refresh(mozilla);assertTrue(store.state.value.fetched)
        assertEquals("2.1",store.state.value.catalog.pack("id-en").version)
        val reopened=TranslationCatalogStore(fallback,folder,::result);reopened.select(mozilla)
        assertEquals(store.state.value.catalog.packs,reopened.state.value.catalog.packs)
        assertEquals(listOf(LocalTranslationLanguage.ENGLISH,LocalTranslationLanguage.fromTag("id")),reopened.state.value.catalog.languages)
    }
    @Test fun failingFetchKeepsLastUsableListAndVersion()=runBlocking {
        var fail=false
        val store=TranslationCatalogStore(fallback,temp.newFolder()) {settings ->if(fail) error("HTTP 503") else result(settings)}
        store.refresh(mozilla);val before=store.state.value.catalog;fail=true
        try {store.refresh(mozilla);fail("Must report failure")} catch(_:IllegalStateException) {}
        assertSame(before,store.state.value.catalog);assertFalse(store.state.value.fetching);assertEquals("HTTP 503",store.state.value.error)
    }
    @Test fun sourceCachesAreIndependentAndLateResponseCannotReplaceNewSource()=runBlocking {
        val gate=CompletableDeferred<Unit>();val started=CompletableDeferred<Unit>()
        val store=TranslationCatalogStore(fallback,temp.newFolder()) {settings ->if(settings==mozilla) {started.complete(Unit);gate.await()};result(settings)}
        val request=launch {store.refresh(mozilla)};started.await();store.select(custom);store.refresh(custom)
        val current=store.state.value.catalog;gate.complete(Unit);request.join()
        assertSame(current,store.state.value.catalog);assertEquals(TranslationModelCatalog.sourceKey(custom),store.state.value.selectedSourceKey)
        store.select(mozilla);assertFalse(store.state.value.fetched)
        store.select(custom);assertTrue(store.state.value.fetched)
    }
    @Test fun cancelledFetchCanBeRetried()=runBlocking {
        val started=CompletableDeferred<Unit>();var block=true
        val store=TranslationCatalogStore(fallback,temp.newFolder()) {settings ->if(block) {started.complete(Unit);awaitCancellation()};result(settings)}
        val request=launch {store.refresh(mozilla)};started.await();request.cancelAndJoin()
        assertFalse(store.state.value.fetching);block=false;store.refresh(mozilla);assertTrue(store.state.value.fetched)
    }
    @Test fun corruptCachedFileFallsBackWithoutLosingExistingModels()=runBlocking {
        val folder=temp.newFolder();val store=TranslationCatalogStore(fallback,folder,::result);store.refresh(mozilla)
        folder.listFiles()!!.single().writeText("corrupt")
        val reopened=TranslationCatalogStore(fallback,folder,::result);reopened.select(mozilla)
        assertFalse(reopened.state.value.fetched);assertEquals(8,reopened.state.value.catalog.packs.size)
    }
    private fun cacheFile(folder:File,settings:TranslationSourceSettings):File {
        val key=TranslationModelCatalog.sourceKey(settings)
        val hash=MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") {"%02x".format(it)}
        return File(folder,"$hash.json")
    }
    @Test fun unsupportedCachedPackagesAreIgnoredWithoutLosingSupportedOnes()=runBlocking {
        val folder=temp.newFolder();val current=fallback.pack("ja-en")
        val supported=TranslationModelCatalog.packJson(current)
        val removed=JsonObject(supported + ("id" to JsonPrimitive("legacy-opus")) + ("engine" to JsonPrimitive("OPUS_MT")))
        val cached=buildJsonObject {
            put("schema",3);put("engine","local-nmt-v1");put("sourceKey",TranslationModelCatalog.sourceKey(mozilla));put("fetchedAt",100L)
            putJsonArray("packages") {add(supported);add(removed)}
        }
        cacheFile(folder,mozilla).writeText(cached.toString())
        val store=TranslationCatalogStore(fallback,folder)
        store.select(mozilla)
        assertTrue(store.state.value.fetched)
        assertEquals(listOf(current),store.state.value.catalog.packs)
    }
    @Test fun cacheWithOnlyUnsupportedPackagesFallsBackToBundledCatalog()=runBlocking {
        val folder=temp.newFolder();val current=fallback.pack("ja-en")
        val removed=JsonObject(TranslationModelCatalog.packJson(current) + ("engine" to JsonPrimitive("OPUS_MT")))
        val cached=buildJsonObject {
            put("schema",3);put("engine","local-nmt-v1");put("sourceKey",TranslationModelCatalog.sourceKey(mozilla))
            putJsonArray("packages") {add(removed)}
        }
        cacheFile(folder,mozilla).writeText(cached.toString())
        val store=TranslationCatalogStore(fallback,folder)
        store.select(mozilla)
        assertFalse(store.state.value.fetched)
        assertEquals(fallback.packs,store.state.value.catalog.packs)
    }
}
