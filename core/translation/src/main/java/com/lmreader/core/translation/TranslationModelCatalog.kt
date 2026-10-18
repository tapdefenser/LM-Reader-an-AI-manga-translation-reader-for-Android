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
    val languages get() = packs.flatMap { listOf(it.source, it.target) }.distinct().sortedBy { it.tag }

    init {
        val schema = root.getValue("schema").jsonPrimitive.int
        // Keep directional packages cached by previous app versions usable.
        require(schema in 1..3 && (root.string("engine") == "bergamot-v1" ||
            schema == 3 && root.string("engine") == "local-nmt-v1"))
        require(packs.size <= 512 && packs.map { it.id }.distinct().size == packs.size)
        require(packs.map { Triple(it.engine, it.source, it.target) }.distinct().size == packs.size)
        validateCustomBase(officialBaseUrl)
        require(mirrorRepository.matches(Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")))
        require(mirrorRevision.isEmpty() || mirrorRevision.matches(Regex("[a-f0-9]{40}")))
        packs.forEach(::validatePack)
    }

    fun toJson() = root.toString()
    fun pack(id: String) = packs.firstOrNull { it.id == id } ?: error("未知语言包：$id")
    fun forEngine(engine: TranslationModelEngine): TranslationModelCatalog = if (packs.all { it.engine == engine }) this else
        TranslationModelCatalog(JsonObject(root + ("packages" to JsonArray(packs.filter { it.engine == engine }.map(::packJson)))))
    fun withAdditionalPacks(additional: List<TranslationModelPack>) = TranslationModelCatalog(JsonObject(root + mapOf(
        "schema" to JsonPrimitive(3), "engine" to JsonPrimitive("local-nmt-v1"),
        "packages" to JsonArray((packs + additional).map(::packJson)))))
    fun route(source: LocalTranslationLanguage, target: LocalTranslationLanguage,
        engine: TranslationModelEngine? = null): List<TranslationModelPack> {
        if (source == target) return emptyList()
        val selected = engine ?: packs.map { it.engine }.distinct().singleOrNull() ?: TranslationModelEngine.BERGAMOT
        val edges = packs.filter { it.engine == selected }.sortedBy { it.id }.groupBy { it.source }
        // Breadth first: direct packages always win, then the fewest translation
        // stages. No language is required as a pivot; cycles cannot be revisited.
        val queue = ArrayDeque<Pair<LocalTranslationLanguage, List<TranslationModelPack>>>()
        val visited = mutableSetOf(source)
        queue.add(source to emptyList())
        while (queue.isNotEmpty()) {
            val (language, path) = queue.removeFirst()
            if (path.size >= 3) continue
            for (pack in edges[language].orEmpty()) {
                val next = path + pack
                if (pack.target == target) return next
                if (visited.add(pack.target)) queue.add(pack.target to next)
            }
        }
        throw IllegalArgumentException("暂无 ${source.label} → ${target.label} 的模型包")
    }

    fun downloadUrl(pack: TranslationModelPack, file: TranslationModelFile, settings: TranslationSourceSettings): String {
        if (sourceKey != null) require(sourceKey == sourceKey(settings)) { "下载源已改变，请重新获取语言包列表" }
        file.url?.let { address ->
            val url = validateDownloadUrl(address)
            // The bundled OPUS snapshot pins public HF URLs; mirror selection
            // must still work before its first successful metadata refresh.
            if(sourceKey == null && pack.engine == TranslationModelEngine.OPUS_MT &&
                settings.source in setOf(TranslationDownloadSource.HUGGING_FACE, TranslationDownloadSource.HF_MIRROR) &&
                url.toHttpUrl().host in setOf("huggingface.co", "hf-mirror.com")) {
                return url.toHttpUrl().newBuilder().host(if(settings.source == TranslationDownloadSource.HF_MIRROR) "hf-mirror.com" else "huggingface.co").build().toString()
            }
            return url
        }
        val (base, path) = when (settings.source) {
            TranslationDownloadSource.MOZILLA -> officialBaseUrl to file.officialPath
            TranslationDownloadSource.HUGGING_FACE -> "https://huggingface.co" to "$mirrorRepository/resolve/$mirrorRevision/${file.mirrorPath}"
            TranslationDownloadSource.HF_MIRROR -> "https://hf-mirror.com" to "$mirrorRepository/resolve/$mirrorRevision/${file.mirrorPath}"
            TranslationDownloadSource.CUSTOM -> validateCustomBase(settings.customBaseUrl) to (file.downloadPath ?: "${pack.id}/${file.name}.gz")
        }
        return (base.trimEnd('/') + '/').toHttpUrl().newBuilder().addEncodedPathSegments(path).build().toString()
    }

    companion object {
        fun sourceKey(settings: TranslationSourceSettings) =
            (if (settings.engine == TranslationModelEngine.BERGAMOT) "" else "${settings.engine.name}:") +
            settings.source.name + if(settings.source == TranslationDownloadSource.CUSTOM) ":${validateCustomBase(settings.customBaseUrl)}" else ""
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
            require(if (pack.engine == TranslationModelEngine.OPUS_MT)
                roles == setOf("encoder", "decoder", "srcVocab", "trgVocab", "vocab", "config") else
                roles == setOf("model", "lexicalShortlist", "vocab") || roles == setOf("model", "lexicalShortlist", "srcVocab", "trgVocab"))
            require(roles.size == pack.files.size && pack.installedBytes <=
                if (pack.engine == TranslationModelEngine.OPUS_MT) 350_000_000L else 1_073_741_824L)
            require(pack.targetPrefix.length <= 80 && '\u0000' !in pack.targetPrefix)
            require(pack.repository.isEmpty() || pack.repository.matches(Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")))
            require(pack.license.length <= 80)
            pack.files.forEach { file ->
                require(file.name.length in 1..150 && file.name.matches(Regex("[A-Za-z0-9._-]+")) && file.name !in listOf(".", ".."))
                require(file.size in 1..536_870_912L && file.downloadSize in 1..536_870_912L)
                val git = file.gitBlobSha1
                if (git != null) {
                    require(pack.engine == TranslationModelEngine.OPUS_MT && file.role in setOf("config", "vocab", "srcVocab", "trgVocab"))
                    require(git.matches(Regex("[a-f0-9]{40}")) && file.size <= 8_388_608 && file.compression == TranslationFileCompression.NONE)
                    require((file.sha256.isEmpty() && file.downloadSha256.isEmpty()) ||
                        file.sha256.matches(Regex("[a-f0-9]{64}")) && file.downloadSha256.matches(Regex("[a-f0-9]{64}")))
                } else require(file.sha256.matches(Regex("[a-f0-9]{64}")) && file.downloadSha256.matches(Regex("[a-f0-9]{64}")))
                require(safeRelativePath(file.officialPath) && safeRelativePath(file.mirrorPath))
                file.url?.let(::validateDownloadUrl)
                file.downloadPath?.let { require(safeRelativePath(it)) }
            }
        }
        internal fun parsePack(pack: JsonObject): TranslationModelPack {
            // Unknown backends must never be mistaken for Marian models.
            val engine = pack.stringOrNull("engine")?.let(TranslationModelEngine::valueOf) ?: TranslationModelEngine.BERGAMOT
            return TranslationModelPack(pack.string("id"), pack.string("version"),
            LocalTranslationLanguage.fromTag(pack.string("source")), LocalTranslationLanguage.fromTag(pack.string("target")),
            pack.getValue("files").jsonArray.map { element -> val f=element.jsonObject
                TranslationModelFile(f.string("role"), f.string("name"), f.number("size"), f.string("sha256"),
                    f.string("officialPath"), f.string("mirrorPath"), f.number("downloadSize"), f.string("downloadSha256"),
                    f.stringOrNull("compression")?.let(TranslationFileCompression::valueOf) ?: TranslationFileCompression.GZIP,
                    f.stringOrNull("url"), f.stringOrNull("downloadPath"), f.stringOrNull("gitBlobSha1"))
            }, engine, pack.stringOrNull("targetPrefix") ?: "", pack.stringOrNull("license") ?: "", pack.stringOrNull("repository") ?: "")
        }
        internal fun packJson(pack: TranslationModelPack) = buildJsonObject {
            put("id",pack.id);put("version",pack.version);put("source",pack.source.tag);put("target",pack.target.tag)
            put("engine",pack.engine.name)
            if (pack.targetPrefix.isNotEmpty()) put("targetPrefix", pack.targetPrefix)
            if (pack.license.isNotEmpty()) put("license", pack.license)
            if (pack.repository.isNotEmpty()) put("repository", pack.repository)
            putJsonArray("files") {pack.files.forEach { file -> add(buildJsonObject {
                put("role",file.role);put("name",file.name);put("size",file.size);put("sha256",file.sha256)
                put("officialPath",file.officialPath);put("mirrorPath",file.mirrorPath);put("downloadSize",file.downloadSize);put("downloadSha256",file.downloadSha256)
                put("compression",file.compression.name);file.url?.let {put("url",it)};file.downloadPath?.let {put("downloadPath",it)}
                file.gitBlobSha1?.let { put("gitBlobSha1", it) }
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
