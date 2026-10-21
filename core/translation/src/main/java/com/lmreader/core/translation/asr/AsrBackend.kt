package com.lmreader.core.translation.asr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/**
 * Native ASR acceleration backends. [AUTO] prefers a real GPU backend and falls back to CPU
 * only when GPU initialization fails; an explicitly selected backend never falls back.
 */
enum class AsrBackend(
    val wireId: String,
    val label: String,
    val description: String,
) {
    AUTO(
        wireId = "AUTO",
        label = "自动（推荐）",
        description = "优先 Vulkan，其次 OpenCL；GPU 初始化失败时回退 CPU，并在会话中标注实际后端。",
    ),
    CPU(
        wireId = "CPU",
        label = "CPU",
        description = "兼容性最好，始终可用；继续使用下方 CPU 线程数。",
    ),
    VULKAN(
        wireId = "VULKAN",
        label = "Vulkan",
        description = "使用系统 Vulkan 驱动；不可用时明确报错，不会静默改用 CPU。",
    ),
    OPENCL(
        wireId = "OPENCL",
        label = "OpenCL",
        description = "可选后端；仅当构建包含 OpenCL 且设备提供 libOpenCL.so 时可用。",
    ),
    ;

    companion object {
        fun fromWire(value: String?): AsrBackend =
            entries.firstOrNull { it.wireId == value?.trim()?.uppercase(Locale.ROOT) } ?: AUTO
    }
}

data class AsrDeviceCapability(
    val name: String,
    val description: String?,
    val type: String,
    val memoryFreeBytes: Long,
    val memoryTotalBytes: Long,
)

data class AsrBackendCapability(
    val backend: AsrBackend,
    val compiled: Boolean,
    val available: Boolean,
    val devices: List<AsrDeviceCapability>,
    val reason: String?,
)

/** Honest runtime report from the native ASR library. Compiled is not the same as available. */
data class AsrRuntimeCapabilities(val backends: List<AsrBackendCapability>) {
    operator fun get(backend: AsrBackend): AsrBackendCapability? =
        backends.firstOrNull { it.backend == backend }

    fun availableDevice(backend: AsrBackend): AsrDeviceCapability? =
        get(backend)?.takeIf { it.available }?.devices?.firstOrNull()

    companion object {
        private const val MAX_JSON_CHARS = 256 * 1024
        private const val MAX_BACKENDS = 8
        private const val MAX_DEVICES = 32

        fun parse(json: String): AsrRuntimeCapabilities {
            require(json.length <= MAX_JSON_CHARS) { "ASR 后端能力报告过大" }
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }
                .getOrElse { throw IllegalStateException("ASR 后端能力报告不是有效 JSON", it) }
            val items = root["backends"]?.jsonArray
                ?: error("ASR 后端能力报告缺少 backends")
            require(items.size <= MAX_BACKENDS) { "ASR 后端数量异常" }
            val parsed = items.map { element ->
                val item = element.jsonObject
                val backend = AsrBackend.fromWire(item.requiredString("id"))
                require(backend != AsrBackend.AUTO) { "ASR 后端能力报告不能包含 AUTO" }
                val devices = (item["devices"]?.jsonArray ?: emptyList()).map { deviceElement ->
                    val device = deviceElement.jsonObject
                    AsrDeviceCapability(
                        name = device.requiredString("name"),
                        description = device.optionalString("description"),
                        type = device.optionalString("type") ?: "UNKNOWN",
                        memoryFreeBytes = device.optionalLong("memoryFreeBytes") ?: 0L,
                        memoryTotalBytes = device.optionalLong("memoryTotalBytes") ?: 0L,
                    )
                }
                require(devices.size <= MAX_DEVICES) { "ASR 后端设备数量异常" }
                AsrBackendCapability(
                    backend = backend,
                    compiled = item.optionalBoolean("compiled") ?: false,
                    available = item.optionalBoolean("available") ?: false,
                    devices = devices,
                    reason = item.optionalString("reason"),
                )
            }
            require(parsed.map { it.backend }.distinct().size == parsed.size) {
                "ASR 后端能力报告包含重复后端"
            }
            return AsrRuntimeCapabilities(parsed)
        }
    }
}

/** Actual backend used by one loaded native session, including any AUTO fallback reason. */
data class AsrLoadedSessionInfo(
    val requestedBackend: AsrBackend,
    val actualBackend: AsrBackend,
    val device: String?,
    val deviceDescription: String?,
    val fallbackReason: String?,
) {
    val usedCpuFallback: Boolean
        get() = requestedBackend == AsrBackend.AUTO &&
            actualBackend == AsrBackend.CPU &&
            !fallbackReason.isNullOrBlank()

    companion object {
        private const val MAX_JSON_CHARS = 64 * 1024

        fun parse(json: String): AsrLoadedSessionInfo {
            require(json.length <= MAX_JSON_CHARS) { "ASR 会话信息过大" }
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }
                .getOrElse { throw IllegalStateException("ASR 会话信息不是有效 JSON", it) }
            val requested = AsrBackend.fromWire(root.requiredString("requestedBackend"))
            val actual = AsrBackend.fromWire(root.requiredString("actualBackend"))
            require(actual != AsrBackend.AUTO) { "ASR 会话信息缺少实际后端" }
            return AsrLoadedSessionInfo(
                requestedBackend = requested,
                actualBackend = actual,
                device = root.optionalString("device"),
                deviceDescription = root.optionalString("deviceDescription"),
                fallbackReason = root.optionalString("fallbackReason"),
            )
        }
    }
}

/**
 * Identity of one retained native session. The backend and CPU thread count are part of the
 * key, so changing either closes the old context and creates a new one.
 */
data class AsrSessionKey(
    val modelId: String,
    val version: String,
    val sha256: String,
    val backend: AsrBackend,
    val threads: Int,
) {
    val identity: String
        get() = "$modelId@$version/${backend.wireId}/$threads"
}

private fun JsonObject.requiredString(key: String): String =
    optionalString(key) ?: error("ASR JSON 缺少 $key")

private fun JsonObject.optionalString(key: String): String? =
    get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.optionalBoolean(key: String): Boolean? =
    get(key)?.jsonPrimitive?.booleanOrNull

private fun JsonObject.optionalLong(key: String): Long? =
    get(key)?.jsonPrimitive?.longOrNull
