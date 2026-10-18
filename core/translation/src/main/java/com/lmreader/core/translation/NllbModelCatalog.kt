package com.lmreader.core.translation

import com.lmreader.core.model.*

/** One verified model serves every supported direction; language choices never duplicate its weights. */
object NllbModelCatalog {
    const val REPOSITORY = "Xenova/nllb-200-distilled-600M"
    const val REVISION = "261c31d1a5732c67cdd16d80e8d6088507c7ccea"
    const val PACK_ID = "nllb-200-distilled-600m-int8"
    fun pack(host: String): TranslationModelPack {
        val base = TranslationModelCatalog.validateCustomBase(host)
        fun file(role: String, path: String, size: Long, hash: String) = TranslationModelFile(
            role, path.substringAfterLast('/'), size, hash, path, path, size, hash,
            TranslationFileCompression.NONE, "$base/$REPOSITORY/resolve/$REVISION/$path")
        return TranslationModelPack(PACK_ID, "onnx-int8-${REVISION.take(12)}",
            LocalTranslationLanguage.ENGLISH, LocalTranslationLanguage.CHINESE_SIMPLIFIED, listOf(
                file("encoder", "onnx/encoder_model_quantized.onnx", 419120483, "5cde664eacba07a62f198857ec6c06e09572b1ebb77c8137f1fa99ac604a3a28"),
                file("decoder", "onnx/decoder_model_merged_quantized.onnx", 475505771, "dd66608c2a4194e78f95548fa0e64f24302303698c5b09fa8e1f9e16ec00676b"),
                file("tokenizer", "sentencepiece.bpe.model", 4852054, "14bb8dfb35c0ffdea7bc01e56cea38b9e3d5efcdcb9c251d6b40538e1aab555a"),
            ), TranslationModelEngine.NLLB)
    }
}
