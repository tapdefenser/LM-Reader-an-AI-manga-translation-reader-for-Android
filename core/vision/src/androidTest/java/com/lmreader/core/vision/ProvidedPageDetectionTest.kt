package com.lmreader.core.vision

import android.graphics.*
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.lmreader.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** Private reproduction inputs are supplied on the device, never bundled in the APK. */
class ProvidedPageDetectionTest {
    @Test fun inspectProvidedPages() = runBlocking {
        val input = InstrumentationRegistry.getArguments().getString("providedPages")
        assumeNotNull(input)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val backend = InstrumentationRegistry.getArguments().getString("visionBackend")
        val originalInput = InstrumentationRegistry.getArguments().getString("originalInput")=="true"
        val execution = when(backend) {
            "default" -> VisionExecutionSettings()
            "qnn" -> VisionExecutionSettings(ocrBackend=OcrBackend.QNN)
            "nnapi" -> VisionExecutionSettings(ocrBackend=OcrBackend.NNAPI)
            else -> VisionExecutionSettings(segGpu=false,ocrBackend=OcrBackend.CPU)
        }
        val session = LocalVisionSession(context, execution, {})
        try {
            for (number in 1..2) {
                val file=File(input!!,"page-$number.${if(originalInput) "webp" else "jpg"}")
                val original = VisionImageDecoder.decode(context,android.net.Uri.fromFile(file),4_000_000)
                try {
                    for (cropped in if(originalInput) listOf(false) else if (backend=="default") listOf(true) else listOf(false, true)) {
                        val image = if (cropped) Bitmap.createBitmap(original, 478, 0, 1445, original.height) else original
                        try {
                            val id = "provided-$number-$cropped"
                            val raw = session.segment(id, image)
                            val lines = session.detectSegTextLines(raw, image)
                            val seg = supplementFreeTextRegions(raw.copy(textLines=lines))
                            val regions = selectSegRegions(seg)
                            val selected = regions.flatMap { selectRegionTextLines(it, seg.regions, lines) }.distinct()
                            val recognized = session.recognize(id, image, LocalOcrLanguage.JAPANESE, null, selected)
                            // Do not print or persist text from the private pages.
                            val counts="page=$number cropped=$cropped backend=${raw.backend} dimensions=${image.width}x${image.height} bubbles=${raw.regions.count { it.kind==RegionKind.BUBBLE }} lines=${lines.size} targets=${regions.size} selected=${selected.size} recognized=${recognized.lines.count { it.confidence>.9f && it.text.length>=7 }}"
                            Log.i("ProvidedPages",counts)
                            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {putString("stream","\n$counts\n")})
                            val bubbles = regions.filter { it.kind==RegionKind.BUBBLE }
                            if(number==1) assertEquals("Both speech balloons must survive SEG selection",2,bubbles.size)
                            else {
                                assertTrue("Outlined captions must have detected lines",lines.size>=16)
                                // Fourteen full rows plus three short section labels in
                                // the original. Count rows once after fragment merging.
                                assertTrue("Outlined captions must yield all full text rows",recognized.lines.count {it.confidence>.9f && it.text.length>=7}>=14)
                                assertTrue("Caption section labels must also remain readable",recognized.lines.count {it.confidence>.9f && it.text.length>=2}>=17)
                            }
                            for (region in bubbles) cropSegRegion(image,region,seg.regions).use { crop ->
                                val local = selectRegionTextLines(region,seg.regions,lines).map { it.copy(bounds=it.bounds.offset(-crop.left.toFloat(),-crop.top.toFloat())) }
                                val result = session.recognize(id,crop.bitmap,LocalOcrLanguage.JAPANESE,null,local)
                                Log.i("ProvidedPages", "page=$number cropped=$cropped bubble=${region.bounds} lineCount=${local.size} recognized=${result.lines.map { it.text.length to it.confidence }}")
                                assertEquals(2,local.size)
                                assertEquals(2,result.lines.size)
                                assertTrue("Balloon crop must preserve heavy vertical glyphs",result.lines.all {it.confidence>.9f && it.text.length>=7})
                            }
                        } finally { if (image !== original) image.recycle() }
                    }
                } finally { original.recycle() }
            }
        } finally { session.releaseModels() }
    }
}
