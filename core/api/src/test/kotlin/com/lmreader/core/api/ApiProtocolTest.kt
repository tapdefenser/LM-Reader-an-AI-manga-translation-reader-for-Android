package com.lmreader.core.api

import com.lmreader.core.model.*
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.Test

class ApiProtocolTest {
    @Test fun `image lists preserve all attachments and their order in every protocol`() {
        val images = listOf(ApiImage("image/jpeg", "YQ=="), ApiImage("image/png", "Yg=="), ApiImage("image/webp", "Yw=="))
        for (format in ApiFormat.entries) {
            val request = Json.parseToJsonElement(ApiProtocol.requestBody(profile(format), listOf(ApiMessage("user", "read all images", images)))).jsonObject
            val urls = when (format) {
                ApiFormat.CHAT -> request.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray.drop(1)
                    .map { it.jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content }
                ApiFormat.RESPONSES -> request.getValue("input").jsonArray.single().jsonObject.getValue("content").jsonArray.drop(1)
                    .map { it.jsonObject.getValue("image_url").jsonPrimitive.content }
                ApiFormat.GEMINI -> request.getValue("contents").jsonArray.single().jsonObject.getValue("parts").jsonArray.drop(1)
                    .map { it.jsonObject.getValue("inlineData").jsonObject.let { data -> "data:${data.getValue("mimeType").jsonPrimitive.content};base64,${data.getValue("data").jsonPrimitive.content}" } }
            }
            assertEquals(images.map { it.dataUrl }, urls, format.name)
        }
    }

    @Test fun `a request carries every attachment without an image count limit`() {
        val images = (1..64).map { ApiImage("image/jpeg", "YQ==") }
        val message = ApiMessage("user", "read all images", images)
        assertEquals(64, message.images.size)
        val request = Json.parseToJsonElement(ApiProtocol.requestBody(profile(), listOf(message))).jsonObject
        val urls = request.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray.drop(1)
        assertEquals(64, urls.size)
        assertTrue(urls.all { it.jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content == images.first().dataUrl })
    }

    @Test fun `typed messages carry real images and role based examples in all protocols`() {
        val image = ApiImage("image/jpeg", "aGVsbG8=")
        val messages = listOf(ApiMessage("system", "Translate"), ApiMessage("user", "example"), ApiMessage("assistant", "示例"), ApiMessage("user", "read image", listOf(image)))
        val chat = Json.parseToJsonElement(ApiProtocol.requestBody(profile(), messages)).jsonObject
        assertEquals("assistant", chat["messages"]!!.jsonArray[2].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(image.dataUrl, chat["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray.last().jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        val responses = Json.parseToJsonElement(ApiProtocol.requestBody(profile(ApiFormat.RESPONSES), messages)).jsonObject
        assertEquals("output_text", responses["input"]!!.jsonArray[2].jsonObject["content"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(image.dataUrl, responses["input"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray.last().jsonObject["image_url"]!!.jsonPrimitive.content)
        val gemini = Json.parseToJsonElement(ApiProtocol.requestBody(profile(ApiFormat.GEMINI), messages)).jsonObject
        assertNotNull(gemini["systemInstruction"])
        assertEquals("model", gemini["contents"]!!.jsonArray[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(image.base64, gemini["contents"]!!.jsonArray.last().jsonObject["parts"]!!.jsonArray.last().jsonObject["inlineData"]!!.jsonObject["data"]!!.jsonPrimitive.content)
    }
    private fun profile(format: ApiFormat = ApiFormat.CHAT) = ApiProfile("test", ApiProfileKind.LLM, url = "http://localhost:1234/v1", model = "model", format = format)

    @Test fun `base and full endpoints share correct models route`() {
        assertEquals("http://localhost:1234/v1/chat/completions", ApiProtocol.endpoint(profile()).toString())
        assertEquals("http://localhost:1234/v1/models", ApiProtocol.endpoint(profile().copy(url = "http://localhost:1234/v1/chat/completions/"), true).toString())
        assertEquals("http://localhost:1234/v1/responses", ApiProtocol.endpoint(profile(ApiFormat.RESPONSES).copy(url = "http://localhost:1234/v1/responses")).toString())
        assertEquals("http://localhost:1234/chat/completions", ApiProtocol.endpoint(profile().copy(url = "http://localhost:1234")).toString())
    }

    @Test fun `request preserves prompt and only sends configured parameters`() {
        val prompt = "第一行\n\"第二行\" \\ 雪 😀"
        val root = Json.parseToJsonElement(ApiProtocol.requestBody(profile(), prompt)).jsonObject
        assertEquals(prompt, root.getValue("messages").jsonArray.single().jsonObject["content"]?.jsonPrimitive?.content)
        assertTrue(root.getValue("stream").jsonPrimitive.boolean)
        assertNull(root["temperature"])
        assertNull(root["reasoning_effort"])
        val configured = Json.parseToJsonElement(ApiProtocol.requestBody(profile().copy(thinkingEnabled = true, thinkingLevel = ThinkingLevel.HIGH, parameters = AiParameters(temperature = 0.5, maxTokens = 20)), "hello")).jsonObject
        assertEquals("high", configured["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals(20, configured["max_tokens"]?.jsonPrimitive?.int)
    }

    @Test fun `custom JSON overrides tuning but cannot replace request identity`() {
        val p = profile().copy(parameters = AiParameters(temperature = 0.5), customParameters = "{\"temperature\":0.2,\"chat_template_kwargs\":{\"enable_thinking\":false}}")
        val root = Json.parseToJsonElement(ApiProtocol.requestBody(p, "hello")).jsonObject
        assertEquals(0.2, root["temperature"]?.jsonPrimitive?.double)
        assertFalse(root["chat_template_kwargs"]?.jsonObject?.get("enable_thinking")!!.jsonPrimitive.boolean)
        for (field in listOf("model", "messages", "input", "stream", "contents")) {
            assertFailsWith<IllegalArgumentException> { ApiProtocol.customParameters("{\"$field\":false}") }
        }
        assertFailsWith<IllegalArgumentException> { ApiProtocol.customParameters("[]") }
        assertFailsWith<IllegalArgumentException> { ApiProtocol.customParameters("{") }
    }

    @Test fun `responses and Gemini use their own schemas`() {
        val responses = Json.parseToJsonElement(ApiProtocol.requestBody(profile(ApiFormat.RESPONSES).copy(parameters = AiParameters(maxTokens = 7), thinkingEnabled = true), "test")).jsonObject
        assertEquals("test", responses["input"]?.jsonPrimitive?.content)
        assertEquals(7, responses["max_output_tokens"]?.jsonPrimitive?.int)
        assertEquals("medium", responses["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        val geminiProfile = profile(ApiFormat.GEMINI).copy(url = "https://example.com/v1beta", model = "models/gemini-test", thinkingEnabled = true, parameters = AiParameters(topP = 0.7))
        assertEquals("https://example.com/v1beta/models/gemini-test:streamGenerateContent?alt=sse", ApiProtocol.endpoint(geminiProfile).toString())
        val gemini = Json.parseToJsonElement(ApiProtocol.requestBody(geminiProfile, "test")).jsonObject
        assertEquals(0.7, gemini["generationConfig"]?.jsonObject?.get("topP")?.jsonPrimitive?.double)
        assertEquals("MEDIUM", gemini["generationConfig"]?.jsonObject?.get("thinkingConfig")?.jsonObject?.get("thinkingLevel")?.jsonPrimitive?.content)
        assertNull(gemini["model"])
    }

    @Test fun `invalid numeric fields fail before networking`() {
        for (p in listOf(profile().copy(timeoutSeconds = 0), profile().copy(retryCount = -1), profile().copy(parallelLimit = 0), profile().copy(parameters = AiParameters(temperature = Double.NaN)), profile().copy(parameters = AiParameters(topK = 0)))) {
            assertFailsWith<IllegalArgumentException> { ApiProtocol.validate(p) }
        }
        ApiProtocol.validate(profile().copy(model = ""), requireModel = false)
        assertFailsWith<IllegalArgumentException> { ApiProtocol.validate(profile().copy(model = "")) }
    }

    @Test fun `profile codec preserves all editable fields and rejects corrupt documents`() {
        val original = profile().copy(apiKey = "secret", kind = ApiProfileKind.OCR, name = "自定义", parameters = AiParameters(0.2, 0.8, 30, 800, 0.5, -0.2), thinkingEnabled = true, parallelLimit = 3, customParameters = "{\"nested\":{\"flag\":true}}")
        assertEquals(listOf(original), ApiProfileCodec.decode(ApiProfileCodec.encode(listOf(original))))
        assertFalse(original.toString().contains("secret"))
        assertFailsWith<IllegalArgumentException> { ApiProfileCodec.decode("{\"schemaVersion\":99,\"profiles\":[]}") }
        assertFailsWith<IllegalArgumentException> { ApiProfileCodec.decode(ApiProfileCodec.encode(listOf(original, original))) }
    }

    @Test fun `models are unique and Gemini pagination is preserved`() {
        assertEquals(listOf("a", "b"), ApiProtocol.models(ApiFormat.CHAT, "{\"data\":[{\"id\":\"a\"},{\"id\":\"a\"},{\"id\":\"b\"}]}").first)
        val result = ApiProtocol.models(ApiFormat.GEMINI, "{\"models\":[{\"name\":\"models/a\",\"supportedGenerationMethods\":[\"generateContent\"]},{\"name\":\"models/embed\",\"supportedGenerationMethods\":[\"embedContent\"]}],\"nextPageToken\":\"next\"}")
        assertEquals(listOf("a") to "next", result)
    }
}
