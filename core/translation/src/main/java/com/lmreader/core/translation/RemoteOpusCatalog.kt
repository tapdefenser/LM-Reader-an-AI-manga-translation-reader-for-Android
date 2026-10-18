package com.lmreader.core.translation

import com.lmreader.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*

/** Discover ready-to-run, version-pinned ONNX packages. No model/tokenizer payloads are fetched here. */
internal class RemoteOpusCatalog(private val host: String, private val json: suspend (String) -> JsonElement) {
    suspend fun fetch(): List<TranslationModelPack> = coroutineScope {
        val repositories = json("$host/api/models?author=Xenova&search=opus-mt-&limit=1000").jsonArray
        require(repositories.size <= 512)
        val names = repositories.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
            .filter { it.matches(Regex("Xenova/opus-mt-[a-z]{2}-[a-z]{2}")) }.distinct().sorted()
        val limit = Semaphore(4)
        val packs = names.map { name -> async { limit.withPermit {
            suspend fun optionalInfo(url: String): JsonObject? = try { json(url).jsonObject }
                catch (failure: CatalogHttpException) { if (failure.status == 404) null else throw failure }
            val info = optionalInfo("$host/api/models/$name?blobs=true") ?: return@withPermit null
            fun declaredLicense(data: JsonObject): String? {
                val value = (data["cardData"] as? JsonObject)?.get("license")
                val values = if (value is JsonArray) value else listOfNotNull(value)
                return values.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .firstOrNull { it in setOf("apache-2.0", "cc-by-4.0") }
            }
            val license = declaredLicense(info) ?: optionalInfo("$host/api/models/Helsinki-NLP/${name.substringAfter('/')}")?.let(::declaredLicense)
                ?: return@withPermit null
            // Network failures abort refresh and preserve the old cache. Only
            // incompatible metadata is skipped, never a failed network response.
            runCatching { parse(name, info, host, license) }.getOrNull()
        }} }.awaitAll().filterNotNull()
        require(packs.isNotEmpty()) { "源目录未提供兼容的完整语言包" }
        packs
    }

    companion object {
        private val files = linkedMapOf(
            "encoder" to ("onnx/encoder_model_quantized.onnx" to "encoder.onnx"),
            "decoder" to ("onnx/decoder_model_merged_quantized.onnx" to "decoder.onnx"),
            "srcVocab" to ("source.spm" to "source.spm"), "trgVocab" to ("target.spm" to "target.spm"),
            "vocab" to ("vocab.json" to "vocab.json"), "config" to ("config.json" to "config.json"))
        internal fun parse(repository: String, info: JsonObject, host: String, license: String): TranslationModelPack {
            require(repository.matches(Regex("Xenova/opus-mt-[a-z]{2}-[a-z]{2}")))
            val revision = info.getValue("sha").jsonPrimitive.content
            require(revision.matches(Regex("[a-f0-9]{40}")))
            val pair = repository.substringAfter("opus-mt-").split('-')
            fun language(tag: String) = LocalTranslationLanguage.fromTag(if (tag == "zh") "zh-Hans" else tag)
            val source = language(pair[0]); val target = language(pair[1])
            val siblings = info.getValue("siblings").jsonArray.map { it.jsonObject }.associateBy { it.getValue("rfilename").jsonPrimitive.content }
            val assets = files.map { (role, paths) ->
                val raw = siblings.getValue(paths.first)
                val size = raw.getValue("size").jsonPrimitive.long
                val lfs = raw["lfs"]?.takeUnless { it is JsonNull }?.jsonObject
                val sha256 = lfs?.get("sha256")?.jsonPrimitive?.content ?: ""
                val git = if (lfs == null) raw.getValue("blobId").jsonPrimitive.content else null
                TranslationModelFile(role, paths.second, size, sha256, paths.first, paths.first, size, sha256,
                    TranslationFileCompression.NONE, "$host/$repository/resolve/$revision/${paths.first}", paths.first, git)
            }
            return TranslationModelPack("opus-${source.tag}-${target.tag}", "hf-${revision.take(12)}", source, target,
                assets, TranslationModelEngine.OPUS_MT,
                if (source.tag == "en" && target.tag == "zh-Hans") ">>cmn_Hans<<" else "", license, repository)
                .also(TranslationModelCatalog::validatePack)
        }
    }
}
