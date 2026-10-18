package com.lmreader.core.translation

import com.lmreader.core.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Collections

class OpusCatalogDiscoveryTest {
    @Test fun discoversNewDirectionsWithoutPayloadDownloadsAndKeepsCacheOnFailure() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        var extra = false
        var failRequests = false
        val server = MockWebServer()
        fun info(oversized: Boolean = false) = buildJsonObject {
            put("sha", "a".repeat(40))
            putJsonArray("siblings") {
                listOf("onnx/encoder_model_quantized.onnx", "onnx/decoder_model_merged_quantized.onnx", "source.spm", "target.spm", "vocab.json", "config.json").forEach { path ->
                    add(buildJsonObject {
                        put("rfilename", path)
                        put("size", if(path.endsWith(".onnx")) if(oversized) 200_000_000 else 50_000_000 else 1024)
                        if(path.endsWith(".onnx")) putJsonObject("lfs") { put("sha256", "b".repeat(64)) }
                        else put("blobId", "c".repeat(40))
                    })
                }
            }
        }.toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                requests += path
                if(failRequests) return MockResponse().setResponseCode(503)
                val body = when {
                    path == "/api/models" -> buildJsonArray {
                        (listOf("fr-de", "en-fr", "mul-en") + if(extra) listOf("de-es") else emptyList()).forEach {
                            add(buildJsonObject { put("id", "Xenova/opus-mt-$it") })
                        }
                    }.toString()
                    path.startsWith("/api/models/Helsinki-NLP/") -> """{"cardData":{"license":"apache-2.0"}}"""
                    path.startsWith("/api/models/Xenova/") -> info(oversized = path.endsWith("opus-mt-en-fr"))
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
        val cache = Files.createTempDirectory("opus-catalog-test").toFile()
        try {
            val host = server.url("/").toString()
            val remote = RemoteTranslationCatalog(endpoints = TranslationCatalogEndpoints(huggingFace = host, hfMirror = host))
            val settings = TranslationSourceSettings(source = TranslationDownloadSource.HF_MIRROR, engine = TranslationModelEngine.OPUS_MT)
            val store = TranslationCatalogStore(TranslationModelCatalog.fromPacks(emptyList()), cache, remote::fetch)
            store.refresh(settings)
            assertEquals(listOf("opus-fr-de"), store.state.value.catalog.packs.map { it.id })
            assertEquals("OPUS_MT:HF_MIRROR", store.state.value.catalog.sourceKey)
            assertTrue(store.state.value.catalog.packs.flatMap {it.files}.all { it.url!!.startsWith(host) })
            extra = true
            store.refresh(settings)
            assertEquals(setOf("opus-fr-de", "opus-de-es"), store.state.value.catalog.packs.map {it.id}.toSet())
            val usable = store.state.value.catalog
            failRequests = true
            try { store.refresh(settings); fail("HTTP errors must be reported") } catch(_: IllegalArgumentException) { }
            assertSame(usable, store.state.value.catalog)
            assertNotNull(store.state.value.error)
            assertTrue(requests.all {it.startsWith("/api/models")})
            assertFalse(requests.any {"mul-en" in it})
        } finally { server.shutdown(); cache.deleteRecursively() }
    }
}
