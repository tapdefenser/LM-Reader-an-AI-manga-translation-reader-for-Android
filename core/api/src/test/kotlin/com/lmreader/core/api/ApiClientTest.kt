package com.lmreader.core.api

import com.lmreader.core.model.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*
import org.junit.Test

class ApiClientTest {
    @Test fun journalCapturesEveryAttemptWithMangaContextAndRedactedAttachments() = runBlocking<Unit> {
        val requests = mutableListOf<ApiRequestInfo>(); val outcomes = mutableListOf<ApiRequestOutcome>()
        val journal = object : ApiRequestJournal {
            override suspend fun begin(info: ApiRequestInfo): String { requests += info; return requests.size.toString() }
            override suspend fun finish(id: String, outcome: ApiRequestOutcome) { outcomes += outcome }
        }
        val calls = AtomicInteger()
        val capture = ApiTraceCapture()
        Server { e -> if(calls.incrementAndGet() == 1) e.reply(503, "{}") else e.reply(200, success, "text/event-stream") }.use { server ->
            withContext(ApiTraceContext("manga", "合成漫画", "第7章", "第1页", "流式翻译") + capture) {
                ApiClient(pause = {}, journal = journal).stream(server.profile(), listOf(ApiMessage("user", "translate", listOf(ApiImage("image/jpeg", "AAAA".repeat(100)))))).toList()
            }
            assertEquals(listOf(1, 2), requests.map { it.attempt })
            assertEquals("2", capture.requestId.get())
            assertEquals(listOf("FAILED", "SUCCESS"), outcomes.map { it.status })
            assertEquals("合成漫画", requests.first().context.mangaName)
            assertFalse(requests.first().request.contains("AAAA"))
            assertTrue(requests.first().request.contains("图片附件"))
            assertFalse(requests.toString().contains("private-test-key"))
            assertTrue(outcomes.last().response.contains("你好"))
        }
    }
    @Test fun journalFailureDoesNotFailTheActualRequest() = runBlocking<Unit> {
        val journal = object : ApiRequestJournal {
            override suspend fun begin(info: ApiRequestInfo): String = error("disk failure")
            override suspend fun finish(id: String, outcome: ApiRequestOutcome) = error("disk failure")
        }
        Server { e -> e.reply(200, success, "text/event-stream") }.use { server ->
            assertTrue(ApiClient(journal = journal).stream(server.profile(), "test").toList().any { it is ApiStreamEvent.Finished })
        }
    }
    @Test fun `different profiles share the smallest server budget and count only admitted work`() = runBlocking<Unit> {
        val limiter = ApiConcurrencyLimiter(); val peak = AtomicInteger()
        limiter.register("small", 2, "server"); limiter.register("large", 4, "server")
        coroutineScope { (0..11).map { index -> launch {
            limiter.withPermit(if(index % 2 == 0) "small" else "large", if(index % 2 == 0) 2 else 4, "server") {
                peak.updateAndGet { maxOf(it, limiter.activeRequests.value) }; delay(10)
            }
        } }.joinAll() }
        assertEquals(2, peak.get()); assertEquals(0, limiter.activeRequests.value)
    }
    @Test fun `truncated output is rejected rather than published as a completed reply`() = runBlocking<Unit> {
        Server { e -> e.reply(200, "{\"choices\":[{\"message\":{\"content\":\"[partial\"},\"finish_reason\":\"length\"}]}") }.use { server ->
            assertFailsWith<ApiException> { ApiClient().stream(server.profile(), "hello").toList() }
        }
    }
    private class Server(handler: (HttpExchange) -> Unit) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@Server.executor
            createContext("/") { exchange -> try { handler(exchange) } catch (_: Throwable) { exchange.close() } }
            start()
        }
        fun profile() = ApiProfile("test", ApiProfileKind.LLM, url = "http://127.0.0.1:${server.address.port}/v1", model = "test", apiKey = "private-test-key", retryCount = 2)
        override fun close() { server.stop(0); executor.shutdownNow() }
    }
    private fun HttpExchange.reply(code: Int, body: String, type: String = "application/json") {
        responseHeaders.add("Content-Type", type)
        val bytes = body.toByteArray(); sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }; close()
    }
    private val success = "data: {\"choices\":[{\"delta\":{\"content\":\"你好😀\"}}]}\r\n\r\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"

    @Test fun `models request uses auth and streaming emits real increments`() = runBlocking {
        Server { e ->
            assertEquals("Bearer private-test-key", e.requestHeaders.getFirst("Authorization"))
            if (e.requestURI.path.endsWith("models")) e.reply(200, "{\"data\":[{\"id\":\"one\"},{\"id\":\"two\"}]}")
            else { assertTrue(e.requestBody.bufferedReader().readText().contains("\"stream\":true")); e.reply(200, success, "text/event-stream") }
        }.use { s ->
            val client = ApiClient(pause = {})
            assertEquals(listOf("one", "two"), client.models(s.profile()))
            assertEquals(listOf(ApiStreamEvent.Text("你好😀"), ApiStreamEvent.Finished("stop")), client.stream(s.profile(), "hello").toList())
        }
    }
    @Test fun `rate limits retry but auth errors do not and credentials are redacted`() = runBlocking {
        val calls = AtomicInteger()
        val pauses = mutableListOf<Long>()
        Server { e -> if (calls.incrementAndGet() == 1) { e.responseHeaders.add("Retry-After", "2"); e.reply(429, "{\"error\":{\"message\":\"wait\"}}") } else e.reply(200, success, "text/event-stream") }.use { s ->
            val events = ApiClient(pause = { pauses += it }).stream(s.profile(), "hi").toList()
            assertEquals(2, calls.get()); assertEquals(listOf(2000L), pauses)
            assertEquals(ApiStreamEvent.Retrying(1), events.first())
        }
        calls.set(0)
        Server { e -> calls.incrementAndGet(); e.reply(401, "{\"error\":{\"message\":\"invalid private-test-key\"}}") }.use { s ->
            val error = assertFailsWith<ApiException> { ApiClient(pause = {}).stream(s.profile(), "hi").toList() }
            assertEquals(1, calls.get()); assertEquals(401, error.httpCode)
            assertFalse(error.message.orEmpty().contains("private-test-key"))
        }
    }
    @Test fun `retry count means attempts after first and zero means once`() = runBlocking {
        val calls = AtomicInteger()
        Server { e -> calls.incrementAndGet(); e.reply(503, "{}") }.use { s ->
            assertFailsWith<ApiException> { ApiClient(pause = {}).models(s.profile()) }
            assertEquals(3, calls.get())
            calls.set(0)
            assertFailsWith<ApiException> { ApiClient(pause = {}).models(s.profile().copy(retryCount = 0)) }
            assertEquals(1, calls.get())
        }
    }
    @Test fun `partial streams are not retried`() = runBlocking {
        val calls = AtomicInteger()
        Server { e -> calls.incrementAndGet(); e.reply(200, "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n", "text/event-stream") }.use { s ->
            val received = mutableListOf<ApiStreamEvent>()
            assertFailsWith<ApiException> { ApiClient(pause = {}).stream(s.profile(), "hi").collect { received += it } }
            assertEquals(listOf<ApiStreamEvent>(ApiStreamEvent.Text("partial")), received)
            assertEquals(1, calls.get())
        }
    }
    @Test fun `cancellation releases the actual request and permit`() = runBlocking {
        val calls = AtomicInteger()
        Server { e ->
            calls.incrementAndGet()
            e.responseHeaders.add("Content-Type", "text/event-stream"); e.sendResponseHeaders(200, 0)
            e.responseBody.write("data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n".toByteArray()); e.responseBody.flush()
            Thread.sleep(30000)
        }.use { s ->
            val client = ApiClient(pause = {})
            val profile = s.profile().copy(parallelLimit = 1)
            val ready = CompletableDeferred<Unit>()
            val first = launch { client.stream(profile, "first").collect { if (it is ApiStreamEvent.Text) ready.complete(Unit) } }
            withTimeout(5000) { ready.await() }
            val secondReady = CompletableDeferred<Unit>()
            val second = launch { client.stream(profile, "second").collect { if (it is ApiStreamEvent.Text) secondReady.complete(Unit) } }
            delay(100); assertEquals(1, calls.get())
            withTimeout(2000) { first.cancelAndJoin() }
            withTimeout(5000) { secondReady.await() }
            assertEquals(2, calls.get()); second.cancelAndJoin()
        }
    }
    @Test fun `non-streaming fallback remains readable`() = runBlocking {
        Server { e -> e.reply(200, "{\"choices\":[{\"message\":{\"content\":\"fallback\"},\"finish_reason\":\"stop\"}]}") }.use { s ->
            assertEquals(listOf(ApiStreamEvent.Text("fallback"), ApiStreamEvent.Finished("stop")), ApiClient().stream(s.profile(), "hi").toList())
        }
    }
}
