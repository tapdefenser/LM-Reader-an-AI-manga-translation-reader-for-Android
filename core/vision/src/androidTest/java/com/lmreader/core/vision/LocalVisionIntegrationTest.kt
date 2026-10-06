package com.lmreader.core.vision

import android.graphics.*
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith

/** 只使用内存绘制的独立图，不读取漫画库或消费任何队列。 */
@RunWith(AndroidJUnit4::class)
class LocalVisionIntegrationTest {
    private val engine = LocalVisionEngine(ApplicationProvider.getApplicationContext())
    @After fun release() = runBlocking { engine.releaseModels() }
    private fun fixture(text: String,width: Int = 900,height: Int = 320,top: Float = 170f): Bitmap {
        val b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(b); canvas.drawColor(Color.WHITE)
        canvas.drawText(text,80f,top,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=62f; typeface=Typeface.DEFAULT_BOLD })
        return b
    }
    private fun assertCoordinates(result: LocalOcrResult,bitmap: Bitmap) {
        Assert.assertEquals(bitmap.width,result.width); Assert.assertEquals(bitmap.height,result.height)
        Assert.assertTrue(result.lines.all { it.bounds.left>=0 && it.bounds.top>=0 && it.bounds.right<=bitmap.width && it.bounds.bottom<=bitmap.height })
        Assert.assertEquals(result.lines.size,result.lines.map { it.id }.toSet().size)
    }
    @Test fun englishAndBlankAndReload() = runBlocking {
        val image=fixture("HELLO WORLD")
        val blank=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try {
            val result=engine.recognize("english",image,LocalOcrLanguage.ENGLISH)
            Log.i("VisionIntegration","EN: ${result.text} ${result.elapsedMillis}ms")
            Assert.assertTrue(result.text.replace(" ","").contains("HELLOWORLD")); assertCoordinates(result,image)
            val empty=engine.recognize("blank",blank,LocalOcrLanguage.ENGLISH)
            Assert.assertTrue(empty.lines.isEmpty())
            engine.releaseModels()
            val reloaded=engine.recognize("new-page",image,LocalOcrLanguage.ENGLISH)
            Assert.assertEquals("new-page",reloaded.imageId); Assert.assertEquals(result.text,reloaded.text)
            Assert.assertTrue(reloaded.lines.all { it.id.startsWith("new-page:") })
        } finally { image.recycle(); blank.recycle() }
    }
    @Test fun segAndHardwareOcrCanRunTogetherAndFallbackKeepsText() = runBlocking<Unit> {
        Log.i("VisionIntegration", "NNAPI preflight=${NnapiAvailability.unavailableReason ?: "GPU/NPU available"}")
        val accelerated = LocalVisionEngine(ApplicationProvider.getApplicationContext()) {
            VisionExecutionSettings(segConcurrency = 1, ocrConcurrency = 1, segGpu = true, ocrBackend = com.lmreader.core.model.OcrBackend.AUTO)
        }
        val image = fixture("HELLO WORLD")
        try {
            val segmented = async { accelerated.segment("concurrent-seg", image) }
            val recognized = async { accelerated.recognize("concurrent-ocr", image, LocalOcrLanguage.ENGLISH) }
            Assert.assertTrue(recognized.await().text.replace(" ", "").contains("HELLOWORLD"))
            val result = segmented.await()
            Assert.assertEquals("concurrent-seg", result.imageId)
            Assert.assertTrue(result.regions.isNotEmpty())
            Log.i("VisionIntegration", "Concurrent backend=${result.backend}, notices=${accelerated.accelerationMessages.value}")
        } finally { accelerated.releaseModels(); image.recycle() }
    }
    @Test fun chineseJapaneseAndKoreanUseMatchingDictionaries() = runBlocking {
        for ((language,text) in listOf(LocalOcrLanguage.CHINESE_SIMPLIFIED to "你好世界",
            LocalOcrLanguage.JAPANESE to "こんにちは",LocalOcrLanguage.KOREAN to "안녕하세요")) {
            val image=fixture(text)
            try {
                val result=engine.recognize(language.name,image,language)
                Log.i("VisionIntegration","$language: ${result.text} ${result.elapsedMillis}ms ${result.lines.map { it.bounds }}")
                Assert.assertTrue("$language: ${result.text}",result.text.contains(text)); assertCoordinates(result,image)
            } finally { image.recycle() }
        }
    }
    @Test fun nativeCjkDetectionAndRecognition() = runBlocking {
        val models=VisionModels(ApplicationProvider.getApplicationContext())
        PaddleDetector(models).use { detector ->
            for ((language,text) in listOf(LocalOcrLanguage.CHINESE_SIMPLIFIED to "你好世界",
                LocalOcrLanguage.JAPANESE to "こんにちは",LocalOcrLanguage.KOREAN to "안녕하세요")) {
                val image=fixture(text)
                try {
                    PaddleRecognizer(models,language==LocalOcrLanguage.KOREAN).use { recognizer ->
                        val boxes=detector.detect(image)
                        val rotated=rotateCounterClockwise(image)
                        try { Log.i("VisionIntegration","NATIVE $language ORIGINAL $boxes ROTATED ${detector.detect(rotated)}") }
                        finally { rotated.recycle() }
                        val decoded=boxes.map { (box,_) -> val line=crop(image,box)
                            try { recognizer.recognize(line).text } finally { if (line!==image) line.recycle() } }
                        Log.i("VisionIntegration","NATIVE $language OCR $decoded")
                        Assert.assertTrue("$language: $decoded",decoded.joinToString("").contains(text))
                    }
                } finally { image.recycle() }
            }
        }
    }
    @Test fun uprightVerticalJapaneseReadsRightColumnFirst() = runBlocking {
        val image=Bitmap.createBitmap(600,700,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image); canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=60f; typeface=Typeface.DEFAULT_BOLD }
        "こんにちは".forEachIndexed { i,c -> canvas.drawText(c.toString(),350f,120f+i*75f,paint) }
        "世界".forEachIndexed { i,c -> canvas.drawText(c.toString(),150f,120f+i*75f,paint) }
        try {
            val seg=engine.segment("vertical",image)
            val result=engine.recognize("vertical",image,LocalOcrLanguage.JAPANESE,textLines=seg.textLines)
            Log.i("VisionIntegration","VERTICAL: ${result.text} ${result.lines.map { it.bounds }} ${result.elapsedMillis}ms")
            Assert.assertTrue(result.text.contains("こんにちは")); Assert.assertTrue(result.text.contains("世界"))
            Assert.assertTrue(result.text.indexOf("こんにちは") < result.text.indexOf("世界")); assertCoordinates(result,image)
        } finally { image.recycle() }
    }
    @Test fun longImageOffsetsRegionsAndCancellation() = runBlocking {
        val image=fixture("BOTTOM TEXT",700,3000,2820f)
        try {
            val result=engine.recognize("long",image,LocalOcrLanguage.ENGLISH)
            Log.i("VisionIntegration","LONG: ${result.text} ${result.elapsedMillis}ms")
            Assert.assertTrue(result.text.replace(" ","").contains("BOTTOMTEXT"))
            Assert.assertTrue(result.lines.any { it.bounds.top>2500 }); assertCoordinates(result,image)
            val region=engine.recognize("crop",image,LocalOcrLanguage.ENGLISH,listOf(PixelRect(40f,2600f,680f,2900f)))
            Assert.assertTrue(region.text.replace(" ","").contains("BOTTOMTEXT"))
            Assert.assertTrue(region.lines.all { it.bounds.top>=2600 && it.bounds.bottom<=2900 })
            var entered=0
            val cancelled=launch {
                engine.recognize("cancel",image,LocalOcrLanguage.ENGLISH,progress={ p -> if (p.stage.startsWith("文字行检测")) { entered++; cancel() } })
            }
            cancelled.join(); Assert.assertTrue(cancelled.isCancelled); Assert.assertEquals(1,entered)
            Assert.assertFalse(image.isRecycled)
        } finally { image.recycle() }
    }
    @Test fun realSegmentationAndBlank() = runBlocking {
        val image=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image); canvas.drawColor(Color.WHITE)
        val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; strokeWidth=5f; style=Paint.Style.STROKE }
        canvas.drawRect(20f,20f,780f,980f,pen)
        canvas.drawOval(100f,100f,700f,460f,pen)
        canvas.drawLine(470f,454f,520f,530f,pen); canvas.drawLine(520f,530f,560f,446f,pen)
        canvas.drawCircle(400f,720f,100f,pen)
        canvas.drawText("HELLO WORLD",205f,270f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=43f; typeface=Typeface.DEFAULT_BOLD })
        val blank=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try {
            val result=engine.segment("seg",image)
            Log.i("VisionIntegration","SEG: ${result.regions.size} ${result.backend} ${result.elapsedMillis}ms")
            Assert.assertTrue("No bubble from fixture",result.regions.any { it.kind==RegionKind.BUBBLE && it.contour.size>=4 })
            Assert.assertTrue(result.regions.all { it.bounds.left>=0 && it.bounds.top>=0 && it.bounds.right<=800 && it.bounds.bottom<=1000 })
            Assert.assertTrue(result.regions.flatMap { it.contour }.all { it.x in 0f..800f && it.y in 0f..1000f })
            val blankSeg=engine.segment("blank-seg",blank)
            Log.i("VisionIntegration","BLANK SEG: ${blankSeg.regions}; lines=${blankSeg.textLines}")
            Assert.assertTrue("Blank SEG regions: ${blankSeg.regions}; lines=${blankSeg.textLines}",blankSeg.regions.isEmpty())
            val long=Bitmap.createBitmap(800,3000,Bitmap.Config.ARGB_8888)
            try {
                Canvas(long).apply { drawColor(Color.WHITE); drawBitmap(image,0f,2000f,null) }
                val longResult=engine.segment("long-seg",long)
                Log.i("VisionIntegration","LONG SEG: ${longResult.regions.size} ${longResult.elapsedMillis}ms")
                Assert.assertTrue(longResult.regions.any { it.kind==RegionKind.BUBBLE && it.bounds.top>2000 && it.contour.size>=4 })
                Assert.assertTrue(longResult.regions.all { it.bounds.top>=0 && it.bounds.bottom<=3000 })
            } finally { long.recycle() }
        } finally { image.recycle(); blank.recycle() }
    }
}
