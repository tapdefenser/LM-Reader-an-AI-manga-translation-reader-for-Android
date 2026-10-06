package com.lmreader.core.api

/** Inline attachments are encoded in memory and are never written as translated images. */
data class ApiImage(val mimeType: String, val base64: String) {
    init {
        require(mimeType in setOf("image/jpeg", "image/png", "image/webp"))
        require(base64.isNotBlank() && base64.length <= 24_000_000)
        require(base64.all { it.isLetterOrDigit() || it in "+/=" })
    }
    val dataUrl get() = "data:$mimeType;base64,$base64"
}
data class ApiMessage(val role: String, val text: String, val images: List<ApiImage> = emptyList()) {
    init { require(role in setOf("system", "user", "assistant")) }
}
