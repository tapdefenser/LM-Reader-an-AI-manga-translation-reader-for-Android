package com.lmreader.tasks

import kotlinx.coroutines.CancellationException
import com.lmreader.ui.navigation.Routes

/** Persisted waiting reasons identify the missing capability, never the task's subtitle source. */
enum class TaskCapability(val reason: String) {
    ASR("CAPABILITY_ASR"), TRANSLATION("CAPABILITY_TRANSLATION"),
    VISION("CAPABILITY_VISION"), API("CAPABILITY_API");

    companion object {
        fun fromReason(reason: String?): TaskCapability? = entries.firstOrNull { it.reason == reason }
    }
}

internal fun isCapabilityWaitingReason(reason: String?): Boolean =
    reason in setOf("CAPABILITY", "API_ACCESS") || TaskCapability.fromReason(reason) != null

internal fun capabilitySettingsRoute(reason: String?): String? = when (TaskCapability.fromReason(reason)) {
    TaskCapability.ASR -> Routes.SETTINGS_API_ASR
    TaskCapability.TRANSLATION -> Routes.SETTINGS_API_LOCAL
    TaskCapability.VISION -> Routes.SETTINGS_API_OCR_LOCAL
    TaskCapability.API -> Routes.SETTINGS_API
    null -> Routes.SETTINGS_API.takeIf { isCapabilityWaitingReason(reason) }
}

internal suspend fun <T> requireTaskCapability(capability: TaskCapability, label: String? = null,
    action: suspend () -> T): T = try {
    action()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (waiting: TaskWaiting) {
    throw waiting
} catch (error: Exception) {
    throw TaskWaiting(capability.reason,
        listOfNotNull(label, error.message ?: error.javaClass.simpleName).joinToString("："), error)
}
