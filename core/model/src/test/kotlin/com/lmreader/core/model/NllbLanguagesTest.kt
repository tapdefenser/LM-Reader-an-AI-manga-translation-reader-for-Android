package com.lmreader.core.model

import kotlin.test.*
import org.junit.Test

class NllbLanguagesTest {
    @Test fun publishedLanguageTokensHaveStableIdsAndDistinctScriptChoices() {
        assertEquals(202, NllbLanguages.languages.size)
        assertEquals(202, NllbLanguages.languages.map(NllbLanguages::tokenId).distinct().size)
        assertEquals(256047, NllbLanguages.tokenId(LocalTranslationLanguage.ENGLISH))
        assertEquals(256200, NllbLanguages.tokenId(LocalTranslationLanguage.CHINESE_SIMPLIFIED))
        assertEquals(256201, NllbLanguages.tokenId(LocalTranslationLanguage.CHINESE_TRADITIONAL))
        assertEquals("jpn_Jpan", NllbLanguages.token(LocalTranslationLanguage.JAPANESE))
        assertEquals("kor_Hang", NllbLanguages.token(LocalTranslationLanguage.KOREAN))
        assertEquals("arb_Arab", NllbLanguages.token(LocalTranslationLanguage.fromTag("ar")))
        assertEquals("pes_Arab", NllbLanguages.token(LocalTranslationLanguage.fromTag("fa")))
        assertTrue(NllbLanguages.supports(LocalTranslationLanguage.fromTag("ace-Arab")))
        assertTrue(NllbLanguages.supports(LocalTranslationLanguage.fromTag("ace-Latn")))
        assertFalse(NllbLanguages.supports(LocalTranslationLanguage.fromTag("ace")))
    }
    @Test fun regionalAliasesPreserveTheActualScriptAndUnsupportedLanguagesAreRejected() {
        assertEquals("eng_Latn", NllbLanguages.token(LocalTranslationLanguage.fromTag("en-US")))
        assertEquals("zho_Hant", NllbLanguages.token(LocalTranslationLanguage.fromTag("zh-TW")))
        assertEquals("zho_Hans", NllbLanguages.token(LocalTranslationLanguage.fromTag("zh-CN")))
        for (tag in listOf("qaa", "lzh", "ja-Latn", "zh-Latn", "en-Cyrl")) {
            assertFalse(NllbLanguages.supports(LocalTranslationLanguage.fromTag(tag)), tag)
            assertFailsWith<IllegalArgumentException> { NllbLanguages.tokenId(LocalTranslationLanguage.fromTag(tag)) }
        }
    }
}
