package com.lmreader.ui.reader

import android.graphics.*
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.lmreader.core.model.*
import com.lmreader.core.storage.reader.*
import com.lmreader.ui.reader.translation.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class ReaderOverlayReloadTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context=ApplicationProvider.getApplicationContext<android.content.Context>()
    private val input=File(context.cacheDir,"overlay-reload-${UUID.randomUUID()}.png")
    @After fun cleanup() { input.delete() }
    private fun imageView(view: View): TapAwareSubsamplingImageView? = when(view) {
        is TapAwareSubsamplingImageView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { imageView(view.getChildAt(it)) }
        else -> null
    }
    @Test fun croppedTranslationSurvivesSourceRebindingAppearanceAndOriginalToggle() {
        val original=Bitmap.createBitmap(400,500,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        Canvas(original).apply {
            drawRect(40f,50f,360f,450f,Paint().apply {color=Color.BLACK;style=Paint.Style.STROKE;strokeWidth=4f})
            drawText("HELLO",100f,220f,Paint().apply {color=Color.BLACK;textSize=32f})
        }
        input.outputStream().use { original.compress(Bitmap.CompressFormat.PNG,100,it) };original.recycle()
        val page=ReaderPage("overlay-reload",0,"page.png","page.png")
        fun source()=object:PageSource {
            override suspend fun pages()=listOf(page)
            override suspend fun open(page:ReaderPage)=input.inputStream()
            override suspend fun probe(page:ReaderPage)=PageGeometry(400,500)
        }
        val translated=listOf(PageTranslatedRegion(PageTextRegion("bubble",RegionKind.BUBBLE,
            PixelRect(80f,120f,320f,310f),emptyList(),"HELLO",listOf(PixelRect(90f,175f,300f,230f))),"你好"))
        val saved=ReaderPageTranslation(page.pageId,"fixture",File(context.cacheDir,"unused.json"),LocalTranslationLanguage.ENGLISH,
            LocalTranslationLanguage.CHINESE_SIMPLIFIED,ReaderPageArtifactStore.hashFile(input),400,500,translated,emptyList(),0)
        val boundSource=mutableStateOf<PageSource>(source())
        val render=mutableStateOf(BubbleRenderSettings())
        val showOriginal=mutableStateOf(false)
        compose.setContent { EnginePageView(boundSource.value,page,ReaderSettings(cropBorders=true),onSingleTap={_,_->},
            translation=saved,renderSettings=render.value,showingOriginal=showOriginal.value) }
        try { compose.waitUntil(15000) { imageView(compose.activity.window.decorView)?.let {it.isReady && it.overlay!=null}==true } }
        catch (error: Exception) {
            val failed=imageView(compose.activity.window.decorView)
            throw AssertionError("native=${failed!=null}, ready=${failed?.isReady}, size=${failed?.width}x${failed?.height}, source=${failed?.sWidth}x${failed?.sHeight}, overlay=${failed?.overlay!=null}, geometry=${failed?.overlayGeometry}",error)
        }
        val view=checkNotNull(imageView(compose.activity.window.decorView))
        val previous=view.overlay
        val geometry=view.overlayGeometry
        assertTrue(geometry!!.crop.left>0)
        compose.runOnIdle { boundSource.value=source() }
        compose.waitUntil(10000) { view.overlay!=null && view.overlay !== previous }
        assertEquals(geometry,view.overlayGeometry)
        val refreshed=view.overlay
        compose.runOnIdle { render.value=render.value.copy(opacityPercent=80) }
        compose.waitUntil(10000) { view.overlay!=null && view.overlay !== refreshed }
        compose.runOnIdle {showOriginal.value=true}
        compose.waitUntil(5000) {!view.showTranslation}
        compose.runOnIdle {showOriginal.value=false}
        compose.waitUntil(5000) {view.showTranslation}
        assertNotNull(view.overlay)
        assertEquals(geometry,view.overlayGeometry)
    }
}
