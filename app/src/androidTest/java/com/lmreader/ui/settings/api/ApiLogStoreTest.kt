package com.lmreader.ui.settings.api

import androidx.test.platform.app.InstrumentationRegistry
import com.lmreader.core.api.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ApiLogStoreTest {
    @Test fun outputFailurePreservesResponseAndSurvivesLateTransportFinish() = runBlocking {
        val cache=InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        val root=File(cache,"api-log-fixture-${UUID.randomUUID()}")
        try {
            val store=ApiLogStore(root)
            val id=store.begin(ApiRequestInfo(ApiTraceContext("fixture","Fixture",stepName="单页重译 · API 请求"),
                "fixture","model","http://127.0.0.1/v1/chat/completions","POST","CHAT",1,"{}"))
            store.recordOutputFailure(id,"Invalid JSON; image count: 1")
            store.finish(id,ApiRequestOutcome("SUCCESS","Please upload an image.",httpCode=200))
            val restored=checkNotNull(ApiLogStore(root).detail(id))
            assertEquals("FAILED",restored.outcome.status)
            assertEquals(200,restored.outcome.httpCode)
            assertEquals("Please upload an image.",restored.outcome.response)
            assertTrue(restored.outcome.error.contains("Invalid JSON"))
        } finally {
            if(root.canonicalFile.parentFile==cache && root.name.startsWith("api-log-fixture-")) root.deleteRecursively()
        }
    }
    @Test fun privateJournalRestoresBodiesAndMarksUnfinishedRequestsInterrupted() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        val root = File(cache, "api-log-fixture-${UUID.randomUUID()}")
        try {
            val store = ApiLogStore(root)
            val info = ApiRequestInfo(ApiTraceContext("manga-fixture", "合成漫画", "章", "页", "步骤"), "fixture", "model", "http://127.0.0.1/v1/chat/completions", "POST", "CHAT_COMPLETIONS", 1, "{\"messages\":[]}")
            val done = store.begin(info); store.finish(done, ApiRequestOutcome("SUCCESS", "[{\"translation\":\"译文\"}]", "thinking", httpCode = 200))
            val pending = store.begin(info.copy(attempt = 2))
            assertTrue(store.records.value.all { it.info.request.isEmpty() && it.outcome.response.isEmpty() })
            val restored = ApiLogStore(root)
            assertEquals("INTERRUPTED", restored.detail(pending)!!.outcome.status)
            assertEquals("[{\"translation\":\"译文\"}]", restored.detail(done)!!.outcome.response)
            assertEquals(2, restored.records.value.groupBy { it.info.context.mangaId }.getValue("manga-fixture").size)
        } finally {
            if(root.canonicalFile.parentFile == cache && root.name.startsWith("api-log-fixture-")) root.deleteRecursively()
        }
    }
}
