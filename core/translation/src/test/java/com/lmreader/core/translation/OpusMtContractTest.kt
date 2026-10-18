package com.lmreader.core.translation

import com.lmreader.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest

class OpusMtContractTest {
    private fun language(tag: String) = LocalTranslationLanguage.fromTag(tag)
    private fun pack(from: String, to: String) = TranslationModelPack("opus-$from-$to", "1", language(from), language(to),
        listOf("encoder", "decoder", "srcVocab", "trgVocab", "vocab", "config").map {
            TranslationModelFile(it, "$it.bin", 100, "0".repeat(64), "$it.bin", "$it.bin", 100, "0".repeat(64), TranslationFileCompression.NONE)
        }, TranslationModelEngine.OPUS_MT)

    @Test fun directPackagesWinAndPivotsAreNotRestrictedToEnglish() {
        val frDe = pack("fr", "de"); val deEs = pack("de", "es"); val esIt = pack("es", "it")
        val c = TranslationModelCatalog.fromPacks(listOf(frDe, deEs, esIt))
        assertEquals(listOf(frDe, deEs), c.route(language("fr"), language("es"), TranslationModelEngine.OPUS_MT))
        assertEquals(listOf(frDe, deEs, esIt), c.route(language("fr"), language("it"), TranslationModelEngine.OPUS_MT))
        val direct = pack("fr", "es")
        assertEquals(listOf(direct), TranslationModelCatalog.fromPacks(c.packs + direct).route(language("fr"), language("es")))
        assertThrows(IllegalArgumentException::class.java) { c.route(language("fr"), language("es"), TranslationModelEngine.BERGAMOT) }
    }
    @Test fun cyclesAndLongChainsCannotProduceAnUnboundedRoute() {
        val c = TranslationModelCatalog.fromPacks(listOf(pack("fr", "de"), pack("de", "fr"), pack("de", "es"), pack("es", "it"), pack("it", "pt")))
        assertThrows(IllegalArgumentException::class.java) { c.route(language("fr"), language("pt")) }
        assertTrue(c.route(language("fr"), language("fr")).isEmpty())
    }
    @Test fun engineSelectionIsIsolatedAndPackagesAreCappedAt350Megabytes() {
        val p = pack("fr", "es")
        val oversized = p.copy(files = p.files.map { if (it.role == "encoder") it.copy(size = 350_000_000, downloadSize = 350_000_000) else it })
        assertThrows(IllegalArgumentException::class.java) { TranslationModelCatalog.fromPacks(listOf(oversized)) }
        val original = TranslationModelCatalog(File("src/main/assets/translation/catalog.json").readText()).packs.first()
        val opus = p.copy(source = original.source, target = original.target)
        val c = TranslationModelCatalog.fromPacks(listOf(original, opus))
        assertEquals(listOf(original), c.route(original.source, original.target, TranslationModelEngine.BERGAMOT))
        assertEquals(listOf(opus), c.route(original.source, original.target, TranslationModelEngine.OPUS_MT))
        assertNotEquals(TranslationModelCatalog.sourceKey(TranslationSourceSettings(source = TranslationDownloadSource.HUGGING_FACE)),
            TranslationModelCatalog.sourceKey(TranslationSourceSettings(source = TranslationDownloadSource.HUGGING_FACE, engine = TranslationModelEngine.OPUS_MT)))
    }
    @Test fun gitObjectDigestsVerifySmallFilesAndRejectCorruption() {
        val bytes = "small tokenizer configuration".toByteArray()
        val hash = MessageDigest.getInstance("SHA-1").apply { update("blob ${bytes.size}\u0000".toByteArray()); update(bytes) }
            .digest().joinToString("") { "%02x".format(it) }
        val file = File.createTempFile("opus-config", ".json")
        try {
            val asset = TranslationModelFile("config", "config.json", bytes.size.toLong(), "", "config.json", "config.json", bytes.size.toLong(), "",
                TranslationFileCompression.NONE, gitBlobSha1 = hash)
            file.writeBytes(bytes); ModelPackageInstaller.verify(file, asset)
            file.writeBytes(bytes.copyOf().apply { this[0] = 0 })
            assertThrows(IllegalArgumentException::class.java) { ModelPackageInstaller.verify(file, asset) }
        } finally { file.delete() }
    }
    @Test fun decodingMasksPaddingAndChoosesFiniteScoresWithStableTies() {
        assertEquals(2L, OpusMtDecoding.selectToken(FloatBuffer.wrap(floatArrayOf(Float.NaN, 20f, 3f, 3f)), 1))
        assertThrows(IllegalArgumentException::class.java) { OpusMtDecoding.selectToken(FloatBuffer.wrap(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY)), 0) }
        val p = pack("en", "zh-Hans")
        assertEquals(">>cmn_Hans<<", OpusMtDecoding.targetPrefix(p, mapOf(">>cmn_Hans<<" to 1L, ">>cmn_Hant<<" to 2L)))
        assertEquals("", OpusMtDecoding.targetPrefix(p, mapOf("word" to 1L)))
    }
    @Test fun remoteMetadataPinsEachFileAndDoesNotNeedTokenizerBodies() {
        val info = buildJsonObject {
            put("sha", "a".repeat(40))
            putJsonArray("siblings") {
                listOf("onnx/encoder_model_quantized.onnx", "onnx/decoder_model_merged_quantized.onnx", "source.spm", "target.spm", "vocab.json", "config.json").forEach { path ->
                    add(buildJsonObject {
                        put("rfilename", path); put("size", 1024)
                        if (path.endsWith(".onnx")) putJsonObject("lfs") { put("sha256", "b".repeat(64)) }
                        else put("blobId", "c".repeat(40))
                    })
                }
            }
        }
        val p = RemoteOpusCatalog.parse("Xenova/opus-mt-fr-es", info, "https://hf-mirror.com", "apache-2.0")
        assertEquals(TranslationModelEngine.OPUS_MT, p.engine)
        assertTrue(p.files.all { it.url!!.contains("/resolve/${"a".repeat(40)}/") })
        assertTrue(p.files.filterNot { it.role in setOf("encoder", "decoder") }.all { it.gitBlobSha1 == "c".repeat(40) })
        assertEquals(p, TranslationModelCatalog.fromPacks(listOf(p)).packs.single())
    }
    @Test fun bundledFallbackIsUsableAndMirrorSelectionPreservesPinnedRevisions() {
        val catalog = TranslationModelCatalog(File("src/main/assets/translation/opus_catalog.json").readText())
        assertTrue(catalog.packs.isNotEmpty())
        assertTrue(catalog.packs.any {it.source.tag != "en" && it.target.tag != "en"})
        assertTrue(catalog.packs.all {it.engine == TranslationModelEngine.OPUS_MT && it.installedBytes <= 350_000_000 && it.repository.isNotBlank() && it.license.isNotBlank()})
        val p = catalog.packs.first()
        assertTrue(p.files.filterNot {it.role in setOf("encoder", "decoder")}.all {it.gitBlobSha1 != null || it.sha256.isNotBlank()})
        val fetched = p.copy(files = p.files.map {if(it.gitBlobSha1 != null) it.copy(sha256 = "", downloadSha256 = "") else it})
        assertTrue(p.sameContents(fetched))
        val mirror = TranslationSourceSettings(source = TranslationDownloadSource.HF_MIRROR, engine = TranslationModelEngine.OPUS_MT)
        val url = catalog.downloadUrl(p, p.files.first(), mirror)
        assertTrue(url.startsWith("https://hf-mirror.com/"))
        assertTrue(url.contains("/resolve/${p.version.removePrefix("hf-")}"))
    }
}
