package com.lmreader.core.model

/** BCP 47 identity; display names come from the platform, not an app-maintained dictionary. */
data class LocalTranslationLanguage private constructor(val tag: String) {
    private val locale get() = java.util.Locale.forLanguageTag(tag)
    val nativeName: String get() = locale.getDisplayName(locale).ifBlank { tag }
    fun localizedName(displayLocale: java.util.Locale) = locale.getDisplayName(displayLocale).ifBlank { tag }
    val label: String get() = localizedName(java.util.Locale.getDefault())
    companion object {
        val ENGLISH = fromTag("en")
        val JAPANESE = fromTag("ja")
        val KOREAN = fromTag("ko")
        val CHINESE_SIMPLIFIED = fromTag("zh-Hans")
        val CHINESE_TRADITIONAL = fromTag("zh-Hant")
        fun fromTag(value: String): LocalTranslationLanguage {
            val tag = value.trim().replace('_', '-')
            require(tag.length in 2..80 && tag.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))) { "请输入语言代码，例如 id、de、lzh、zh-Hant" }
            val locale = java.util.Locale.Builder().setLanguageTag(tag).build()
            require(locale.language.isNotBlank() && locale.language != "und") { "无效的语言代码" }
            return LocalTranslationLanguage(locale.toLanguageTag())
        }
    }
}

enum class TranslationDownloadSource(val label: String) {
    NLLB_HUGGING_FACE("NLLB-200 多语言直译 · Hugging Face"),
    NLLB_HF_MIRROR("NLLB-200 多语言直译 · HF-Mirror"),
    MOZILLA("Mozilla 官方源"), HUGGING_FACE("Hugging Face 社区副本"),
    HF_MIRROR("HF-Mirror 社区镜像"), CUSTOM("自定义源");
}

data class TranslationSourceSettings(
    val source: TranslationDownloadSource = TranslationDownloadSource.MOZILLA,
    val customBaseUrl: String = "",
    val wifiOnly: Boolean = true,
)

data class TranslationModelFile(
    val role: String, val name: String, val size: Long, val sha256: String,
    val officialPath: String, val mirrorPath: String, val downloadSize: Long,
    val downloadSha256: String,
    val compression: TranslationFileCompression = TranslationFileCompression.GZIP,
    val url: String? = null,
    val downloadPath: String? = null,
)

enum class TranslationFileCompression { GZIP, NONE }

enum class TranslationModelEngine { BERGAMOT, NLLB }

data class TranslationModelPack(
    val id: String, val version: String,
    val source: LocalTranslationLanguage, val target: LocalTranslationLanguage,
    val files: List<TranslationModelFile>,
    val engine: TranslationModelEngine = TranslationModelEngine.BERGAMOT,
) {
    val languages get() = if (engine == TranslationModelEngine.NLLB) NllbLanguages.languages else listOf(source, target)
    val label get() = if (engine == TranslationModelEngine.NLLB) "NLLB-200" else "${source.label} → ${target.label}"
    val downloadBytes get() = files.sumOf { it.downloadSize }
    val installedBytes get() = files.sumOf { it.size }
    val identity get() = "$id@$version"
    fun sameContents(other: TranslationModelPack) = id==other.id && version==other.version && engine==other.engine &&
        files.map { listOf(it.role,it.name,it.size.toString(),it.sha256) }.sortedBy {it[0]} == other.files.map { listOf(it.role,it.name,it.size.toString(),it.sha256) }.sortedBy {it[0]}
}

enum class TranslationPackStatus { NOT_INSTALLED, DOWNLOADING, PAUSED, VERIFYING, READY, FAILED }

data class TranslationPackState(
    val status: TranslationPackStatus = TranslationPackStatus.NOT_INSTALLED,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val message: String? = null,
)

data class LocalTranslationText(val id: String, val sourceText: String)
data class LocalTranslatedText(val id: String, val sourceText: String, val translatedText: String)
data class LocalTranslationResult(
    val items: List<LocalTranslatedText>, val modelPackIds: List<String>, val elapsedMillis: Long,
    val backend: String = "Bergamot · CPU",
)

interface LocalTextTranslator {
    suspend fun translate(
        source: LocalTranslationLanguage, target: LocalTranslationLanguage,
        items: List<LocalTranslationText>, onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): LocalTranslationResult
    suspend fun releaseModels()
}
