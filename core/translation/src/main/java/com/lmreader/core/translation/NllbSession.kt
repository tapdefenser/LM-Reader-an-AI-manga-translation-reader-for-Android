package com.lmreader.core.translation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.lmreader.core.model.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

internal object NllbTokenizerNative {
    init { System.loadLibrary("lmreader_translation") }
    external fun load(path: ByteArray): Long
    external fun encode(handle: Long, text: ByteArray): IntArray
    external fun decode(handle: Long, ids: IntArray): ByteArray
    external fun close(handle: Long)
}

/** A dedicated encoder/decoder NMT session. No prompts, chat model or network inference. */
internal class NllbSession private constructor(
    private val encoder: OrtSession, private val decoder: OrtSession, private var tokenizer: Long,
) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()

    suspend fun translate(source: LocalTranslationLanguage, target: LocalTranslationLanguage, text: String): String {
        val from = NllbLanguages.tokenId(source).toLong()
        val to = NllbLanguages.tokenId(target).toLong()
        // Preserve paragraph boundaries. Long paragraphs are processed in full,
        // in bounded chunks rather than silently truncated to the model context.
        val paragraphs = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            currentCoroutineContext().ensureActive()
            paragraphs += if (paragraph.isBlank()) paragraph else {
                val tokens = NllbTokenizerNative.encode(tokenizer, paragraph.toByteArray(Charsets.UTF_8))
                val chunks = mutableListOf<String>()
                for (chunk in tokens.asList().chunked(510)) chunks += generate(from, to, chunk)
                chunks.joinToString(" ")
            }
        }
        return paragraphs.joinToString("\n")
    }

    private suspend fun generate(source: Long, target: Long, tokens: List<Int>): String {
        val ids = longArrayOf(source) + tokens.map(Int::toLong) + longArrayOf(2)
        val mask = LongArray(ids.size) { 1L }
        fun longs(values: LongArray) = OnnxTensor.createTensor(environment, LongBuffer.wrap(values), longArrayOf(1, values.size.toLong()))
        longs(ids).use { input -> longs(mask).use { attention ->
            encoder.run(mapOf("input_ids" to input, "attention_mask" to attention)).use { encoded ->
                val hidden = encoded[0] as OnnxTensor
                OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), longArrayOf(1, 16, 0, 64)).use { empty ->
                    OnnxTensor.createTensor(environment, booleanArrayOf(false)).use { noCache ->
                        OnnxTensor.createTensor(environment, booleanArrayOf(true)).use { useCache ->
                            var first: OrtSession.Result? = null
                            var previous: OrtSession.Result? = null
                            var nextIds = longArrayOf(2, target)
                            val generated = ArrayList<Int>()
                            try {
                                repeat(512) {
                                    currentCoroutineContext().ensureActive()
                                    longs(nextIds).use { next ->
                                        val feed = mutableMapOf("input_ids" to next, "encoder_hidden_states" to hidden,
                                            "encoder_attention_mask" to attention, "use_cache_branch" to if (first == null) noCache else useCache)
                                        for (layer in 0 until 12) for (side in listOf("decoder", "encoder")) for (kind in listOf("key", "value")) {
                                            val cache = if (side == "encoder") first else previous
                                            feed["past_key_values.$layer.$side.$kind"] = cache?.get("present.$layer.$side.$kind")
                                                ?.orElseThrow { IllegalStateException("NLLB 缺少注意力缓存") } as? OnnxTensor ?: empty
                                        }
                                        val result = decoder.run(feed)
                                        val old = previous
                                        previous = result
                                        if (first == null) first = result
                                        if (old != null && old !== first) old.close()
                                        val output = result.get("logits").orElseThrow { IllegalStateException("NLLB 缺少译文分数") } as OnnxTensor
                                        val vocab = output.info.shape.last().toInt()
                                        require(vocab == 256206) { "NLLB 译文词表不兼容" }
                                        val scores = output.floatBuffer
                                        val offset = scores.remaining() - vocab
                                        var best = 2
                                        var score = Float.NEGATIVE_INFINITY
                                        // Suppress padding, BOS, language and mask tokens after the forced target language.
                                        for (id in 2..256000) {
                                            val value = scores.get(offset + id)
                                            if (value > score) { best = id; score = value }
                                        }
                                        check(score.isFinite()) { "NLLB 返回无效译文分数" }
                                        if (best == 2) return NllbTokenizerNative.decode(tokenizer, generated.toIntArray()).toString(Charsets.UTF_8)
                                        generated += best
                                        nextIds = longArrayOf(best.toLong())
                                    }
                                }
                                error("NLLB 译文超过长度限制，请拆分文本后重试")
                            } finally {
                                if (previous !== first) previous?.close()
                                first?.close()
                            }
                        }
                    }
                }
            }
        } }
    }

    override fun close() {
        try { encoder.close() } finally {
            try { decoder.close() } finally {
                if (tokenizer != 0L) { NllbTokenizerNative.close(tokenizer); tokenizer = 0L }
            }
        }
    }

    companion object {
        suspend fun create(pack: TranslationModelPack, directory: File): NllbSession {
            val environment = OrtEnvironment.getEnvironment()
            fun path(role: String) = File(directory, pack.files.single { it.role == role }.name).absolutePath
            var tokenizer = 0L
            var encoder: OrtSession? = null
            var decoder: OrtSession? = null
            try {
                currentCoroutineContext().ensureActive()
                tokenizer = NllbTokenizerNative.load(path("tokenizer").toByteArray(Charsets.UTF_8))
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                    options.setInterOpNumThreads(1)
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    encoder = environment.createSession(path("encoder"), options)
                    currentCoroutineContext().ensureActive()
                    decoder = environment.createSession(path("decoder"), options)
                }
                currentCoroutineContext().ensureActive()
                return NllbSession(requireNotNull(encoder), requireNotNull(decoder), tokenizer)
            } catch (failure: Throwable) {
                decoder?.close(); encoder?.close()
                if (tokenizer != 0L) NllbTokenizerNative.close(tokenizer)
                throw failure
            }
        }
    }
}
