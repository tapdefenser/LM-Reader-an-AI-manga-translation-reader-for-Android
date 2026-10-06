package com.lmreader.core.api

import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

object ProjectLinks {
    const val GITHUB = "https://github.com/tapdefenser/LM-Reader-an-AI-manga-translation-reader-for-Android"
    const val RELEASES = "$GITHUB/releases"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/tapdefenser/LM-Reader-an-AI-manga-translation-reader-for-Android/releases/latest"
}

data class ReleaseApk(val name: String, val url: String, val bytes: Long, val sha256: String? = null)

sealed interface UpdateStatus {
    data object NoPublicRelease : UpdateStatus
    data object AccessRestricted : UpdateStatus
    data class Release(val version: String, val page: String, val isNewer: Boolean?,
        val notes: String = "", val apks: List<ReleaseApk> = emptyList()) : UpdateStatus {
        fun apkFor(abis: List<String>): ReleaseApk? = apks.firstOrNull {
            it.name.contains("universal", true) ||
                (abis.any { abi -> abi == "arm64-v8a" || abi == "x86_64" } && it.name.endsWith("-64bit.apk", true))
        }
            ?: abis.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.name.contains(abi, true) } }
            ?: apks.singleOrNull { apk -> listOf("arm64", "armeabi", "x86", "32bit", "64bit").none { apk.name.contains(it, true) } }
    }
}

/** Reads public stable releases only; never uses translation-provider credentials. */
class GitHubUpdates(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build(),
    private val endpoint: HttpUrl = ProjectLinks.LATEST_RELEASE_API.toHttpUrl(),
) {
    suspend fun check(currentVersion: String): UpdateStatus = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(endpoint)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10")
            .header("User-Agent", "LM-Reader/$currentVersion")
            .build()
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    // Parse on the network callback while the cancellable wait still owns this Call.
                    val result = response.use { parse(it, currentVersion) }
                    continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun parse(response: Response, currentVersion: String): UpdateStatus {
        when (response.code) {
            404 -> return UpdateStatus.NoPublicRelease
            403, 429 -> return UpdateStatus.AccessRestricted
        }
        if (!response.isSuccessful) throw IOException("Release request failed: HTTP ${response.code}")
        val body = response.body ?: throw IOException("Missing release response")
        val bytes = body.source().readByteArray(when {
            body.contentLength() in 0..MAX_RESPONSE -> body.contentLength()
            body.contentLength() > MAX_RESPONSE -> throw IOException("Release response is too large")
            else -> return parseUnknownLength(body.source(), currentVersion)
        })
        return parseJson(bytes.toString(Charsets.UTF_8), currentVersion)
    }

    private fun parseUnknownLength(source: okio.BufferedSource, currentVersion: String): UpdateStatus {
        val buffer = okio.Buffer()
        while (!source.exhausted()) {
            source.read(buffer, minOf(8192, MAX_RESPONSE + 1 - buffer.size))
            if (buffer.size > MAX_RESPONSE) throw IOException("Release response is too large")
        }
        return parseJson(buffer.readUtf8(), currentVersion)
    }

    private fun parseJson(text: String, currentVersion: String): UpdateStatus {
        val json = Json.parseToJsonElement(text).jsonObject
        if (json["draft"]?.jsonPrimitive?.booleanOrNull == true ||
            json["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return UpdateStatus.NoPublicRelease
        val tag = json["tag_name"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && it.length <= 100 } ?: throw IOException("Missing release tag")
        val latest = ReleaseVersion.parse(tag)
        val current = ReleaseVersion.parse(currentVersion)
        val newer = if (latest != null && current != null) latest > current else null
        // Build the destination from our repository and an encoded tag, not server-supplied URLs.
        val page = ProjectLinks.RELEASES.toHttpUrl().newBuilder().addPathSegment("tag").addPathSegment(tag).build().toString()
        val notes = json["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val apks = json["assets"]?.jsonArray.orEmpty().mapNotNull { element ->
            val asset = element.jsonObject
            val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if(!name.startsWith("LM-Reader", true) || !name.endsWith(".apk", true) ||
                name.any { it == '/' || it == '\\' || it.isISOControl() }) return@mapNotNull null
            if(asset["state"]?.jsonPrimitive?.contentOrNull != "uploaded") return@mapNotNull null
            val size = asset["size"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
            val url = ProjectLinks.RELEASES.toHttpUrl().newBuilder().addPathSegment("download")
                .addPathSegment(tag).addPathSegment(name).build().toString()
            // Only an asset on this release in our own repository can become an install candidate.
            if(asset["browser_download_url"]?.jsonPrimitive?.contentOrNull?.toHttpUrlOrNull()?.toString() != url) return@mapNotNull null
            val digest = asset["digest"]?.jsonPrimitive?.contentOrNull?.removePrefix("sha256:")
                ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }?.lowercase()
            ReleaseApk(name, url, size, digest)
        }
        return UpdateStatus.Release(tag, page, newer, notes, apks)
    }

    private companion object { const val MAX_RESPONSE = 512L * 1024 }
}

internal data class ReleaseVersion(val numbers: List<Int>, val preview: List<String>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        numbers.zip(other.numbers).forEach { (left, right) ->
            if (left != right) return left.compareTo(right)
        }
        if (preview.isEmpty() || other.preview.isEmpty()) return when {
            preview.isEmpty() && other.preview.isEmpty() -> 0
            preview.isEmpty() -> 1
            else -> -1
        }
        preview.zip(other.preview).forEach { (left, right) ->
            val leftNumber = left.all(Char::isDigit)
            val rightNumber = right.all(Char::isDigit)
            val comparison = when {
                leftNumber && rightNumber -> BigInteger(left).compareTo(BigInteger(right))
                leftNumber -> -1
                rightNumber -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return preview.size.compareTo(other.preview.size)
    }

    companion object {
        private val pattern = Regex("^[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
        fun parse(value: String): ReleaseVersion? {
            val match = pattern.matchEntire(value.trim()) ?: return null
            val numbers = (1..3).map { match.groupValues[it].toIntOrNull() ?: return null }
            val preview = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.') ?: emptyList()
            if (preview.any { it.length > 1 && it.startsWith('0') && it.all(Char::isDigit) }) return null
            return ReleaseVersion(numbers, preview)
        }
    }
}
