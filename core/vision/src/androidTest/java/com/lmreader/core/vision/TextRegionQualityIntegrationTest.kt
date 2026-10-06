package com.lmreader.core.vision

import android.graphics.*
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextRegionQualityIntegrationTest {
    @Test fun smallHeavyTextInSpeechAndCaptionsIsRecognizedFromSegLines() = runBlocking {
        val image = Bitmap.createBitmap(1800,2400,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val speech = SegRegion("speech",RegionKind.BUBBLE,PixelRect(180f,200f,500f,440f),.9f)
        val caption = SegRegion("caption",RegionKind.FREE_TEXT,PixelRect(1090f,1580f,1290f,1660f),.9f)
        Canvas(image).apply {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=Color.BLACK; textSize=22f; typeface=Typeface.create("sans-serif-black",Typeface.NORMAL)
                style=Paint.Style.FILL_AND_STROKE; strokeWidth=.5f
            }
            drawText("BOLD HELLO",250f,300f,paint); drawText("WORLD",270f,335f,paint)
            drawText("OUTSIDE TEXT",1100f,1610f,paint); drawText("CAPTION",1120f,1645f,paint)
        }
        val session = LocalVisionSession(ApplicationProvider.getApplicationContext(),
            VisionExecutionSettings(segGpu=false,ocrBackend=OcrBackend.CPU),{})
        try {
            val coarse = SegResult("bold",image.width,image.height,listOf(speech,caption),0,"fixture")
            val pageLines = session.detectTextLines("bold",image)
            val lines = session.detectSegTextLines(coarse,image)
            val speechLines = selectRegionTextLines(speech,coarse.regions,lines)
            val captionLines = selectRegionTextLines(caption,coarse.regions,lines)
            val recognized = session.recognize("bold",image,LocalOcrLanguage.ENGLISH,null,speechLines+captionLines)
            val text = recognized.text.replace(" ","")
            Log.i("TextQuality","pageLines=${pageLines.size}, refinedLines=${lines.size}, OCR=${recognized.text}, scores=${recognized.lines.map { it.confidence }}")
            assertTrue(recognized.text,text.contains("BOLDHELLO") && text.contains("WORLD"))
            assertTrue(recognized.text,text.contains("OUTSIDETEXT") && text.contains("CAPTION"))
            assertEquals(2,speechLines.size); assertEquals(2,captionLines.size)
            assertTrue(recognized.lines.all { it.bounds.left>=0 && it.bounds.top>=0 && it.bounds.right<=image.width && it.bounds.bottom<=image.height })
        } finally { session.releaseModels(); image.recycle() }
    }
}
