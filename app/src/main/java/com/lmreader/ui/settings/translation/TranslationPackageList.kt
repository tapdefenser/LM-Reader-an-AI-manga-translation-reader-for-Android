package com.lmreader.ui.settings.translation

import com.lmreader.core.model.*
import java.util.Locale

internal data class TranslationPackageColumns(val incoming:List<TranslationModelPack>,val outgoing:List<TranslationModelPack>,val other:List<TranslationModelPack>)

internal fun translationPackageColumns(available:List<TranslationModelPack>,retained:List<TranslationModelPack>,
    installed:Map<String,TranslationModelPack>,query:String,locale:Locale,
    extraNames:(LocalTranslationLanguage)->List<String> = {emptyList()}):TranslationPackageColumns {
    val search=query.trim()
    val packs=(available+retained).distinctBy {it.id}.filter {pack -> search.isEmpty() || listOf(
        pack.id,pack.source.tag,pack.target.tag,pack.source.nativeName,pack.target.nativeName,
        pack.source.localizedName(locale),pack.target.localizedName(locale),pack.version,installed[pack.id]?.version.orEmpty()
    ).plus(extraNames(pack.source)).plus(extraNames(pack.target)).any {it.contains(search,ignoreCase=true)} }
    val order=compareByDescending<TranslationModelPack> {installed.containsKey(it.id)}.thenBy {if(it.source==LocalTranslationLanguage.ENGLISH) it.target.tag else it.source.tag}
    return TranslationPackageColumns(packs.filter {it.target==LocalTranslationLanguage.ENGLISH}.sortedWith(order),
        packs.filter {it.source==LocalTranslationLanguage.ENGLISH}.sortedWith(order),
        packs.filter {it.source!=LocalTranslationLanguage.ENGLISH && it.target!=LocalTranslationLanguage.ENGLISH}.sortedWith(order))
}
