package com.lmreader.core.translation

import com.lmreader.core.model.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Immutable metadata snapshot; installed older packages can form a separate runtime catalog. */
class TranslationModelCatalog private constructor(private val root: JsonObject) {
    constructor(json: String): this(Json.parseToJsonElement(json).jsonObject)
    val officialBaseUrl = root.stringOrNull("officialBaseUrl") ?: "https://firefox-settings-attachments.cdn.mozilla.net"
    val mirrorRepository = root.stringOrNull("mirrorRepository") ?: "TiberiuCristianLeon/Bergamot"
    val mirrorRevision = root.stringOrNull("mirrorRevision") ?: ""
    val sourceKey = root.stringOrNull("sourceKey")
    val fetchedAt = root["fetchedAt"]?.jsonPrimitive?.longOrNull
    val packs = root.getValue("packages").jsonArray.map { parsePack(it.jsonObject) }
    val languages = packs.flatMap { it.languages }.distinct().sortedBy { it.tag }

    init {
        val schema = root.getValue("schema").jsonPrimitive.int
        require(schema in 1..3 && root.string("engine") in if (schema == 3) setOf("bergamot-v1", "local-nmt-v1") else setOf("bergamot-v1"))
        require(packs.size <= 512 && packs.map { it.id }.distinct().size == packs.size)
        val directional = packs.filter { it.engine == TranslationModelEngine.BERGAMOT }
        require(directional.map { it.source to it.target }.distinct().size == directional.size)
        require(packs.count { it.engine == TranslationModelEngine.NLLB } <= 1)
        validateCustomBase(officialBaseUrl)
        require(mirrorRepository.matches(Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")))
        require(mirrorRevision.isEmpty() || mirrorRevision.matches(Regex("[a-f0-9]{40}")))
        packs.forEach(::validatePack)
    }

    fun toJson() = root.toString()
    fun pack(id: String) = packs.firstOrNull { it.id == id } ?: error("未知语言包：$id")
    fun route(source: LocalTranslationLanguage, target: LocalTranslationLanguage): List<TranslationModelPack> {
        if (source == target) return emptyList()
        packs.firstOrNull { it.engine == TranslationModelEngine.NLLB && NllbLanguages.supports(source) && NllbLanguages.supports(target) }
            ?.let { return listOf(it) }
        packs.firstOrNull { it.engine == TranslationModelEngine.BERGAMOT && it.source == source && it.target == target }?.let { return listOf(it) }
        val first = packs.firstOrNull { it.engine == TranslationModelEngine.BERGAMOT && it.source == source && it.target == LocalTranslationLanguage.ENGLISH }
        val second = packs.firstOrNull { it.engine == TranslationModelEngine.BERGAMOT && it.source == LocalTranslationLanguage.ENGLISH && it.target == target }
        require(first != null && second != null) { "暂无 ${source.label} → ${target.label} 的模型包" }
        return listOf(first, second)
    }

    fun downloadUrl(pack: TranslationModelPack, file: TranslationModelFile, settings: TranslationSourceSettings): String {
        if (sourceKey != null) require(sourceKey == sourceKey(settings)) { "下载源已改变，请重新获取语言包列表" }
        if (settings.source in setOf(TranslationDownloadSource.NLLB_HUGGING_FACE, TranslationDownloadSource.NLLB_HF_MIRROR))
            require(sourceKey != null && pack.engine == TranslationModelEngine.NLLB) { "请先获取 NLLB-200 模型列表" }
        file.url?.let { return validateDownloadUrl(it) }
        val (base, path) = when (settings.source) {
            TranslationDownloadSource.MOZILLA -> officialBaseUrl to file.officialPath
            TranslationDownloadSource.HUGGING_FACE -> "https://huggingface.co" to "$mirrorRepository/resolve/$mirrorRevision/${file.mirrorPath}"
            TranslationDownloadSource.HF_MIRROR -> "https://hf-mirror.com" to "$mirrorRepository/resolve/$mirrorRevision/${file.mirrorPath}"
            TranslationDownloadSource.NLLB_HUGGING_FACE, TranslationDownloadSource.NLLB_HF_MIRROR -> error("NLLB 模型文件缺少下载地址")
            TranslationDownloadSource.CUSTOM -> validateCustomBase(settings.customBaseUrl) to (file.downloadPath ?: "${pack.id}/${file.name}.gz")
        }
        return (base.trimEnd('/') + '/').toHttpUrl().newBuilder().addEncodedPathSegments(path).build().toString()
    }

    companion object {
        fun sourceKey(settings: TranslationSourceSettings) = settings.source.name + if(settings.source == TranslationDownloadSource.CUSTOM) ":${validateCustomBase(settings.customBaseUrl)}" else ""
        fun validateCustomBase(value: String): String {
            val url = value.trim().toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "下载源需为不含账号、查询参数和片段的 HTTP(S) 目录地址" }
            return url.toString().trimEnd('/')
        }
        fun validateDownloadUrl(value: String) = validateCustomBase(value)
        internal fun safeRelativePath(path: String) = path.isNotEmpty() && !path.startsWith('/') &&
            '\\' !in path && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
            '%' !in path && '?' !in path && '#' !in path && ':' !in path
        internal fun safeVersion(version: String) = version.length in 1..100 && version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) && ".." !in version
        internal fun validatePack(pack: TranslationModelPack) {
            require(pack.id.length in 1..100 && pack.id.matches(Regex("[A-Za-z0-9-]+")) && safeVersion(pack.version))
            require(pack.source != pack.target)
            require(pack.files.isNotEmpty() && pack.files.map { it.name }.distinct().size == pack.files.size)
            val roles = pack.files.map { it.role }.toSet()
            require(if (pack.engine == TranslationModelEngine.NLLB) roles == setOf("encoder", "decoder", "tokenizer") else
                roles == setOf("model", "lexicalShortlist", "vocab") || roles == setOf("model", "lexicalShortlist", "srcVocab", "trgVocab"))
            require(roles.size == pack.files.size && pack.installedBytes <= 1_073_741_824L)
            pack.files.forEach { file ->
                require(file.name.length in 1..150 && file.name.matches(Regex("[A-Za-z0-9._-]+")) && file.name !in listOf(".", ".."))
                require(file.size in 1..536_870_912L && file.downloadSize in 1..536_870_912L)
                require(file.sha256.matches(Regex("[a-f0-9]{64}")) && file.downloadSha256.matches(Regex("[a-f0-9]{64}")))
                require(safeRelativePath(file.officialPath) && safeRelativePath(file.mirrorPath))
                file.url?.let(::validateDownloadUrl)
                file.downloadPath?.let { require(safeRelativePath(it)) }
            }
        }
        internal fun parsePack(pack: JsonObject) = TranslationModelPack(pack.string("id"), pack.string("version"),
            LocalTranslationLanguage.fromTag(pack.string("source")), LocalTranslationLanguage.fromTag(pack.string("target")),
            pack.getValue("files").jsonArray.map { element -> val f=element.jsonObject
                TranslationModelFile(f.string("role"), f.string("name"), f.number("size"), f.string("sha256"),
                    f.string("officialPath"), f.string("mirrorPath"), f.number("downloadSize"), f.string("downloadSha256"),
                    f.stringOrNull("compression")?.let(TranslationFileCompression::valueOf) ?: TranslationFileCompression.GZIP,
                    f.stringOrNull("url"), f.stringOrNull("downloadPath"))
            }, pack.stringOrNull("engine")?.let(TranslationModelEngine::valueOf) ?: TranslationModelEngine.BERGAMOT)
        internal fun packJson(pack: TranslationModelPack) = buildJsonObject {
            put("id",pack.id);put("version",pack.version);put("source",pack.source.tag);put("target",pack.target.tag)
            put("engine",pack.engine.name)
            putJsonArray("files") {pack.files.forEach { file -> add(buildJsonObject {
                put("role",file.role);put("name",file.name);put("size",file.size);put("sha256",file.sha256)
                put("officialPath",file.officialPath);put("mirrorPath",file.mirrorPath);put("downloadSize",file.downloadSize);put("downloadSha256",file.downloadSha256)
                put("compression",file.compression.name);file.url?.let {put("url",it)};file.downloadPath?.let {put("downloadPath",it)}
            }) } }
        }
        fun fromPacks(packs: List<TranslationModelPack>, source: TranslationSourceSettings? = null, fetchedAt: Long? = null) = TranslationModelCatalog(buildJsonObject {
            put("schema",3);put("engine","local-nmt-v1")
            source?.let {put("sourceKey",sourceKey(it))};fetchedAt?.let {put("fetchedAt",it)}
            putJsonArray("packages") {packs.forEach { add(packJson(it)) }}
        })
        internal fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
        internal fun JsonObject.stringOrNull(key: String) = get(key)?.jsonPrimitive?.contentOrNull
        internal fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.long
    }
}
