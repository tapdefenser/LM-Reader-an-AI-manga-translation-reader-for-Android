package com.lmreader.core.translation.asr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File

/**
 * A single immutable whisper.cpp GGML model. The descriptor is metadata only; weights are
 * never bundled in the APK and only become [AsrModelStatus.READY] after byte-count and
 * SHA-256 verification of the staged file.
 */
data class AsrModelDescriptor(
    val id: String,
    val label: String,
    val version: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
    val languages: List<String>,
) {
    val identity: String get() = "$id@$version"
}

enum class AsrModelStatus { NOT_INSTALLED, DOWNLOADING, PAUSED, VERIFYING, READY, FAILED }

data class AsrModelState(
    val status: AsrModelStatus = AsrModelStatus.NOT_INSTALLED,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val message: String? = null,
) {
    val busy: Boolean get() = status == AsrModelStatus.DOWNLOADING || status == AsrModelStatus.VERIFYING
}

data class AsrSettings(
    val wifiOnly: Boolean = true,
    val threads: Int = asrThreadLimit(),
    val selectedId: String? = null,
    val backend: AsrBackend = AsrBackend.AUTO,
)

/** whisper.cpp keeps at most four CPU workers useful on the currently supported phone ABIs. */
fun asrThreadLimit(cores: Int = Runtime.getRuntime().availableProcessors()): Int = cores.coerceIn(1, 4)

/**
 * Bundled, verified metadata for the two multilingual whisper.cpp GGML models.
 *
 * Sources:
 * - https://github.com/ggml-org/whisper.cpp/blob/master/models/README.md (multilingual = no `.en`)
 * - https://huggingface.co/ggerganov/whisper.cpp/tree/main
 * - https://huggingface.co/api/models/ggerganov/whisper.cpp/tree/main?recursive=true&expand=true
 *
 * The URLs are pinned to the file commit reported by the Hugging Face metadata API so a
 * later repository update cannot silently change the bytes behind a fixed SHA-256.
 * Sizes and LFS SHA-256 values were read from that API on 2026-10-20:
 * - ggml-tiny.bin: 77,691,713 bytes, be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21
 * - ggml-base.bin: 147,951,465 bytes, 60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe
 */
val BUNDLED_ASR_MODELS: List<AsrModelDescriptor> = listOf(
    AsrModelDescriptor(
        id = "whisper-tiny-multilingual",
        label = "Whisper Tiny（多语言）",
        version = "80da2d8",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/80da2d8bfee42b0e836fc3a9890373e5defc00a6/ggml-tiny.bin",
        sha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
        bytes = 77_691_713L,
        languages = listOf("multilingual"),
    ),
    AsrModelDescriptor(
        id = "whisper-base-multilingual",
        label = "Whisper Base（多语言）",
        version = "80da2d8",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/80da2d8bfee42b0e836fc3a9890373e5defc00a6/ggml-base.bin",
        sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
        bytes = 147_951_465L,
        languages = listOf("multilingual"),
    ),
)

internal object AsrCatalogJson {
    const val SCHEMA = 1
    private val ID = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,79}")
    private val VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
    private val SHA256 = Regex("[a-f0-9]{64}")

    fun parseCatalog(text: String): List<AsrModelDescriptor> {
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.int("schema") == SCHEMA) { "不支持的 ASR 模型目录版本" }
        val models = root["models"]?.jsonArray?.map { parseDescriptor(it.jsonObject) }
            ?: error("ASR 模型目录缺少 models")
        require(models.size in 1..64) { "ASR 模型目录数量无效" }
        require(models.map { it.id }.distinct().size == models.size) { "ASR 模型目录包含重复 ID" }
        models.forEach(::validate)
        return models
    }

    fun toCatalogJson(models: List<AsrModelDescriptor>): String = buildJsonObject {
        put("schema", SCHEMA)
        putJsonArray("models") { models.forEach { add(descriptorJson(it)) } }
    }.toString()

    fun parseDescriptor(root: JsonObject): AsrModelDescriptor = AsrModelDescriptor(
        id = root.string("id"),
        label = root.string("label"),
        version = root.string("version"),
        url = root.string("url"),
        sha256 = root.string("sha256"),
        bytes = root.getValue("bytes").jsonPrimitive.longOrNull ?: error("ASR 模型大小无效"),
        languages = (root["languages"]?.jsonArray ?: error("ASR 模型缺少 languages")).map {
            it.jsonPrimitive.contentOrNull ?: error("ASR 模型语言项无效")
        },
    )

    fun descriptorJson(model: AsrModelDescriptor): JsonObject = buildJsonObject {
        put("id", model.id)
        put("label", model.label)
        put("version", model.version)
        put("url", model.url)
        put("sha256", model.sha256)
        put("bytes", model.bytes)
        putJsonArray("languages") { model.languages.forEach { add(JsonPrimitive(it)) } }
    }

    fun validate(model: AsrModelDescriptor) {
        require(model.id.matches(ID)) { "ASR 模型 ID 非法" }
        require(model.label.isNotBlank() && model.label.length <= 120) { "ASR 模型名称非法" }
        require(model.version.matches(VERSION) && ".." !in model.version) { "ASR 模型版本非法" }
        require(model.url.length in 1..2048) { "ASR 模型下载地址过长" }
        val url = model.url.toHttpUrl()
        require(url.scheme == "https" && url.host.isNotBlank() &&
            url.username.isEmpty() && url.password.isEmpty()) { "ASR 模型下载地址必须是 HTTPS 且不能包含账号" }
        require(model.sha256.matches(SHA256)) { "ASR 模型 SHA-256 非法" }
        require(model.bytes in 1..2_147_483_648L) { "ASR 模型大小超出限制" }
        require(model.languages.isNotEmpty() && model.languages.size <= 128 &&
            model.languages.all { it.isNotBlank() && it.length <= 40 }) { "ASR 模型语言列表非法" }
    }

    internal fun JsonObject.string(key: String): String =
        get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("ASR 模型目录缺少 $key")

    internal fun JsonObject.int(key: String): Int =
        get(key)?.jsonPrimitive?.intOrNull ?: error("ASR 模型目录缺少 $key")
}

internal data class AsrManifest(
    val descriptor: AsrModelDescriptor,
    val modelFile: String,
    val installedAtEpochMs: Long,
) {
    companion object {
        const val SCHEMA = 1
        const val MANIFEST_FILE = "manifest.json"
        const val DESCRIPTOR_FILE = "descriptor.json"
        const val MODEL_FILE = "model.bin"
        private const val MAX_MANIFEST_BYTES = 131_072L

        fun write(file: File, model: AsrModelDescriptor, installedAtEpochMs: Long = System.currentTimeMillis()) {
            val json = buildJsonObject {
                put("schema", SCHEMA)
                put("modelFile", MODEL_FILE)
                put("installedAtEpochMs", installedAtEpochMs)
                put("descriptor", AsrCatalogJson.descriptorJson(model))
            }.toString()
            AsrFileOps.atomicText(file, json)
        }

        fun read(file: File): AsrManifest = with(AsrCatalogJson) {
            require(file.isFile && file.length() in 1..MAX_MANIFEST_BYTES) { "ASR 安装清单缺失或损坏" }
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            require(root.int("schema") == SCHEMA) { "不支持的 ASR 安装清单版本" }
            val modelFile = root.string("modelFile")
            require(modelFile == MODEL_FILE) { "ASR 安装清单模型文件名非法" }
            val descriptor = AsrCatalogJson.parseDescriptor(root.getValue("descriptor").jsonObject)
            AsrCatalogJson.validate(descriptor)
            val installedAt = root["installedAtEpochMs"]?.jsonPrimitive?.longOrNull ?: 0L
            AsrManifest(descriptor, modelFile, installedAt)
        }
    }
}

/** Small metadata file whose replacement must not leave a half-written catalog. */
internal class AsrCatalogStore(private val file: File) {
    fun load(): List<AsrModelDescriptor> {
        val persisted = runCatching {
            require(file.isFile && file.length() in 1..1_048_576)
            AsrCatalogJson.parseCatalog(file.readText())
        }.getOrNull()
        val models = when {
            persisted.isNullOrEmpty() -> BUNDLED_ASR_MODELS
            else -> merge(persisted)
        }
        if (persisted != models) {
            // A corrupt or older cache is replaced with the bundled valid metadata. The
            // weights themselves are untouched and remain usable only if their hash matches.
            runCatching { save(models) }
        }
        return models
    }

    fun save(models: List<AsrModelDescriptor>) {
        models.forEach(AsrCatalogJson::validate)
        AsrFileOps.atomicText(file, AsrCatalogJson.toCatalogJson(models))
    }

    private fun merge(persisted: List<AsrModelDescriptor>): List<AsrModelDescriptor> {
        val byId = persisted.associateBy { it.id }
        val bundledIds = BUNDLED_ASR_MODELS.map { it.id }.toSet()
        val bundled = BUNDLED_ASR_MODELS.map { bundledModel ->
            // The app ships the exact official metadata. A cache can only keep the same
            // immutable identity for a bundled id; stale hashes must not survive an update.
            byId[bundledModel.id]?.takeIf {
                it.version == bundledModel.version && it.sha256 == bundledModel.sha256 &&
                    it.bytes == bundledModel.bytes
            } ?: bundledModel
        }
        val additional = persisted.filter { it.id !in bundledIds }
        return bundled + additional
    }
}

internal class AsrSettingsStore(
    private val preferences: android.content.SharedPreferences,
    private val threadLimit: Int,
) {
    fun load(): AsrSettings {
        val wifiOnly = preferences.getBoolean(KEY_WIFI_ONLY, true)
        val storedThreads = preferences.getInt(KEY_THREADS, threadLimit)
        val threads = storedThreads.coerceIn(1, threadLimit)
        val selectedId = preferences.getString(KEY_SELECTED_ID, null)?.takeIf { it.isNotBlank() }
        val backend = AsrBackend.fromWire(preferences.getString(KEY_BACKEND, null))
        if (storedThreads != threads) preferences.edit().putInt(KEY_THREADS, threads).apply()
        return AsrSettings(wifiOnly, threads, selectedId, backend)
    }

    fun saveWifiOnly(value: Boolean) {
        preferences.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
    }

    fun saveThreads(value: Int) {
        preferences.edit().putInt(KEY_THREADS, value.coerceIn(1, threadLimit)).apply()
    }

    fun saveSelected(id: String?) {
        preferences.edit().apply {
            if (id == null) remove(KEY_SELECTED_ID) else putString(KEY_SELECTED_ID, id)
        }.apply()
    }

    fun saveBackend(value: AsrBackend) {
        preferences.edit().putString(KEY_BACKEND, value.wireId).apply()
    }

    companion object {
        const val PREFERENCES_NAME = "local-asr-settings"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_THREADS = "threads"
        private const val KEY_SELECTED_ID = "selected_model_id"
        private const val KEY_BACKEND = "backend"
    }
}
