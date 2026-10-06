package com.lmreader.core.api

import com.lmreader.core.model.ApiFormat
import com.lmreader.core.model.ApiProfile
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** 可替换的调用边界，供工作流与设置界面共享。 */
interface ApiGateway {
    suspend fun models(profile: ApiProfile): List<String>
    fun stream(profile: ApiProfile, prompt: String): Flow<ApiStreamEvent>
    fun stream(profile: ApiProfile, messages: List<ApiMessage>): Flow<ApiStreamEvent> {
        require(messages.size == 1 && messages.single().role == "user" && messages.single().images.isEmpty()) { "此 API 实现不支持上下文或图片" }
        return stream(profile, messages.single().text)
    }
}

/** 同一配置的请求并发控制；等待许可也可取消，修改限制不创建第二份许可池。 */
class ApiConcurrencyLimiter {
    private val lock = Mutex()
    private val changed = MutableStateFlow(0L)
    private val active = mutableMapOf<String, Int>()
    private val activeServers = mutableMapOf<String, Int>()
    private val servers = mutableMapOf<String, Pair<String, Int>>()
    private val mutableActiveRequests = MutableStateFlow(0)
    val activeRequests = mutableActiveRequests.asStateFlow()
    suspend fun register(id: String, limit: Int, server: String) = lock.withLock {
        require(limit > 0); servers[id] = server to limit; changed.update { it + 1 }
    }
    suspend fun <T> withPermit(id: String, limit: Int, server: String = "", action: suspend () -> T): T {
        require(limit > 0)
        while (true) {
            val revision = changed.value
            val acquired = lock.withLock {
                servers[id] = server to limit
                val serverLimit = servers.values.filter { it.first == server }.minOf { it.second }
                val serverActive = activeServers[server] ?: 0
                if ((active[id] ?: 0) < limit && (server.isEmpty() || serverActive < serverLimit)) {
                    active[id] = (active[id] ?: 0) + 1; activeServers[server] = serverActive + 1; mutableActiveRequests.update { it + 1 }; true
                } else false
            }
            if (acquired) break
            changed.first { it != revision }
        }
        try { return action() } finally {
            withContext(NonCancellable) { lock.withLock {
                active[id] = (active[id] ?: 1) - 1; activeServers[server] = (activeServers[server] ?: 1) - 1
                mutableActiveRequests.update { it - 1 }; changed.update { it + 1 }
            } }
        }
    }
}

/** HTTP/SSE 实现；取消关闭底层 Call，收到内容后的失败不重发请求。 */
class ApiClient(
    private val http: OkHttpClient = OkHttpClient(),
    private val limiter: ApiConcurrencyLimiter = ApiConcurrencyLimiter(),
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val journal: ApiRequestJournal? = null,
) : ApiGateway {
    val activeRequests get() = limiter.activeRequests
    suspend fun configureProfiles(profiles: List<ApiProfile>) {
        profiles.forEach { profile ->
            val endpoint = ApiProtocol.endpoint(profile)
            limiter.register(profile.id, profile.parallelLimit, "${endpoint.scheme}://${endpoint.host}:${endpoint.port}")
        }
    }
    override suspend fun models(profile: ApiProfile): List<String> {
        ApiProtocol.validate(profile, requireModel = false)
        return operation<List<String>>(profile) { session ->
            val found = linkedSetOf<String>()
            val seenTokens = mutableSetOf<String>()
            var token: String? = null
            do {
                currentCoroutineContext().ensureActive()
                val endpoint = ApiProtocol.endpoint(profile, models = true).newBuilder().apply {
                    if (token != null) addQueryParameter("pageToken", token)
                }.build()
                var attempt = 0
                val payload = retry(profile) {
                    val id = beginTrace(profile, endpoint, "GET", "", ++attempt)
                    var code: Int? = null
                    try {
                        session.response(session.request(endpoint)).use { response ->
                            code = response.code; checkResponse(profile, response)
                            limitedBody(response).also { finishTrace(id, ApiRequestOutcome("SUCCESS", ApiLogPayload.sanitize(it), httpCode = code)) }
                        }
                    } catch(e: CancellationException) {
                        finishTrace(id, ApiRequestOutcome("CANCELLED", httpCode = code)); throw e
                    } catch(e: Exception) {
                        finishTrace(id, ApiRequestOutcome("FAILED", error = safeMessage(profile, e), httpCode = code)); throw e
                    }
                }
                val decoded = try { ApiProtocol.models(profile.format, payload) } catch (e: ApiException) { throw e } catch (_: Exception) {
                    throw ApiException("模型列表响应格式错误")
                }
                found += decoded.first
                token = decoded.second
                if (token != null && (!seenTokens.add(token) || seenTokens.size > 20)) throw ApiException("模型列表分页异常")
            } while (!token.isNullOrBlank())
            send(found.toList())
        }.first()
    }

    override fun stream(profile: ApiProfile, prompt: String): Flow<ApiStreamEvent> = stream(profile, listOf(ApiMessage("user", prompt)))
    override fun stream(profile: ApiProfile, messages: List<ApiMessage>): Flow<ApiStreamEvent> = operation(profile) { session ->
        val body = ApiProtocol.requestBody(profile, messages)
        var delivered = false
        var observedEnd = false
        var finishedReason: String? = null
        var responseText = StringBuilder()
        var thinkingText = StringBuilder()
        suspend fun accept(payload: String) {
            for (event in ApiStreamParser.event(profile.format, payload)) {
                when (event) {
                    is ApiStreamEvent.Text -> {
                        delivered = true
                        val log = if(event.thinking) thinkingText else responseText
                        if(log.length < 4_000_000) log.append(event.value.take(4_000_000 - log.length))
                        send(event)
                    }
                    is ApiStreamEvent.Finished -> { observedEnd = true; if (event.reason != null) finishedReason = event.reason }
                    else -> Unit
                }
            }
        }
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            responseText = StringBuilder(); thinkingText = StringBuilder()
            val endpoint = ApiProtocol.endpoint(profile)
            val traceId = beginTrace(profile, endpoint, "POST", body, attempt + 1)
            var code: Int? = null
            try {
                val request = session.request(endpoint).newBuilder()
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                session.response(request).use { response ->
                    code = response.code
                    checkResponse(profile, response)
                    val responseBody = response.body ?: throw ApiException("API 返回空响应")
                    if (responseBody.contentType()?.toString()?.contains("text/event-stream", ignoreCase = true) == true) {
                        val decoder = SseDecoder()
                        responseBody.charStream().buffered().use { reader ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val line = reader.readLine() ?: break
                                decoder.line(line)?.let { accept(it) }
                                // Chat 的结束帧后不等待服务器关闭 keep-alive 连接。
                                if (observedEnd || line == "data: [DONE]") break
                            }
                            decoder.flush()?.let { accept(it) }
                        }
                        if (!observedEnd) throw ApiException("流式响应中断，未收到结束标记")
                    } else {
                        accept(limitedBody(response))
                        observedEnd = true
                    }
                }
                if (!delivered) throw ApiException("模型没有返回文本内容")
                if (finishedReason in setOf("length", "max_tokens", "MAX_TOKENS", "incomplete")) throw ApiException("模型输出被截断，请增加输出 token 上限")
                send(ApiStreamEvent.Finished(finishedReason))
                finishTrace(traceId, ApiRequestOutcome("SUCCESS", responseText.toString(), thinkingText.toString(), httpCode = code))
                break
            } catch (e: CancellationException) {
                finishTrace(traceId, ApiRequestOutcome("CANCELLED", responseText.toString(), thinkingText.toString(), httpCode = code)); throw e
            } catch (e: Exception) {
                finishTrace(traceId, ApiRequestOutcome("FAILED", responseText.toString(), thinkingText.toString(), safeMessage(profile, e), code))
                if (delivered || attempt >= profile.retryCount || !retryable(e)) throw e
                attempt++
                send(ApiStreamEvent.Retrying(attempt))
                pause(retryDelay(e, attempt, profile.timeoutSeconds))
            }
        }
    }

    private suspend fun beginTrace(profile: ApiProfile, endpoint: HttpUrl, method: String, body: String, attempt: Int): String? {
        val sink = journal ?: return null
        val info = ApiRequestInfo(currentCoroutineContext()[ApiTraceContext] ?: ApiTraceContext(stepName = if(method == "GET") "模型列表" else "API 测试"),
            profile.name, profile.model, endpoint.newBuilder().query(null).fragment(null).username("").password("").build().toString(),
            method, profile.format.name, attempt, ApiLogPayload.sanitize(body))
        return try { sink.begin(info).also { currentCoroutineContext()[ApiTraceCapture]?.requestId?.set(it) } }
        catch(e: CancellationException) { throw e } catch(_: Exception) { null }
    }
    private suspend fun finishTrace(id: String?, outcome: ApiRequestOutcome) {
        if(id == null) return
        withContext(NonCancellable) { runCatching { journal?.finish(id, outcome) } }
    }

    private fun <T> operation(profile: ApiProfile, block: suspend ProducerScope<T>.(Session) -> Unit): Flow<T> = callbackFlow {
        val session = Session(profile, http.newBuilder()
            .connectTimeout(profile.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(profile.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .callTimeout(profile.timeoutSeconds.toLong(), TimeUnit.SECONDS).build())
        val worker = launch(Dispatchers.IO) {
            try {
                val endpoint = ApiProtocol.endpoint(profile)
                limiter.withPermit(profile.id, profile.parallelLimit, "${endpoint.scheme}://${endpoint.host}:${endpoint.port}") { block(session) }
                close()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                close(ApiException(safeMessage(profile, e), (e as? ApiException)?.httpCode))
            }
        }
        awaitClose { session.cancel(); worker.cancel() }
    }

    private class Session(private val profile: ApiProfile, private val client: OkHttpClient) {
        private val call = AtomicReference<Call?>()
        private val response = AtomicReference<Response?>()
        fun request(url: HttpUrl): Request = Request.Builder().url(url).header("Accept", "text/event-stream, application/json").apply {
            if (profile.apiKey.isNotBlank()) {
                if (profile.format == ApiFormat.GEMINI) header("x-goog-api-key", profile.apiKey.trim())
                else header("Authorization", "Bearer ${profile.apiKey.trim()}")
            }
        }.build()
        suspend fun response(request: Request): Response {
            currentCoroutineContext().ensureActive()
            val next = client.newCall(request); call.set(next)
            val result = suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { next.cancel() }
                next.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
                    }
                })
            }
            response.set(result)
            currentCoroutineContext().ensureActive()
            return result
        }
        fun cancel() { call.get()?.cancel(); runCatching { response.get()?.close() } }
    }

    private suspend fun <T> retry(profile: ApiProfile, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try { return block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (attempt >= profile.retryCount || !retryable(e)) throw e
                pause(retryDelay(e, ++attempt, profile.timeoutSeconds))
            }
        }
    }

    private fun retryable(e: Exception): Boolean = when (e) {
        is ApiException -> e.httpCode in setOf(408, 429, 500, 502, 503, 504)
        is IOException -> true
        else -> false
    }
    private fun retryDelay(e: Exception, attempt: Int, timeout: Int): Long =
        ((e as? ApiException)?.retryAfterMillis ?: (500L shl (attempt - 1).coerceAtMost(4))).coerceIn(0L, timeout * 1000L)

    private fun checkResponse(profile: ApiProfile, response: Response) {
        if (response.isSuccessful) return
        val body = readBody(response, 4096, truncate = true)
        val detail = runCatching {
            val root = Json.parseToJsonElement(body).jsonObject
            ApiStreamParser.errorMessage(root["error"] ?: root)
        }.getOrDefault(response.message)
        val retryAfter = response.header("Retry-After")?.let { value ->
            value.toLongOrNull()?.times(1000L) ?: runCatching {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()
            }.getOrNull()
        }
        val scrubbed = if (profile.apiKey.isBlank()) detail else detail.replace(profile.apiKey, "[已隐藏]")
        throw ApiException("HTTP ${response.code}：$scrubbed", response.code, retryAfter)
    }

    private fun limitedBody(response: Response): String {
        return readBody(response, 4 * 1024 * 1024, truncate = false)
    }

    private fun readBody(response: Response, limit: Int, truncate: Boolean): String {
        val input = response.body?.byteStream() ?: throw ApiException("API 返回空响应")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        if (output.size() > limit && !truncate) throw ApiException("API 响应过大")
        val bytes = output.toByteArray()
        return String(bytes, 0, minOf(bytes.size, limit), Charsets.UTF_8)
    }

    private fun safeMessage(profile: ApiProfile, error: Exception): String {
        val raw = when (error) {
            is ApiException, is IllegalArgumentException -> error.message ?: "API 请求失败"
            is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "API 请求超时"
            is IOException -> "网络连接失败：${error.message.orEmpty().take(300)}"
            else -> "API 响应格式错误"
        }
        val code = (error as? ApiException)?.httpCode
        val detail = if (code != null) raw.removePrefix("HTTP $code：") else raw
        val scrubbed = if (profile.apiKey.isBlank()) detail else detail.replace(profile.apiKey, "[已隐藏]")
        return if (code != null) "HTTP $code：$scrubbed" else scrubbed
    }
}
