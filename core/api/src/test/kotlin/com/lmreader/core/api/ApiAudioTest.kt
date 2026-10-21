package com.lmreader.core.api

import com.lmreader.core.model.*
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.Test

class ApiAudioTest {
    private val wav = "UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA="

    @Test fun `chat sends input audio parts alongside text and images`() {
        val content = body(ApiFormat.CHAT, ApiAudio("audio/wav", wav))
            .getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray
        assertEquals("text", content[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject.getValue("type").jsonPrimitive.content)
        val audio = content[2].jsonObject
        assertEquals("input_audio", audio.getValue("type").jsonPrimitive.content)
        assertEquals(wav, audio.getValue("input_audio").jsonObject.getValue("data").jsonPrimitive.content)
        assertEquals("wav", audio.getValue("input_audio").jsonObject.getValue("format").jsonPrimitive.content)
    }

    @Test fun `gemini sends wav inline data and normalizes the alias`() {
        val parts = body(ApiFormat.GEMINI, ApiAudio("audio/x-wav", wav))
            .getValue("contents").jsonArray.single().jsonObject.getValue("parts").jsonArray
        assertEquals("text", parts[0].jsonObject.keys.single())
        val inline = parts.mapNotNull { it.jsonObject["inlineData"]?.jsonObject }
            .single { it.getValue("mimeType").jsonPrimitive.content == "audio/wav" }
        assertEquals(wav, inline.getValue("data").jsonPrimitive.content)
        assertEquals(2, parts.count { it.jsonObject.containsKey("inlineData") })
    }

    @Test fun `responses rejects audio with an actionable message`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            ApiProtocol.requestBody(profile(ApiFormat.RESPONSES),
                listOf(ApiMessage("user", "x", audios = listOf(ApiAudio("audio/wav", wav)))))
        }
        assertTrue(failure.message.orEmpty().contains("Responses"))
        assertTrue(failure.message.orEmpty().contains("Chat Completions"))
    }

    @Test fun `audio only belongs to user messages`() {
        assertFailsWith<IllegalArgumentException> { body(ApiFormat.CHAT, ApiAudio("audio/wav", wav), role = "assistant") }
    }

    @Test fun `audio bytes are redacted from request logs including short clips`() {
        val request = ApiProtocol.requestBody(profile(ApiFormat.CHAT), listOf(ApiMessage("user", "listen",
            audios = listOf(ApiAudio("audio/wav", wav)))))
        val logged = ApiLogPayload.sanitize(request)
        assertFalse(logged.contains(wav))
        assertTrue(logged.contains("音频附件"))
    }

    @Test fun `invalid audio payloads and mime types are rejected`() {
        assertFailsWith<IllegalArgumentException> { ApiAudio("audio/mpeg", wav) }
        assertFailsWith<IllegalArgumentException> { ApiAudio("audio/wav", "not base64!") }
        assertFailsWith<IllegalArgumentException> { ApiAudio("audio/wav", "A".repeat(24_000_001)) }
        assertFailsWith<IllegalArgumentException> { ApiAudio("audio/wav", "") }
    }

    private fun body(format: ApiFormat, audio: ApiAudio, role: String = "user"): JsonObject {
        val image = ApiImage("image/png", "YQ==")
        return Json.parseToJsonElement(ApiProtocol.requestBody(profile(format),
            listOf(ApiMessage(role, "listen", images = if (role == "user") listOf(image) else emptyList(),
                audios = listOf(audio))))).jsonObject
    }

    private fun profile(format: ApiFormat) = ApiProfile("test", ApiProfileKind.LLM,
        url = "http://localhost:1234/v1", model = "model", format = format)
}
