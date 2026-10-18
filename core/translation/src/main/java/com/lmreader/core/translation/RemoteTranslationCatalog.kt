package com.lmreader.core.translation

import com.lmreader.core.model.*
import com.lmreader.core.translation.TranslationModelCatalog.Companion.number
import com.lmreader.core.translation.TranslationModelCatalog.Companion.string
import com.lmreader.core.translation.TranslationModelCatalog.Companion.stringOrNull
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class TranslationCatalogEndpoints(
    val mozillaService: String = "https://firefox.settings.services.mozilla.com/v1/",
    val mozillaRecords: String = "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models/records",
    val huggingFace: String = "https://huggingface.co",
    val hfMirror: String = "https://hf-mirror.com",
    val repository: String = "TiberiuCristianLeon/Bergamot",
)

internal class CatalogHttpException(val status: Int) : IllegalArgumentException("获取目录失败：HTTP $status")

/** Fetches metadata, never model payloads; each published snapshot pins files by commit and digest. */
class RemoteTranslationCatalog(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(),
    private val endpoints: TranslationCatalogEndpoints = TranslationCatalogEndpoints()) {
    suspend fun fetch(settings: TranslationSourceSettings): TranslationModelCatalog = withContext(Dispatchers.IO) {
        withTimeout(120_000) {
            val packs = if (settings.engine == TranslationModelEngine.OPUS_MT && settings.source != TranslationDownloadSource.CUSTOM)
                opus(settings) else when(settings.source) {
                TranslationDownloadSource.MOZILLA -> mozilla()
                TranslationDownloadSource.HUGGING_FACE -> huggingFace(endpoints.huggingFace)
                TranslationDownloadSource.HF_MIRROR -> huggingFace(endpoints.hfMirror)
                TranslationDownloadSource.CUSTOM -> custom(settings)
            }
            TranslationModelCatalog.fromPacks(packs,settings,System.currentTimeMillis())
        }
    }

    private suspend fun opus(settings: TranslationSourceSettings): List<TranslationModelPack> {
        require(settings.source in setOf(TranslationDownloadSource.HUGGING_FACE, TranslationDownloadSource.HF_MIRROR))
        val host = if (settings.source == TranslationDownloadSource.HF_MIRROR) endpoints.hfMirror else endpoints.huggingFace
        val base = TranslationModelCatalog.validateCustomBase(host)
        return RemoteOpusCatalog(base) { json(it).first }.fetch()
    }

    private suspend fun mozilla(): List<TranslationModelPack> = coroutineScope {
        val service=async { json(endpoints.mozillaService).first.jsonObject }
        val records=async { json(endpoints.mozillaRecords).first.jsonObject.getValue("data").jsonArray.map {it.jsonObject} }
        val base=TranslationModelCatalog.validateCustomBase(service.await().getValue("capabilities").jsonObject
            .getValue("attachments").jsonObject.string("base_url"))
        val groups=records.await().filter { record ->
            record["deleted"]?.jsonPrimitive?.booleanOrNull != true && record.stringOrNull("version")?.matches(Regex("[0-9]+(\\.[0-9]+)*")) == true &&
                record.stringOrNull("filter_expression").let { it.isNullOrBlank() || it.trim() == "env.appinfo.OS == 'Android'" }
        }.groupBy { Triple(it.string("fromLang"),it.string("toLang"),it.string("version")) }
        val candidates=groups.mapNotNull { (key, records) -> runCatching {
            val source=LocalTranslationLanguage.fromTag(key.first);val target=LocalTranslationLanguage.fromTag(key.second)
            val files=records.map {record ->
                val role=when(record.string("fileType")) {"model"->"model";"lex"->"lexicalShortlist";"vocab"->"vocab";"srcvocab"->"srcVocab";"trgvocab"->"trgVocab";else->error("未知文件角色")}
                val attachment=record.getValue("attachment").jsonObject
                val path=attachment.string("location");require(TranslationModelCatalog.safeRelativePath(path))
                val name=record.string("name")
                if(role=="model") require(name.endsWith(".intgemm.alphas.bin"))
                TranslationModelFile(role,name,attachment.number("size"),attachment.string("hash"),path,path,
                    attachment.number("size"),attachment.string("hash"),TranslationFileCompression.NONE,"$base/$path")
            }
            TranslationModelPack("${source.tag}-${target.tag}",key.third,source,target,files).also(TranslationModelCatalog::validatePack)
        }.getOrNull() }
        require(candidates.isNotEmpty()) { "源目录未提供兼容的完整语言包" }
        candidates.groupBy {it.id}.values.map { group -> group.maxWith {a,b -> compareVersions(a.version,b.version)} }.sortedBy {it.id}
    }

    private suspend fun huggingFace(host: String): List<TranslationModelPack> = coroutineScope {
        val base=TranslationModelCatalog.validateCustomBase(host)
        val revision=json("$base/api/models/${endpoints.repository}").first.jsonObject.string("sha")
        require(revision.matches(Regex("[a-f0-9]{40}"))) { "源返回无效仓库版本" }
        var next: String?="$base/api/models/${endpoints.repository}/tree/$revision?recursive=true&expand=false"
        val seen=mutableSetOf<String>();val files=mutableListOf<JsonObject>()
        while(next!=null) {
            ensureActive();require(seen.add(next) && seen.size<=8) { "目录分页过多或重复" }
            val (data, link)=json(next)
            files+=data.jsonArray.map {it.jsonObject};require(files.size<=6000) { "目录文件过多" }
            next=link?.let { header -> Regex("<([^>]+)>;\\s*rel=\"next\"").find(header)?.groupValues?.get(1) }?.let { address ->
                val url=address.toHttpUrl();val expected=base.toHttpUrl()
                require(url.encodedPath.startsWith("/api/models/${endpoints.repository}/tree/$revision")) { "无效目录分页路径" }
                require(url.host==expected.host || (expected.host=="hf-mirror.com" && url.host=="huggingface.co")) { "目录分页切换了源" }
                url.newBuilder().scheme(expected.scheme).host(expected.host).port(expected.port).build().toString()
            }
        }
        val byPath=files.filter {it.string("type")=="file"}.associateBy {it.string("path")}
        val metadata=byPath.keys.filter {it.endsWith("/metadata.json") && TranslationModelCatalog.safeRelativePath(it)}
        require(metadata.size<=256) { "目录包数过多" }
        val limit=Semaphore(6)
        val candidates=metadata.map { path -> async {limit.withPermit {
            val directory=path.substringBeforeLast('/')
            val rawFiles=byPath.filterKeys {it.substringBeforeLast('/')==directory && (it.endsWith(".spm") || it.endsWith(".bin"))}
            if(rawFiles.values.any {it["lfs"]==null}) return@withPermit null
            val model=rawFiles.values.singleOrNull {it.string("path").substringAfterLast('/').startsWith("model.")} ?: return@withPermit null
            if(!model.string("path").endsWith(".intgemm.alphas.bin")) return@withPermit null
            val info=json("$base/${endpoints.repository}/resolve/$revision/$path").first.jsonObject
            fun language(value: String)=LocalTranslationLanguage.fromTag(if(value=="zh") "zh-Hans" else value)
            val source=language(info.string("sourceLanguage"));val target=language(info.string("targetLanguage"))
            val assets=rawFiles.values.map {raw ->
                val rawPath=raw.string("path");val name=rawPath.substringAfterLast('/')
                val role=when {name.startsWith("model.")->"model";name.startsWith("lex.")->"lexicalShortlist";name.startsWith("srcvocab.")->"srcVocab";name.startsWith("trgvocab.")->"trgVocab";name.startsWith("vocab.")->"vocab";else->error("未知模型文件")}
                val wire=byPath["$rawPath.gz"]?.takeIf {it["lfs"]!=null} ?: raw
                val wirePath=wire.string("path");val hash=raw.getValue("lfs").jsonObject.string("oid")
                TranslationModelFile(role,name,raw.number("size"),hash,rawPath,rawPath,wire.number("size"),wire.getValue("lfs").jsonObject.string("oid"),
                    if(wire===raw) TranslationFileCompression.NONE else TranslationFileCompression.GZIP,
                    "$base/${endpoints.repository}/resolve/$revision/$wirePath")
            }
            TranslationModelPack("${source.tag}-${target.tag}","hf-${revision.take(12)}-${model.getValue("lfs").jsonObject.string("oid").take(8)}",source,target,assets)
                .also(TranslationModelCatalog::validatePack) to directory.substringBefore('/')
        }} }.awaitAll().filterNotNull()
        require(candidates.isNotEmpty()) { "源目录未提供兼容的完整语言包" }
        fun priority(architecture: String)=when(architecture) {"base-memory"->0;"base"->1;"tiny"->2;else->3}
        candidates.groupBy {it.first.id}.values.map {group -> group.minBy {priority(it.second)}.first}.sortedBy {it.id}
    }

    private suspend fun custom(settings: TranslationSourceSettings): List<TranslationModelPack> {
        val base=TranslationModelCatalog.validateCustomBase(settings.customBaseUrl)
        val catalog=TranslationModelCatalog(json("$base/catalog.json").first.toString())
        val packs = catalog.packs.filter { it.engine == settings.engine }
        require(packs.isNotEmpty()) { "源目录未提供兼容的完整语言包" }
        return packs.map { pack -> pack.copy(files=pack.files.map { file ->
            val path=file.downloadPath ?: "${pack.id}/${file.name}${if(file.compression==TranslationFileCompression.GZIP) ".gz" else ""}"
            require(TranslationModelCatalog.safeRelativePath(path))
            file.copy(url="$base/$path")
        }) }
    }

    private suspend fun json(url: String): Pair<JsonElement,String?> {
        val call=client.newCall(Request.Builder().url(url).build());val context=currentCoroutineContext()
        val watcher=CoroutineScope(context).launch(start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {call.cancel()} }
        try { return call.execute().use { response ->
            if (!response.isSuccessful) throw CatalogHttpException(response.code)
            val body=requireNotNull(response.body);require(body.contentLength()<=4_194_304) { "源目录过大" }
            val bytes=ByteArrayOutputStream()
            body.byteStream().use {stream -> val buffer=ByteArray(16*1024)
                while(true) {context.ensureActive();val n=stream.read(buffer);if(n<0) break
                    require(bytes.size()+n<=4_194_304) {"源目录过大"};bytes.write(buffer,0,n)}
            }
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8.name())) to response.header("Link")
        } } catch(failure:Exception) {context.ensureActive();throw failure}
        finally {withContext(NonCancellable) {watcher.cancelAndJoin()} }
    }

    private fun compareVersions(a:String,b:String):Int {
        val first=a.split('.').map(String::toLong);val second=b.split('.').map(String::toLong)
        for(index in 0 until maxOf(first.size,second.size)) {
            val difference=(first.getOrElse(index){0L}).compareTo(second.getOrElse(index){0L});if(difference!=0) return difference
        };return 0
    }
}
