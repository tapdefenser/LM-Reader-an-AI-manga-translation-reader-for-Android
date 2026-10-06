package com.lmreader.core.api

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.*

data class ApiTraceContext(val mangaId: String = "", val mangaName: String = "配置测试",
    val chapterName: String = "", val pageName: String = "", val stepName: String = "") : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ApiTraceContext>
}

/** One invocation links its HTTP attempts to later structured-output validation. */
class ApiTraceCapture : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ApiTraceCapture>
    val requestId = java.util.concurrent.atomic.AtomicReference<String?>()
}
data class ApiRequestInfo(val context: ApiTraceContext, val profileName: String, val model: String,
    val url: String, val method: String, val format: String, val attempt: Int, val request: String)
data class ApiRequestOutcome(val status: String, val response: String = "", val thinking: String = "",
    val error: String = "", val httpCode: Int? = null)
interface ApiRequestJournal {
    suspend fun begin(info: ApiRequestInfo): String
    suspend fun finish(id: String, outcome: ApiRequestOutcome)
}

/** Store attachment metadata, never multi-megabyte base64 payloads or authorization headers. */
object ApiLogPayload {
    fun sanitize(text: String): String {
        fun clean(value: JsonElement, key: String = ""): JsonElement = when(value) {
            is JsonObject -> JsonObject(value.mapValues { (k, v) -> clean(v, k) })
            is JsonArray -> JsonArray(value.map { clean(it) })
            is JsonPrimitive -> {
                val content = value.content
                when {
                    key.lowercase() in setOf("api_key", "apikey", "authorization", "x-goog-api-key") -> JsonPrimitive("[已隐藏]")
                    value.isString && key in setOf("data", "base64") && content.length > 100 -> JsonPrimitive("[图片附件：约 ${content.length * 3 / 4} 字节]")
                    value.isString && content.startsWith("data:image/") -> JsonPrimitive("[图片附件：${content.substringBefore(';')}，约 ${content.substringAfter(',').length * 3 / 4} 字节]")
                    else -> value
                }
            }
            else -> value
        }
        return runCatching { clean(Json.parseToJsonElement(text)).toString() }.getOrDefault(text).take(1_000_000)
    }
}
