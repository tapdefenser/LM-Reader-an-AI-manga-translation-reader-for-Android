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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody

/** 可替换的调用边界，供工作流与设置界面共享。 */
interface ApiGateway {
    suspend fun models(profile: ApiProfile): List<String>
    fun stream(profile: ApiProfile, prompt: String): Flow<ApiStreamEvent>
    fun stream(profile: ApiProfile, messages: List<ApiMessage>): Flow<ApiStreamEvent> {
        require(messages.size == 1 && messages.single().role == "user" && messages.single().images.isEmpty() && messages.single().audios.isEmpty()) { "此 API 实现不支持上下文、图片或音频" }
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
    private data class Ticket(val order: Long,val id:String,val limit:Int,val server:String,val priority:Int,val at:Long = System.currentTimeMillis())
    private val waiting = mutableListOf<Ticket>()
    private var sequence = 0L
    private val mutableActiveRequests = MutableStateFlow(0)
    val activeRequests = mutableActiveRequests.asStateFlow()
    suspend fun register(id: String, limit: Int, server: String) = lock.withLock {
        require(limit > 0); servers[id] = server to limit; changed.update { it + 1 }
    }
    suspend fun configure(profiles: Map<String, Pair<String, Int>>) = lock.withLock {
        require(profiles.values.all { it.second > 0 })
        servers.clear(); servers.putAll(profiles); changed.update { it + 1 }
    }
    suspend fun <T> withPermit(id: String, limit: Int, server: String = "", action: suspend () -> T): T {
        require(limit > 0)
        val priority=currentCoroutineContext()[com.lmreader.core.model.TaskContext]?.priority
            ?: currentCoroutineContext()[com.lmreader.core.model.InferencePriority]?.rank ?: 0
        val ticket=lock.withLock { servers.putIfAbsent(id,server to limit)
            Ticket(sequence++,id,limit,server,priority).also {waiting+=it;changed.update {v->v+1}} }
        var acquired=false
        fun eligible(t:Ticket):Boolean {
            val profileLimit=minOf(t.limit,servers[t.id]?.second ?: t.limit)
            val serverLimit=servers.values.filter {it.first==t.server}.minOfOrNull {it.second} ?: t.limit
            return (active[t.id] ?: 0)<profileLimit && (t.server.isEmpty() || (activeServers[t.server] ?: 0)<serverLimit)
        }
        val context=currentCoroutineContext()[com.lmreader.core.model.TaskContext]
        try {
            context?.onWaiting?.invoke("API:$id")
            while(!acquired) {
                currentCoroutineContext().ensureActive()
                val before=changed.value
                acquired=lock.withLock {
                    val next=waiting.filter(::eligible).maxWithOrNull(compareBy<Ticket> {
                        it.priority + ((System.currentTimeMillis() - it.at).coerceAtLeast(0) / 1000).coerceAtMost((95 - it.priority).coerceAtLeast(0).toLong()).toInt()
                    }.thenBy {-it.order})
                    if(next===ticket) { waiting.remove(ticket); active[id]=(active[id] ?: 0)+1
                        activeServers[server]=(activeServers[server] ?: 0)+1
                        mutableActiveRequests.update {it+1};changed.update {it+1};true
                    } else false
                }
                if(!acquired) withTimeoutOrNull(1000) { changed.first {it!=before} }
            }
            context?.onWaiting?.invoke(null)
            return action()
        } finally {withContext(NonCancellable) {lock.withLock {
            waiting.remove(ticket)
            if(acquired) {active[id]=(active[id] ?: 1)-1;activeServers[server]=(activeServers[server] ?: 1)-1;mutableActiveRequests.update {it-1}}
            changed.update {it+1}
        }}}
    }
}
/** HTTP/SSE 实现；取消关闭底层 Call。有长度限制时先验证响应，再交付内容以安全重试。 */
class ApiClient(
    private val http: OkHttpClient = OkHttpClient(),
    private val limiter: ApiConcurrencyLimiter = ApiConcurrencyLimiter(),
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val journal: ApiRequestJournal? = null,
) : ApiGateway {
    private val managedRequests = ConcurrentHashMap<String, Pair<com.lmreader.core.model.TaskContext, String>>()
    val activeRequests get() = limiter.activeRequests
    /** Explicit audio/other protocol adapters share the same provider admission as text requests. */
    suspend fun <T> withExternalPermit(profile: ApiProfile, endpoint: String, action: suspend () -> T): T {
        val url = endpoint.toHttpUrl()
        return limiter.withPermit(profile.id, profile.parallelLimit, "${url.scheme}://${url.host}:${url.port}", action)
    }
    suspend fun configureProfiles(profiles: List<ApiProfile>) {
        limiter.configure(profiles.associate { profile ->
            val endpoint = ApiProtocol.endpoint(profile)
            profile.id to ("${endpoint.scheme}://${endpoint.host}:${endpoint.port}" to profile.parallelLimit)
        })
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
                        session.consume(session.request(endpoint), id) { response ->
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
    override fun stream(profile: ApiProfile, messages: List<ApiMessage>): Flow<ApiStreamEvent> = stream(profile, messages, null)

    /** The limit counts Unicode code points in both body and thinking text, including JSON syntax. */
    fun stream(profile: ApiProfile, messages: List<ApiMessage>, maxOutputLength: Int?): Flow<ApiStreamEvent> = operation(profile) { session ->
        require(maxOutputLength == null || maxOutputLength > 0) { "最大输出长度必须是正整数" }
        val body = ApiProtocol.requestBody(profile, messages)
        var delivered = false
        var receivedText = false
        var observedEnd = false
        var finishedReason: String? = null
        var responseText = StringBuilder()
        var thinkingText = StringBuilder()
        val pending = maxOutputLength?.let { mutableListOf<ApiStreamEvent.Text>() }
        var pendingLength = 0
        var outputLength = 0L
        val highSurrogate = BooleanArray(2)
        suspend fun accept(payload: String) {
            for (event in ApiStreamParser.event(profile.format, payload)) {
                when (event) {
                    is ApiStreamEvent.Text -> {
                        receivedText = true
                        val log = if(event.thinking) thinkingText else responseText
                        if(log.length < 4_000_000) log.append(event.value.take(4_000_000 - log.length))
                        if (maxOutputLength != null) {
                            val value = event.value
                            val part = if (event.thinking) 1 else 0
                            if (value.isNotEmpty()) {
                                outputLength += value.codePointCount(0, value.length) -
                                    if (highSurrogate[part] && value.first().isLowSurrogate()) 1 else 0
                                highSurrogate[part] = value.last().isHighSurrogate()
                            }
                            if (outputLength > maxOutputLength) {
                                // Cancel before closing the response so HTTP/2 also resets the active stream.
                                session.cancel()
                                throw OutputLimitExceeded(maxOutputLength)
                            }
                            // Buffering must retain the workflow's existing response size protection.
                            if (pendingLength.toLong() + value.length > 4_000_000) {
                                session.cancel()
                                throw ApiException("API 输出超过 4 MB")
                            }
                            pendingLength += value.length
                            pending!!.add(event)
                        } else {
                            delivered = true
                            send(event)
                        }
                    }
                    is ApiStreamEvent.Finished -> { observedEnd = true; if (event.reason != null) finishedReason = event.reason }
                    else -> Unit
                }
            }
        }
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            delivered = false; receivedText = false; outputLength = 0; pendingLength = 0; highSurrogate.fill(false); pending?.clear()
            responseText = StringBuilder(); thinkingText = StringBuilder(); observedEnd = false; finishedReason = null
            val endpoint = ApiProtocol.endpoint(profile)
            val traceId = beginTrace(profile, endpoint, "POST", body, attempt + 1)
            var code: Int? = null
            try {
                val request = session.request(endpoint).newBuilder()
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                session.consume(request, traceId) { response ->
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
                if (!receivedText) throw ApiException("模型没有返回文本内容")
                if (finishedReason in setOf("length", "max_tokens", "MAX_TOKENS", "incomplete")) throw ApiException("模型输出被截断，请增加输出 token 上限")
                pending?.forEach { delivered = true; send(it) }
                send(ApiStreamEvent.Finished(finishedReason))
                finishTrace(traceId, ApiRequestOutcome("SUCCESS", responseText.toString(), thinkingText.toString(), httpCode = code))
                break
            } catch (e: CancellationException) {
                finishTrace(traceId, ApiRequestOutcome("CANCELLED", responseText.toString(), thinkingText.toString(), httpCode = code)); throw e
            } catch (e: Exception) {
                finishTrace(traceId, ApiRequestOutcome("FAILED", responseText.toString(), thinkingText.toString(), safeMessage(profile, e), code))
                if(currentCoroutineContext()[com.lmreader.core.model.TaskContext] != null && code !in setOf(400, 401, 403, 404, 405, 413, 415, 422, 429))
                    throw ApiException("请求结果未知，可能已计费；已停止自动重试，请核对提供方。${safeMessage(profile, e)}", code)
                if (delivered || attempt >= profile.retryCount || !retryable(e)) throw e
                attempt++
                send(ApiStreamEvent.Retrying(attempt))
                pause(retryDelay(e, attempt, profile.timeoutSeconds))
            }
        }
    }

    private suspend fun beginTrace(profile: ApiProfile, endpoint: HttpUrl, method: String, body: String, attempt: Int): String? {
        val sink = journal
        val info = ApiRequestInfo(currentCoroutineContext()[ApiTraceContext] ?: ApiTraceContext(stepName = if(method == "GET") "模型列表" else "API 测试"),
            profile.name, profile.model, endpoint.newBuilder().query(null).fragment(null).username("").password("").build().toString(),
            method, profile.format.name, attempt, ApiLogPayload.sanitize(body))
        val id = try { sink?.begin(info) ?: java.util.UUID.randomUUID().toString() }
            catch(e: CancellationException) { throw e } catch(_: Exception) { java.util.UUID.randomUUID().toString() }
        currentCoroutineContext()[ApiTraceCapture]?.requestId?.set(id)
        if(method == "POST") currentCoroutineContext()[com.lmreader.core.model.TaskContext]?.let { task ->
            managedRequests[id] = task to info.context.publicationKey
            try { task.onRemoteRequest(id, info.context.publicationKey, "PREPARED") }
            catch(error: Throwable) { managedRequests.remove(id); throw error }
        }
        return id
    }
    private suspend fun finishTrace(id: String?, outcome: ApiRequestOutcome) {
        if(id == null) return
        withContext(NonCancellable) {
            runCatching { journal?.finish(id, outcome) }
            managedRequests.remove(id)?.let { (task, scope) -> task.onRemoteRequest(id, scope, when {
                    outcome.status == "SUCCESS" -> "RECEIVED"
                    outcome.httpCode in setOf(400, 401, 403, 404, 405, 413, 415, 422, 429) -> "REJECTED"
                    else -> "UNKNOWN"
                }) }
        }
    }

    private fun <T> operation(profile: ApiProfile, block: suspend ProducerScope<T>.(Session) -> Unit): Flow<T> = callbackFlow {
        val session = Session(profile, http.newBuilder()
            .connectTimeout(profile.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(profile.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .callTimeout(profile.timeoutSeconds.toLong(), TimeUnit.SECONDS).build(), limiter)
        val worker = launch(Dispatchers.IO) {
            try {
                block(session)
                close()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                close(ApiException(safeMessage(profile, e), (e as? ApiException)?.httpCode))
            }
        }
        awaitClose { session.cancel(); worker.cancel() }
    }

    private class Session(private val profile: ApiProfile, private val client: OkHttpClient, private val limiter: ApiConcurrencyLimiter) {
        private val call = AtomicReference<Call?>()
        private val response = AtomicReference<Response?>()
        fun request(url: HttpUrl): Request = Request.Builder().url(url).header("Accept", "text/event-stream, application/json").apply {
            if (profile.apiKey.isNotBlank()) {
                if (profile.format == ApiFormat.GEMINI) header("x-goog-api-key", profile.apiKey.trim())
                else header("Authorization", "Bearer ${profile.apiKey.trim()}")
            }
        }.build()
        suspend fun <T> consume(request: Request, traceId: String? = null, read: suspend (Response) -> T): T {
            val endpoint = request.url
            return limiter.withPermit(profile.id, profile.parallelLimit, "${endpoint.scheme}://${endpoint.host}:${endpoint.port}") {
                if(request.method == "POST" && traceId != null) currentCoroutineContext()[com.lmreader.core.model.TaskContext]?.onRemoteRequest?.invoke(traceId,
                    currentCoroutineContext()[ApiTraceContext]?.publicationKey ?: "*", "SENT")
                response(request).use { read(it) }
            }
        }
        private suspend fun response(request: Request): Response {
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
            is ApiException, is IllegalArgumentException, is OutputLimitExceeded -> error.message ?: "API 请求失败"
            is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "API 请求超时"
            is IOException -> "网络连接失败：${error.message.orEmpty().take(300)}"
            else -> "API 响应格式错误"
        }
        val code = (error as? ApiException)?.httpCode
        val detail = if (code != null) raw.removePrefix("HTTP $code：") else raw
        val scrubbed = if (profile.apiKey.isBlank()) detail else detail.replace(profile.apiKey, "[已隐藏]")
        return if (code != null) "HTTP $code：$scrubbed" else scrubbed
    }

    private class OutputLimitExceeded(limit: Int) : IOException("API 输出超过最大长度 $limit 字符，已中止本次请求")
}
