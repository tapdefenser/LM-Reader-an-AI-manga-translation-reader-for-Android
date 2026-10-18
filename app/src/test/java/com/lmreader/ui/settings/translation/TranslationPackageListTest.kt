package com.lmreader.ui.settings.translation

import com.lmreader.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class TranslationPackageListTest {
    private fun pack(source:String,target:String,version:String="1.0")=TranslationModelPack("$source-$target",version,LocalTranslationLanguage.fromTag(source),LocalTranslationLanguage.fromTag(target),emptyList())
    @Test fun columnsHaveIndependentInstalledFirstOrdering() {
        val deEn=pack("de","en");val enId=pack("en","id")
        val result=translationPackageColumns(listOf(pack("af","en"),pack("en","af"),deEn,enId),emptyList(),mapOf(deEn.id to deEn,enId.id to enId),"",Locale.ENGLISH)
        assertEquals(listOf("de-en","af-en"),result.incoming.map {it.id})
        assertEquals(listOf("en-id","en-af"),result.outgoing.map {it.id})
    }
    @Test fun searchUsesNativeLocalizedAndCodeWithoutLanguageMappings() {
        val list=listOf(pack("de","en"),pack("en","de"),pack("lzh","en"))
        listOf("Deutsch","德语","DE").forEach {query ->
            val result=translationPackageColumns(list,emptyList(),emptyMap(),query,Locale.SIMPLIFIED_CHINESE)
            assertEquals(1,result.incoming.size);assertEquals(1,result.outgoing.size)
        }
        assertEquals("lzh-en",translationPackageColumns(list,emptyList(),emptyMap(),"lzh",Locale.ENGLISH).incoming.single().id)
    }
    @Test fun sourceRefreshShowsLatestMetadataAndRetainsUnlistedInstalledPack() {
        val older=pack("de","en","1.0");val newer=older.copy(version="2.0");val retained=pack("en","id")
        val result=translationPackageColumns(listOf(newer),listOf(older,retained),mapOf(older.id to older,retained.id to retained),"",Locale.ENGLISH)
        assertEquals("2.0",result.incoming.single().version);assertEquals(retained,result.outgoing.single())
    }
    @Test fun directNonEnglishPackagesAreStillAvailable() {
        val direct=pack("de","id")
        assertEquals(listOf(direct),translationPackageColumns(listOf(direct),emptyList(),emptyMap(),"",Locale.ENGLISH).other)
    }
}
