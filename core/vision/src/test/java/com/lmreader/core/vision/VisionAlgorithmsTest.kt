package com.lmreader.core.vision

import com.lmreader.core.model.*
import org.junit.Test
import java.nio.FloatBuffer
import kotlin.test.*

class VisionAlgorithmsTest {
    @Test fun `text mask keeps disconnected strokes and accents in one complete contour`() {
        val w = 20; val h = 20; val proto = FloatArray(w*h*32) { -1f }
        for (y in 5..8) for (x in 2..9) proto[(y*w+x)*32] = 1f
        for (y in 12..17) for (x in 12..17) proto[(y*w+x)*32] = 1f
        proto[(2*w+3)*32] = 1f // a disconnected accent must survive
        val contours = segContours(RawSeg(PixelRect(0f,0f,20f,20f),.9f,1,
            FloatArray(32).apply { this[0] = 1f }), FloatBuffer.wrap(proto),w,h,Letterbox(w,h,w,h))
        assertEquals(1, contours.size)
        assertEquals(PixelRect(2f,2f,18f,18f), contourBounds(contours.single()))
        assertTrue(polygonContains(contours.single(), 15f, 15f))
    }
    @Test fun `merged prediction keeps a smaller disconnected balloon but discards isolated mask noise`() {
        val w = 20; val h = 20; val proto = FloatArray(w * h * 32) { -1f }
        for (y in 2..10) for (x in 2..9) proto[(y * w + x) * 32] = 1f
        for (y in 12..17) for (x in 12..17) proto[(y * w + x) * 32] = 1f
        proto[0] = 1f
        val coefficients = FloatArray(32).apply { this[0] = 1f }
        val contours = segContours(RawSeg(PixelRect(0f, 0f, 20f, 20f), .9f, 0, coefficients),
            FloatBuffer.wrap(proto), w, h, Letterbox(w, h, w, h))
        assertEquals(2, contours.size)
        assertTrue(contours[1].all { it.x >= 12f && it.y >= 12f })
    }
    @Test fun `class-aware head suppression retains both connected balloon predictions`() {
        val n = 2; val values = FloatArray(38 * n)
        for (i in 0 until n) {
            values[i] = .3f + i * .13f; values[n + i] = .3f
            values[2 * n + i] = .4f; values[3 * n + i] = .4f
            values[4 * n + i] = .9f - i * .1f
        }
        assertEquals(2, decodeSeg(FloatBuffer.wrap(values), n, 500, 500, .35f).size)
    }
    @Test fun `letterbox maps actual rounded edges to source pixels`() {
        val t=Letterbox(333,777,1472,1472)
        val r=t.rect(PixelRect(t.left.toFloat(),t.top.toFloat(),(t.left+t.contentWidth).toFloat(),(t.top+t.contentHeight).toFloat()))
        assertEquals(PixelRect(0f,0f,333f,777f),r)
        assertEquals(PixelPoint(0f,0f),t.point(-100f,-100f))
        assertEquals(PixelPoint(333f,777f),t.point(5000f,5000f))
    }
    @Test fun `vertical and horizontal tiles cover last edge with overlap`() {
        for ((width,height) in listOf(800 to 7001,7001 to 800)) {
            val tiles=planTiles(width,height)
            assertEquals(0,if (height>width) tiles.first().y else tiles.first().x)
            assertEquals(height,tiles.last().y+tiles.last().height)
            assertEquals(width,tiles.last().x+tiles.last().width)
            tiles.zipWithNext().forEach { (a,b) ->
                assertTrue(if (height>width) b.y < a.y+a.height else b.x < a.x+a.width)
            }
        }
        assertEquals(listOf(ImageTile(0,0,600,900)),planTiles(600,900))
    }
    @Test fun `class aware nms preserves text within same bubble and rejects invalid data`() {
        val n=4; val values=FloatArray(38*n)
        fun set(i: Int,score0: Float,score1: Float) {
            values[i]=.5f; values[n+i]=.5f; values[2*n+i]=.4f; values[3*n+i]=.4f
            values[4*n+i]=score0; values[5*n+i]=score1
        }
        set(0,.9f,.1f); set(1,.8f,.1f); set(2,.1f,.9f); set(3,Float.NaN,.9f)
        val result=decodeSeg(FloatBuffer.wrap(values),n,1000,500,.35f)
        assertEquals(listOf(0,1),result.map { it.classId })
        assertEquals(PixelRect(300f,150f,700f,350f),result.first().bounds)
    }
    @Test fun `mask keeps largest connected area and ignores isolated pixel`() {
        val w=8; val h=8; val proto=FloatArray(w*h*32) { -1f }
        for (y in 2..5) for (x in 2..5) proto[(y*w+x)*32]=1f
        proto[0]=1f
        val coefficients=FloatArray(32); coefficients[0]=1f
        val contour=segContour(RawSeg(PixelRect(0f,0f,8f,8f),.9f,0,coefficients),FloatBuffer.wrap(proto),w,h,Letterbox(8,8,8,8))
        assertTrue(contour.size>=8)
        assertTrue(contour.all { it.x in 2f..6f && it.y in 2f..6f })
    }
    @Test fun `8 connected components join diagonal foreground`() {
        val foreground=setOf(0,4,8,2)
        val parts=components(3,3) { it in foreground }
        assertEquals(1,parts.size); assertEquals(4,parts.single().indices.size)
    }
    @Test fun `db output scales probability map independently from model input`() {
        val p=FloatArray(100)
        for (y in 3..5) for (x in 3..6) p[y*10+x]=.9f
        val result=dbBoxes(FloatBuffer.wrap(p),10,10,Letterbox(1000,500,100,100))
        assertEquals(1,result.size)
        assertTrue(result.single().first.right<=1000 && result.single().first.bottom<=500)
        assertTrue(result.single().first.width>300)
    }
    @Test fun `blank page produces no db boxes`() {
        assertTrue(dbBoxes(FloatBuffer.wrap(FloatArray(100)),10,10,Letterbox(100,100,100,100)).isEmpty())
    }
    @Test fun `small confident text kernel survives the upstream component threshold`() {
        val p=FloatArray(100)
        for (i in 3..5) p[i*10+i]=.9f
        assertEquals(1,dbBoxes(FloatBuffer.wrap(p),10,10,Letterbox(100,100,100,100)).size)
        p[55]=0f
        assertTrue(dbBoxes(FloatBuffer.wrap(p),10,10,Letterbox(100,100,100,100)).isEmpty())
    }
    @Test fun `user confidence threshold changes detected lines without changing geometry`() {
        val p=FloatArray(100)
        for (y in 3..5) for (x in 3..6) p[y*10+x]=.55f
        val transform=Letterbox(100,100,100,100)
        assertEquals(1,dbBoxes(FloatBuffer.wrap(p),10,10,transform,.4f).size)
        assertTrue(dbBoxes(FloatBuffer.wrap(p),10,10,transform,.7f).isEmpty())
    }
    @Test fun `ctc blank separates repeated characters and preserves unicode`() {
        val chars=listOf("","你","好","😀"," ")
        val ids=listOf(1,1,0,1,2,2,0,3)
        val p=FloatArray(ids.size*chars.size) { .01f }
        ids.forEachIndexed { t,c -> p[t*chars.size+c]=.96f }
        val result=decodeCtc(FloatBuffer.wrap(p),longArrayOf(1,ids.size.toLong(),chars.size.toLong()),chars)
        assertEquals("你你好😀",result.text); assertEquals(.96f,result.confidence,.0001f)
    }
    @Test fun `ctc mismatched dictionary and nonfinite output fail explicitly`() {
        assertFailsWith<IllegalArgumentException> { decodeCtc(FloatBuffer.wrap(FloatArray(6)),longArrayOf(1,2,3),listOf("","a")) }
        assertFailsWith<IllegalArgumentException> { decodeCtc(FloatBuffer.wrap(floatArrayOf(.2f,Float.NaN)),longArrayOf(1,1,2),listOf("","a")) }
    }
    @Test fun `ctc all blank is successful empty text`() {
        assertEquals("",decodeCtc(FloatBuffer.wrap(floatArrayOf(.9f,.1f,.9f,.1f)),longArrayOf(1,2,2),listOf("","a")).text)
    }
    @Test fun `vertical japanese lines read from right to left`() {
        val left=OcrLine("l",PixelRect(10f,10f,30f,180f),"左",.9f)
        val right=OcrLine("r",PixelRect(80f,10f,100f,180f),"右",.9f)
        assertEquals(listOf(right,left),readingOrder(listOf(left,right),LocalOcrLanguage.JAPANESE))
    }
    @Test fun `overlapping tile detections deduplicate but separated lines remain`() {
        val a=PixelRect(1f,1f,200f,100f)
        assertTrue(duplicate(a,PixelRect(2f,2f,198f,99f)))
        assertFalse(duplicate(a,PixelRect(1f,110f,200f,200f)))
    }
    @Test fun `higher confidence truncated fragment never discards complete line`() {
        val complete=PixelRect(70f,100f,400f,190f) to .95f
        val fragment=PixelRect(280f,100f,400f,190f) to .99f
        assertEquals(listOf(complete),keepCompleteRegions(listOf(fragment,complete),{ it.first },{ it.second }))
        assertEquals(1,planTiles(900,320).size)
    }
    @Test fun `image decode sizes cap pixels and long edge without enlarging normal images`() {
        assertEquals(800 to 1000,visionDecodeSize(800,1000))
        for ((w,h) in listOf(9000 to 9000,1000 to 60000,60000 to 1000)) {
            val (dw,dh)=visionDecodeSize(w,h)
            assertTrue(dw.toLong()*dh<=8_000_000 && maxOf(dw,dh)<=12000)
            assertTrue(kotlin.math.abs(dw.toDouble()/dh-w.toDouble()/h)<.02)
        }
    }
    @Test fun `corrupt native outputs never masquerade as blank images`() {
        assertFailsWith<IllegalArgumentException> { requireFiniteTensor(FloatBuffer.wrap(floatArrayOf(0f,Float.NaN)),"Seg") }
        assertFailsWith<IllegalArgumentException> { dbBoxes(FloatBuffer.wrap(floatArrayOf(0f,0f,Float.NaN,0f)),2,2,Letterbox(10,10,10,10)) }
    }
}
