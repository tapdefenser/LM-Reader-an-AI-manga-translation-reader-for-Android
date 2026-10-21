package com.lmreader.core.api

import com.lmreader.core.model.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Test
import kotlin.test.*

class ApiOutputLimitTest {
    @Test fun `overflow cancels the live call before retry and never delivers the failed prefix`() = runBlocking {
        val calls = AtomicInteger()
        val cancelled = CopyOnWriteArrayList<Call>()
        val release = CountDownLatch(1)
        val requestBodies = CopyOnWriteArrayList<String>()
        Server { exchange ->
            requestBodies += exchange.requestBody.bufferedReader().readText()
            if (calls.incrementAndGet() == 1) {
                exchange.startStream()
                exchange.write(chat("old") + chat("too long"))
                release.await(10, TimeUnit.SECONDS)
            } else exchange.reply(chat("new") + done)
        }.use { server ->
            lateinit var client: ApiClient
            client = ApiClient(OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled += call }
            }).build(), pause = {
                assertEquals(1, cancelled.size)
                assertTrue(cancelled.single().isCanceled())
                assertEquals(0, client.activeRequests.value)
            })
            try {
                val events = withTimeout(3000) { client.stream(server.profile(), messages, 3).toList() }
                assertEquals(listOf(ApiStreamEvent.Retrying(1), ApiStreamEvent.Text("new"), ApiStreamEvent.Finished()), events)
                assertEquals(2, calls.get())
                assertEquals(2, requestBodies.size)
                assertEquals(requestBodies.first(), requestBodies.last())
                assertEquals(0, client.activeRequests.value)
            } finally { release.countDown() }
        }
    }

    @Test fun `every format counts thinking and body and accepts the exact Unicode character limit`() = runBlocking {
        for (format in ApiFormat.entries) {
            val chunks = when (format) {
                ApiFormat.CHAT -> chat("想", thinking = true) + chat("你😀") + done
                ApiFormat.RESPONSES -> sse(buildJsonObject { put("type", "response.reasoning_text.delta"); put("delta", "想") }) +
                    sse(buildJsonObject { put("type", "response.output_text.delta"); put("delta", "你😀") }) +
                    sse(buildJsonObject { put("type", "response.completed") })
                ApiFormat.GEMINI -> sse(Json.parseToJsonElement("""{"candidates":[{"content":{"parts":[{"text":"想","thought":true},{"text":"你😀"}]},"finishReason":"STOP"}]}"""))
            }
            val calls = AtomicInteger()
            Server { e -> calls.incrementAndGet(); e.reply(chunks) }.use { server ->
                val client = ApiClient(pause = {})
                val exact = client.stream(server.profile().copy(format = format), messages, 3).toList()
                assertEquals(listOf(ApiStreamEvent.Text("想", true), ApiStreamEvent.Text("你😀")), exact.filterIsInstance<ApiStreamEvent.Text>())
                assertEquals(1, calls.get())
                val failed = mutableListOf<ApiStreamEvent>()
                val error = assertFailsWith<ApiException> {
                    client.stream(server.profile().copy(format = format, retryCount = 0), messages, 2).collect { failed += it }
                }
                assertContains(error.message.orEmpty(), "2 字符")
                assertTrue(failed.isEmpty())
                assertEquals(2, calls.get())
            }
        }
    }

    @Test fun `split surrogate pairs count as one character across chunks`() = runBlocking {
        Server { e -> e.reply(chat("想", thinking = true) +
            "data: {\"choices\":[{\"delta\":{\"content\":\"\\ud83d\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"\\ude00\"}}]}\n\n" + done) }.use { server ->
            val events = ApiClient(pause = {}).stream(server.profile(), messages, 2).toList()
            assertEquals("😀", events.filterIsInstance<ApiStreamEvent.Text>().filterNot { it.thinking }.joinToString("") { it.value })
            assertTrue(events.last() is ApiStreamEvent.Finished)
            assertFalse(events.any { it is ApiStreamEvent.Retrying })
        }
    }

    @Test fun `retry budget is finite and journals each discarded attempt`() = runBlocking {
        val calls = AtomicInteger()
        val infos = mutableListOf<ApiRequestInfo>()
        val outcomes = mutableListOf<ApiRequestOutcome>()
        val journal = object : ApiRequestJournal {
            override suspend fun begin(info: ApiRequestInfo): String { infos += info; return infos.size.toString() }
            override suspend fun finish(id: String, outcome: ApiRequestOutcome) { outcomes += outcome }
        }
        Server { e -> calls.incrementAndGet(); e.reply(chat("a") + chat("b") + done) }.use { server ->
            val events = mutableListOf<ApiStreamEvent>()
            val error = assertFailsWith<ApiException> {
                ApiClient(pause = {}, journal = journal).stream(server.profile(), messages, 1).collect { events += it }
            }
            assertContains(error.message.orEmpty(), "已中止本次请求")
            assertEquals(3, calls.get())
            assertEquals(listOf<ApiStreamEvent>(ApiStreamEvent.Retrying(1), ApiStreamEvent.Retrying(2)), events)
            assertEquals(listOf(1, 2, 3), infos.map { it.attempt })
            assertEquals(listOf("FAILED", "FAILED", "FAILED"), outcomes.map { it.status })
            assertTrue(outcomes.all { it.error.orEmpty().contains("最大长度 1 字符") })
        }
    }

    @Test fun `JSON fallback retries without exposing an oversized response`() = runBlocking {
        val calls = AtomicInteger()
        Server { e ->
            val text = if(calls.incrementAndGet() == 1) "oversized" else "ok"
            e.reply("""{"choices":[{"message":{"content":"$text"},"finish_reason":"stop"}]}""", "application/json")
        }.use { server ->
            val events = ApiClient(pause = {}).stream(server.profile(), messages, 2).toList()
            assertEquals(listOf(ApiStreamEvent.Retrying(1), ApiStreamEvent.Text("ok"), ApiStreamEvent.Finished("stop")), events)
            assertEquals(2, calls.get())
        }
    }

    @Test fun `cancelling during overflow backoff starts no additional request`() = runBlocking {
        val calls = AtomicInteger()
        val backoff = CompletableDeferred<Unit>()
        Server { e -> calls.incrementAndGet(); e.reply(chat("too long") + done) }.use { server ->
            val client = ApiClient(pause = { backoff.complete(Unit); awaitCancellation() })
            val job = launch { client.stream(server.profile(), messages, 1).collect() }
            withTimeout(3000) { backoff.await() }
            assertEquals(0, client.activeRequests.value)
            withTimeout(3000) { job.cancelAndJoin() }
            assertEquals(1, calls.get())
        }
    }

    @Test fun `invalid limits fail before an HTTP request`() = runBlocking {
        val calls = AtomicInteger()
        Server { e -> calls.incrementAndGet(); e.reply(done) }.use { server ->
            for (limit in listOf(0, -1)) assertFailsWith<ApiException> {
                ApiClient(pause = {}).stream(server.profile(), messages, limit).toList()
            }
            assertEquals(0, calls.get())
        }
    }

    @Test fun `a large configured limit preserves the response buffer size protection`() = runBlocking {
        val calls = AtomicInteger()
        Server { e -> calls.incrementAndGet(); e.reply(chat("x".repeat(4_000_001)) + done) }.use { server ->
            val events = mutableListOf<ApiStreamEvent>()
            val error = assertFailsWith<ApiException> {
                ApiClient(pause = {}).stream(server.profile(), messages, Int.MAX_VALUE).collect { events += it }
            }
            assertEquals("API 输出超过 4 MB", error.message)
            assertEquals(1, calls.get())
            assertTrue(events.isEmpty())
        }
    }

    private class Server(handler: (HttpExchange) -> Unit) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@Server.executor
            createContext("/") { exchange -> try { handler(exchange) } finally { exchange.close() } }
            start()
        }
        fun profile() = ApiProfile("limit", ApiProfileKind.LLM, url = "http://127.0.0.1:${server.address.port}/v1", model = "test", retryCount = 2)
        override fun close() { server.stop(0); executor.shutdownNow() }
    }

    companion object {
        private val messages = listOf(ApiMessage("user", "translate"))
        private const val done = "data: [DONE]\n\n"
        private fun sse(json: JsonElement) = "data: $json\n\n"
        private fun chat(text: String, thinking: Boolean = false) = sse(buildJsonObject {
            putJsonArray("choices") { addJsonObject { putJsonObject("delta") { put(if(thinking) "reasoning_content" else "content", text) } } }
        })
        private fun HttpExchange.startStream() {
            responseHeaders.add("Content-Type", "text/event-stream")
            sendResponseHeaders(200, 0)
        }
        private fun HttpExchange.write(body: String) { responseBody.write(body.toByteArray()); responseBody.flush() }
        private fun HttpExchange.reply(body: String, type: String = "text/event-stream") {
            responseHeaders.add("Content-Type", type)
            val bytes = body.toByteArray()
            sendResponseHeaders(200, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }
}
