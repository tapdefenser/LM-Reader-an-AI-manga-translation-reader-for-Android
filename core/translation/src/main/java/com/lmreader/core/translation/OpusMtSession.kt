package com.lmreader.core.translation

import ai.onnxruntime.*
import com.lmreader.core.model.TranslationModelPack
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Reuses SentencePiece already linked into the Bergamot library. No Python/tokenizer runtime. */
internal object MarianTokenizerNative {
    init { System.loadLibrary("lmreader_translation") }
    external fun open(source: ByteArray, target: ByteArray): Long
    external fun encode(handle: Long, text: ByteArray): Array<ByteArray>
    external fun decode(handle: Long, pieces: Array<ByteArray>): ByteArray
    external fun close(handle: Long)
}

/** Marian encoder + cached decoder. All calls belong to one leased translation slot. */
internal class OpusMtSession private constructor(private val pack: TranslationModelPack,
    private val encoder: OrtSession, private val decoder: OrtSession, private val tokenizer: Long,
    private val vocabulary: Map<String, Long>, private val config: OpusMtConfig) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val pieces = vocabulary.entries.associate { it.value to it.key }
    private val unknown = requireNotNull(vocabulary["<unk>"])
    private val targetPrefix = OpusMtDecoding.targetPrefix(pack, vocabulary)

    suspend fun translate(text: String): String {
        val tokens = MarianTokenizerNative.encode(tokenizer, text.toByteArray(Charsets.UTF_8)).map { piece ->
            vocabulary[piece.toString(Charsets.UTF_8)] ?: unknown
        }
        if (tokens.isEmpty()) return ""
        val maxSource = minOf(config.maximumPositions - 2, 240)
        return tokens.chunked(maxSource).map { chunk ->
            currentCoroutineContext().ensureActive()
            val prefix = if (targetPrefix.isEmpty()) emptyList() else listOf(vocabulary.getValue(targetPrefix))
            translateTokens(prefix + chunk + config.eos)
        }.joinToString(" ")
    }

    private suspend fun translateTokens(source: List<Long>): String {
        val shape = longArrayOf(1, source.size.toLong())
        OnnxTensor.createTensor(environment, LongBuffer.wrap(source.toLongArray()), shape).use { input ->
            OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(source.size) { 1 }), shape).use { mask ->
                encoder.run(mapOf("input_ids" to input, "attention_mask" to mask)).use { encoded ->
                    val hidden = encoded.get("last_hidden_state").get() as OnnxTensor
                    val headSize = config.dimension / config.heads
                    OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), longArrayOf(1, config.heads.toLong(), 0, headSize.toLong())).use { empty ->
                        var past: OrtSession.Result? = null
                        var crossAttention: OrtSession.Result? = null
                        val output = mutableListOf<Long>()
                        var next = config.start
                        try {
                            for (step in 0 until config.maximumPositions.coerceAtMost(512)) {
                                currentCoroutineContext().ensureActive()
                                val inputs = mutableMapOf<String, OnnxTensor>("encoder_hidden_states" to hidden, "encoder_attention_mask" to mask)
                                for (layer in 0 until config.layers) for (kind in listOf("key", "value")) {
                                    inputs["past_key_values.$layer.decoder.$kind"] = past?.get("present.$layer.decoder.$kind")?.get() as? OnnxTensor ?: empty
                                    inputs["past_key_values.$layer.encoder.$kind"] = crossAttention?.get("present.$layer.encoder.$kind")?.get() as? OnnxTensor ?: empty
                                }
                                OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(next)), longArrayOf(1, 1)).use { token ->
                                    OnnxTensor.createTensor(environment, booleanArrayOf(past != null)).use { useCache ->
                                        inputs["input_ids"] = token; inputs["use_cache_branch"] = useCache
                                        val result = decoder.run(inputs)
                                        try {
                                            val logits = result.get("logits").get() as OnnxTensor
                                            val previous = past
                                            next = OpusMtDecoding.selectToken(logits.floatBuffer, config.pad)
                                            if (crossAttention == null) crossAttention = result
                                            past = result
                                            if (previous !== crossAttention) previous?.close()
                                        } catch (failure: Throwable) { result.close(); throw failure }
                                    }
                                }
                                if (next == config.eos) break
                                output += next
                                require(output.size < config.maximumPositions.coerceAtMost(512)) { "OPUS-MT output is too long; split the source text" }
                            }
                            return MarianTokenizerNative.decode(tokenizer, output.map { id ->
                                requireNotNull(pieces[id]) { "Unknown OPUS-MT output token: $id" }.toByteArray(Charsets.UTF_8)
                            }.toTypedArray()).toString(Charsets.UTF_8)
                        } finally {
                            if (past !== crossAttention) past?.close()
                            crossAttention?.close()
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        try { decoder.close() } finally { try { encoder.close() } finally { MarianTokenizerNative.close(tokenizer) } }
    }

    companion object {
        fun create(pack: TranslationModelPack, directory: File): OpusMtSession {
            fun file(role: String) = File(directory, pack.files.single { it.role == role }.name)
            val config = OpusMtConfig.parse(file("config").readText())
            val vocabulary = Json.parseToJsonElement(file("vocab").readText()).jsonObject.mapValues { it.value.jsonPrimitive.long }
            require(vocabulary.size in 100..300_000 && vocabulary.values.distinct().size == vocabulary.size)
            require(pack.targetPrefix.isEmpty() || vocabulary.containsKey(pack.targetPrefix))
            var encoder: OrtSession? = null; var decoder: OrtSession? = null; var tokenizer: Long? = null
            try {
                tokenizer = MarianTokenizerNative.open(file("srcVocab").absolutePath.toByteArray(Charsets.UTF_8), file("trgVocab").absolutePath.toByteArray(Charsets.UTF_8))
                val environment = OrtEnvironment.getEnvironment()
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
                    encoder = environment.createSession(file("encoder").absolutePath, options)
                    decoder = environment.createSession(file("decoder").absolutePath, options)
                }
                require(encoder!!.inputNames == setOf("input_ids", "attention_mask"))
                require("use_cache_branch" in decoder!!.inputNames && "logits" in decoder!!.outputNames)
                return OpusMtSession(pack, encoder!!, decoder!!, tokenizer, vocabulary, config)
            } catch (failure: Throwable) {
                decoder?.close(); encoder?.close(); tokenizer?.let(MarianTokenizerNative::close); throw failure
            }
        }
    }
}

internal data class OpusMtConfig(val pad: Long, val eos: Long, val start: Long, val layers: Int,
    val heads: Int, val dimension: Int, val maximumPositions: Int) {
    companion object {
        fun parse(text: String): OpusMtConfig {
            val json = Json.parseToJsonElement(text).jsonObject
            require(json["model_type"]?.jsonPrimitive?.content == "marian")
            fun number(key: String) = json.getValue(key).jsonPrimitive.long
            return OpusMtConfig(number("pad_token_id"), number("eos_token_id"), number("decoder_start_token_id"),
                number("decoder_layers").toInt(), number("decoder_attention_heads").toInt(), number("d_model").toInt(),
                number("max_position_embeddings").toInt()).also {
                require(it.layers in 1..12 && it.heads in 1..32 && it.dimension in 128..2048 && it.dimension % it.heads == 0)
                require(it.maximumPositions in 32..4096 && it.pad >= 0 && it.eos >= 0 && it.start >= 0)
            }
        }
    }
}

internal object OpusMtDecoding {
    fun targetPrefix(pack: TranslationModelPack, vocabulary: Map<String, Long>): String {
        if (pack.targetPrefix.isNotEmpty()) return pack.targetPrefix.also { require(vocabulary.containsKey(it)) }
        val codes = vocabulary.keys.filter { it.matches(Regex(">>[A-Za-z_]{2,24}<<")) }.toSet()
        if (codes.isEmpty()) return ""
        val tag = pack.target.tag
        val iso3 = runCatching { java.util.Locale.forLanguageTag(tag).isO3Language }.getOrNull()
        val candidates = if (tag == "zh-Hans") listOf("cmn_Hans", "cmn", "zho_Hans", "zho", "zh") else listOfNotNull(tag, iso3)
        return requireNotNull(candidates.map { ">>$it<<" }.firstOrNull { it in codes }) { "No target-language token for $tag" }
    }
    fun selectToken(scores: FloatBuffer, pad: Long): Long {
        require(scores.hasRemaining())
        val offset = scores.position(); var best = -1; var value = Float.NEGATIVE_INFINITY
        for (index in 0 until scores.remaining()) {
            val score = scores.get(offset + index)
            if (index.toLong() != pad && score.isFinite() && (best < 0 || score > value)) { best = index; value = score }
        }
        require(best >= 0) { "OPUS-MT returned invalid translation scores" }
        return best.toLong()
    }
}
