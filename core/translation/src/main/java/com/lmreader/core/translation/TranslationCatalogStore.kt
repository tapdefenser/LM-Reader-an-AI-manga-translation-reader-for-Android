package com.lmreader.core.translation

import com.lmreader.core.model.TranslationSourceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class TranslationCatalogState(val catalog: TranslationModelCatalog, val fetching: Boolean=false,
    val error: String?=null, val selectedSourceKey: String?=null) {
    val fetched get() = catalog.sourceKey!=null && catalog.sourceKey==selectedSourceKey
}

/** One atomic cache per source; failures and late replies never replace the active usable catalog. */
class TranslationCatalogStore(private val fallback: TranslationModelCatalog, private val root: File,
    private val fetch: suspend(TranslationSourceSettings)->TranslationModelCatalog = RemoteTranslationCatalog()::fetch) {
    private val mutex=Mutex()
    private val mutable=MutableStateFlow(TranslationCatalogState(fallback.forEngine(com.lmreader.core.model.TranslationModelEngine.BERGAMOT)))
    val state=mutable.asStateFlow()
    suspend fun select(settings: TranslationSourceSettings)=withContext(Dispatchers.IO) {mutex.withLock {
        val key=TranslationModelCatalog.sourceKey(settings)
        if(mutable.value.selectedSourceKey==key) return@withLock
        val cached=runCatching {val file=cache(key);require(file.length() in 1..4_194_304)
            TranslationModelCatalog(file.readText()).also {require(it.sourceKey==key)} }.getOrNull()
        mutable.value=TranslationCatalogState(cached ?: fallback.forEngine(settings.engine),selectedSourceKey=key)
    } }
    suspend fun refresh(settings: TranslationSourceSettings) {
        select(settings);val key=TranslationModelCatalog.sourceKey(settings)
        mutex.withLock {require(!mutable.value.fetching) {"正在获取目录"};mutable.value=mutable.value.copy(fetching=true,error=null)}
        try {
            val catalog=fetch(settings);require(catalog.sourceKey==key && catalog.fetchedAt!=null)
            withContext(Dispatchers.IO) {mutex.withLock {
                // Source changes are allowed while a request is running, but its result cannot overwrite the new source.
                if(mutable.value.selectedSourceKey!=key) return@withLock
                check(root.mkdirs() || root.isDirectory)
                ModelPackageInstaller.atomicText(cache(key),catalog.toJson())
                mutable.value=TranslationCatalogState(catalog,selectedSourceKey=key)
            } }
        } catch(failure:Exception) {
            withContext(NonCancellable) {mutex.withLock {if(mutable.value.selectedSourceKey==key) mutable.value=mutable.value.copy(fetching=false,error=if(failure is kotlinx.coroutines.CancellationException) null else failure.message ?: "获取目录失败")}}
            throw failure
        }
    }
    private fun cache(key:String):File {
        val hash=MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") {"%02x".format(it)}
        return File(root,"$hash.json")
    }
}
