package com.lmreader.core.vision

import androidx.test.core.app.ApplicationProvider
import com.lmreader.core.model.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class VisionPreprocessingCacheTest {
    private val context=ApplicationProvider.getApplicationContext<android.content.Context>()
    private val root=File(context.cacheDir,"preprocessing-test-${UUID.randomUUID()}")
    private val region=SegRegion("p:free",RegionKind.FREE_TEXT,PixelRect(1f,2f,30f,40f),.8f)
    private val seg=SegResult("p",100,200,listOf(region),12,"fixture",listOf(DetectedTextLine(PixelRect(2f,3f,20f,15f),.9f)))
    @After fun cleanup() { check(root.canonicalFile.parentFile==context.cacheDir.canonicalFile);root.deleteRecursively() }
    @Test fun uncommittedSegAndFreeTextOcrAreCompleteAndPinnedAcrossRestart() = runBlocking {
        var cache=VisionPreprocessingCache(root) { 1 }
        cache.markPendingPage("p");cache.retainPage("p")
        cache.segment("p","hash",100,200,.35f,.35f) { seg }
        val ocr=LocalOcrResult("p",100,200,LocalOcrLanguage.ENGLISH,
            listOf(OcrLine("line",PixelRect(2f,3f,20f,15f),"outside bubble",.9f)),10)
        cache.recognize("p","hash",LocalOcrLanguage.ENGLISH,region,seg.regions,seg.textLines) { ocr }
        cache.releasePage("p")
        assertTrue(cache.bytes.value>1)
        cache=VisionPreprocessingCache(root) { 1 }
        val hit=cache.segment("p","hash",100,200,.35f,.35f) { error("SEG should be reused") }
        assertEquals(seg.regions,hit.regions);assertEquals(seg.textLines,hit.textLines)
        val text=cache.recognize("p","hash",LocalOcrLanguage.ENGLISH,region,seg.regions,seg.textLines) { error("OCR should be reused") }
        assertEquals(ocr.lines,text.lines);assertEquals(2,cache.hits.value)
        cache.commitPage("p");assertTrue(cache.bytes.value<=1)
    }
    @Test fun concurrentIdenticalInferenceIsDeduplicatedAndChangedInputsInvalidateIt() = runBlocking {
        val cache=VisionPreprocessingCache(root) { 1_000_000 }
        val calls=AtomicInteger()
        coroutineScope { (1..3).map { async { cache.segment("p","hash",100,200,.35f,.35f) {
            calls.incrementAndGet();delay(30);seg
        } } }.awaitAll() }
        assertEquals(1,calls.get())
        cache.segment("p","hash",100,200,.4f,.35f) { calls.incrementAndGet();seg }
        cache.segment("p","new-hash",100,200,.4f,.35f) { calls.incrementAndGet();seg }
        assertEquals(3,calls.get())
    }
    @Test fun trimmingAnotherParallelPageNeverDropsAnUnwrittenPendingOwnersPin() = runBlocking {
        val cache=VisionPreprocessingCache(root) { 1 }
        cache.markPendingPage("p")
        val entered=CompletableDeferred<Unit>()
        val finish=CompletableDeferred<Unit>()
        val anotherWritten=CompletableDeferred<Unit>()
        val pending=async { cache.segment("p","hash",100,200,.35f,.35f) {
            entered.complete(Unit);finish.await();seg
        } }
        entered.await()
        val others=(1..32).map { index -> async(Dispatchers.Default) {
            val id="parallel-$index"
            cache.markPendingPage(id)
            cache.segment(id,"hash-$index",100,200,.35f,.35f) { seg.copy(imageId=id) }
            anotherWritten.complete(Unit)
        } }
        try { withTimeout(10_000) { anotherWritten.await() } }
        finally { finish.complete(Unit) }
        pending.await();others.awaitAll()
        val restarted=VisionPreprocessingCache(root) { 1 }
        val restored=restarted.segment("p","hash",100,200,.35f,.35f) { error("pending results were evicted") }
        assertEquals(seg.regions,restored.regions)
        assertEquals(seg.textLines,restored.textLines)
    }
}
