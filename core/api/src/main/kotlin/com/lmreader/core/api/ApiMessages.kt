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

/** Only WAV clips are accepted; the wire format stays "wav" even when the alias audio/x-wav is supplied. */
data class ApiAudio(val mimeType: String, val base64: String) {
    init {
        require(mimeType in setOf("audio/wav", "audio/x-wav")) { "音频附件只支持 WAV" }
        require(base64.isNotBlank() && base64.length <= MAX_BASE64_LENGTH) { "音频附件大小必须在 24 MB 以内" }
        val padding = base64.takeLastWhile { it == '=' }.length
        require(base64.length % 4 == 0 && padding <= 2 && base64.dropLast(padding).all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/'
        }) { "音频附件必须是有效的 base64" }
    }
    val format get() = "wav"
    val normalizedMimeType get() = "audio/wav"
    private companion object { const val MAX_BASE64_LENGTH = 24_000_000 }
}

data class ApiMessage(val role: String, val text: String, val images: List<ApiImage> = emptyList(),
    val audios: List<ApiAudio> = emptyList()) {
    init { require(role in setOf("system", "user", "assistant")) }
}
