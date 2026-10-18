package com.lmreader.ui.translation

import com.lmreader.core.model.WorkflowProgram
import com.lmreader.core.workflow.translationEngines

import com.lmreader.core.model.LocalTranslationLanguage
import com.lmreader.core.translation.TranslationModelCatalog
import java.util.Locale

fun TranslationModelCatalog.forWorkflow(program: WorkflowProgram?): TranslationModelCatalog {
    val engines = program?.translationEngines().orEmpty()
    return if (engines.isEmpty()) this else TranslationModelCatalog.fromPacks(packs.filter { it.engine in engines })
}

/** Platform lists can include undefined or malformed tags on some Android versions. */
fun translationLanguageChoices(tags: Iterable<String>): List<LocalTranslationLanguage> =
    (tags.mapNotNull { runCatching { LocalTranslationLanguage.fromTag(it) }.getOrNull() } +
        listOf(LocalTranslationLanguage.CHINESE_SIMPLIFIED, LocalTranslationLanguage.CHINESE_TRADITIONAL)).distinct()

fun TranslationModelCatalog.availableTargets(source: LocalTranslationLanguage): List<LocalTranslationLanguage> =
    languages.filter { it != source && runCatching { route(source, it) }.isSuccess }

fun TranslationModelCatalog.availableSources(): List<LocalTranslationLanguage> =
    languages.filter { source -> languages.any { it != source && runCatching { route(source, it) }.isSuccess } }

/** Stable partition: downloaded direct and pivot routes move first; no labels or automatic selection. */
fun prioritizeInstalledLanguages(languages: List<LocalTranslationLanguage>, installed: List<LocalTranslationLanguage>) =
    languages.filter { it in installed } + languages.filterNot { it in installed }

/** Accept old display-name preferences; new choices persist canonical language tags. */
fun matchEngineLanguage(value: String?, languages: List<LocalTranslationLanguage>): LocalTranslationLanguage? =
    languages.firstOrNull { value.equals(it.tag, true) } ?:
    languages.firstOrNull { candidate ->
        value.equals(candidate.nativeName, true) ||
            listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH, Locale.getDefault()).any { locale ->
                platformLanguageNames(candidate, locale).toList().any { value.equals(it, true) }
            }
    }
