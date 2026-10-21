package com.lmreader.core.api

import com.lmreader.core.model.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 协议组装与校验，独立于 Android、存储和 HTTP 传输。 */
object ApiProtocol {
    private val protectedFields = setOf("model", "messages", "input", "contents", "stream", "systemInstruction")
    private const val MAX_MEDIA_BASE64 = 24_000_000L

    fun validate(profile: ApiProfile, requireModel: Boolean = true) {
        val url = profile.url.trim().toHttpUrlOrNull()
        require(url != null && url.scheme in setOf("http", "https")) { "API 地址必须是有效的 HTTP(S) 地址" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "请通过 API Key 填写凭据，不要放进地址" }
        require(url.query == null && url.fragment == null) { "API 地址不能包含查询参数或片段" }
        require(!requireModel || profile.model.isNotBlank()) { "请填写或选择模型名称" }
        require(profile.timeoutSeconds in 30..1200) { "API 超时应为 30～1200 秒" }
        require(profile.retryCount in 0..50) { "API 重试次数应为 0～50" }
        require(profile.parallelLimit in 1..200) { "API 并行限制应为 1～200" }
        validateParameters(profile.parameters)
        customParameters(profile.customParameters)
    }

    fun validateParameters(p: AiParameters) {
        p.temperature?.let { require(it.isFinite() && it in 0.0..2.0) { "temperature 应为 0～2" } }
        p.topP?.let { require(it.isFinite() && it in 0.0..1.0) { "top_p 应为 0～1" } }
        p.topK?.let { require(it >= 1) { "top_k 应为正整数" } }
        p.maxTokens?.let { require(it >= 1) { "最大输出 token 应为正整数" } }
        p.frequencyPenalty?.let { require(it.isFinite() && it in -2.0..2.0) { "frequency_penalty 应为 -2～2" } }
        p.presencePenalty?.let { require(it.isFinite() && it in -2.0..2.0) { "presence_penalty 应为 -2～2" } }
    }

    fun customParameters(value: String): JsonObject {
        val parsed = try { Json.parseToJsonElement(value.ifBlank { "{}" }) } catch (_: Exception) {
            throw IllegalArgumentException("自定义请求参数必须是有效的 JSON 对象")
        }
        require(parsed is JsonObject) { "自定义请求参数必须是 JSON 对象" }
        val forbidden = parsed.keys.intersect(protectedFields)
        require(forbidden.isEmpty()) { "自定义参数不能覆盖：${forbidden.joinToString()}" }
        return parsed
    }

    private fun base(profile: ApiProfile): HttpUrl {
        val url = profile.url.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("API 地址无效")
        val suffix = when (profile.format) {
            ApiFormat.CHAT -> "/chat/completions"
            ApiFormat.RESPONSES -> "/responses"
            ApiFormat.GEMINI -> null
        }
        val path = url.encodedPath.trimEnd('/')
        return if (suffix != null && path.endsWith(suffix)) {
            url.newBuilder().encodedPath(path.removeSuffix(suffix).ifBlank { "/" }).build()
        } else url
    }

    fun endpoint(profile: ApiProfile, models: Boolean = false): HttpUrl {
        val base = base(profile)
        val builder = base.newBuilder()
        if (models) return builder.addPathSegment("models").build()
        return when (profile.format) {
            ApiFormat.CHAT -> builder.addPathSegments("chat/completions").build()
            ApiFormat.RESPONSES -> builder.addPathSegment("responses").build()
            ApiFormat.GEMINI -> {
                val rootPath = base.encodedPath.substringBefore("/models/").trimEnd('/')
                base.newBuilder().encodedPath(rootPath.ifBlank { "/" })
                    .addPathSegment("models")
                    .addPathSegment(profile.model.removePrefix("models/") + ":streamGenerateContent")
                    .addQueryParameter("alt", "sse").build()
            }
        }
    }

    fun requestBody(profile: ApiProfile, prompt: String): String = requestBody(profile, listOf(ApiMessage("user", prompt)))

    fun requestBody(profile: ApiProfile, messages: List<ApiMessage>): String {
        validate(profile)
        require(messages.isNotEmpty() && messages.size <= 200 && messages.any { it.text.isNotBlank() || it.images.isNotEmpty() || it.audios.isNotEmpty() }) { "请求消息不能为空或过多" }
        require(messages.all { it.audios.isEmpty() || it.role == "user" }) { "仅用户消息可以包含音频附件" }
        val mediaLength = messages.sumOf { message -> message.images.sumOf { it.base64.length.toLong() } + message.audios.sumOf { it.base64.length.toLong() } }
        require(mediaLength <= MAX_MEDIA_BASE64) { "图片与音频附件合计不能超过 24 MB" }
        val p = profile.parameters
        val body = buildJsonObject {
            when (profile.format) {
                ApiFormat.CHAT -> {
                    put("model", profile.model.trim()); put("stream", true)
                    put("messages", buildJsonArray { messages.forEach { message -> add(buildJsonObject {
                        put("role", message.role)
                        if (message.images.isEmpty() && message.audios.isEmpty()) put("content", message.text) else put("content", buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", message.text) })
                            message.images.forEach { img -> add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", img.dataUrl) }) }) }
                            message.audios.forEach { audio -> add(buildJsonObject {
                                put("type", "input_audio"); put("input_audio", buildJsonObject { put("data", audio.base64); put("format", audio.format) })
                            }) }
                        })
                    }) } })
                    if (profile.thinkingEnabled) put("reasoning_effort", profile.thinkingLevel.wireValue)
                }
                ApiFormat.RESPONSES -> {
                    require(messages.none { it.audios.isNotEmpty() }) { "当前 Responses 协议不支持音频附件，请选择支持音频的 Chat Completions 或 Gemini 配置" }
                    put("model", profile.model.trim()); put("stream", true)
                    if (messages.size == 1 && messages.single().role == "user" && messages.single().images.isEmpty() && messages.single().audios.isEmpty()) put("input", messages.single().text)
                    else put("input", buildJsonArray { messages.forEach { message -> add(buildJsonObject {
                        put("role", message.role); put("content", buildJsonArray {
                            add(buildJsonObject { put("type", if (message.role == "assistant") "output_text" else "input_text"); put("text", message.text) })
                            require(message.images.isEmpty() || message.role != "assistant") { "助手消息不能包含输入图片" }
                            message.images.forEach { img -> add(buildJsonObject { put("type", "input_image"); put("image_url", img.dataUrl) }) }
                        })
                    }) } })
                    if (profile.thinkingEnabled) put("reasoning", buildJsonObject { put("effort", profile.thinkingLevel.wireValue) })
                }
                ApiFormat.GEMINI -> {
                    val system = messages.filter { it.role == "system" }
                    if (system.isNotEmpty()) put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { system.forEach { add(buildJsonObject { put("text", it.text) }) } }) })
                    put("contents", buildJsonArray { messages.filter { it.role != "system" }.forEach { message -> add(buildJsonObject {
                        put("role", if (message.role == "assistant") "model" else "user"); put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", message.text) })
                            message.images.forEach { img -> add(buildJsonObject { put("inlineData", buildJsonObject { put("mimeType", img.mimeType); put("data", img.base64) }) }) }
                            message.audios.forEach { audio -> add(buildJsonObject { put("inlineData", buildJsonObject { put("mimeType", audio.normalizedMimeType); put("data", audio.base64) }) }) }
                        })
                    }) } })
                    put("generationConfig", buildJsonObject {
                        p.temperature?.let { put("temperature", it) }; p.topP?.let { put("topP", it) }
                        p.topK?.let { put("topK", it) }; p.maxTokens?.let { put("maxOutputTokens", it) }
                        p.frequencyPenalty?.let { put("frequencyPenalty", it) }; p.presencePenalty?.let { put("presencePenalty", it) }
                        if (profile.thinkingEnabled) put("thinkingConfig", buildJsonObject { put("thinkingLevel", profile.thinkingLevel.wireValue.uppercase()) })
                    })
                }
            }
            if (profile.format != ApiFormat.GEMINI) {
                p.temperature?.let { put("temperature", it) }; p.topP?.let { put("top_p", it) }
                p.topK?.let { put("top_k", it) }
                p.maxTokens?.let { put(if (profile.format == ApiFormat.RESPONSES) "max_output_tokens" else "max_tokens", it) }
                p.frequencyPenalty?.let { put("frequency_penalty", it) }; p.presencePenalty?.let { put("presence_penalty", it) }
            }
        }
        return merge(body, customParameters(profile.customParameters)).toString()
    }

    private fun merge(base: JsonObject, overrides: JsonObject): JsonObject = JsonObject(base.toMutableMap().apply {
        overrides.forEach { (key, value) ->
            val old = this[key]
            this[key] = if (old is JsonObject && value is JsonObject) merge(old, value) else value
        }
    })

    fun models(format: ApiFormat, payload: String): Pair<List<String>, String?> {
        val root = Json.parseToJsonElement(payload).jsonObject
        val array = root[if (format == ApiFormat.GEMINI) "models" else "data"]?.jsonArray
            ?: throw ApiException("模型列表响应缺少 ${if (format == ApiFormat.GEMINI) "models" else "data"}")
        val result = array.mapNotNull { value ->
            val item = value.jsonObject
            if (format == ApiFormat.GEMINI) {
                val methods = item["supportedGenerationMethods"]?.jsonArray?.map { it.jsonPrimitive.content }
                if (methods != null && "generateContent" !in methods) null
                else item["name"]?.jsonPrimitive?.content?.removePrefix("models/")
            } else item["id"]?.jsonPrimitive?.content
        }.filter { it.isNotBlank() }.distinct()
        return result to root["nextPageToken"]?.jsonPrimitive?.content
    }
}

/** 可安全呈现给用户的错误；认证与格式错误不会自动重试。 */
class ApiException(message: String, val httpCode: Int? = null, val retryAfterMillis: Long? = null) : java.io.IOException(message)
