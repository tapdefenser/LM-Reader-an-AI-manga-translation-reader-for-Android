#include <jni.h>

#include <whisper.h>

#include "asr_backend.h"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <exception>
#include <fstream>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

constexpr jsize kSampleRate = 16000;
constexpr jsize kMaxSamples = 35 * kSampleRate;
constexpr std::size_t kMaxJsonBytes = 8 * 1024 * 1024;
constexpr int kMaxSegments = 100000;

struct Context {
    Context() = default;
    ~Context() {
        if (ctx != nullptr) {
            whisper_free(ctx);
        }
    }

    Context(const Context&) = delete;
    Context& operator=(const Context&) = delete;

    whisper_context* ctx = nullptr;
    std::string backendInfoJson;
    std::mutex inference;
    std::atomic<bool> abortRequested{false};
};

std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<Context>> registry;
std::uint64_t nextHandle = 1;

void throwNew(JNIEnv* env, const char* className, const char* message) {
    if (env->ExceptionCheck()) {
        return;
    }
    jclass clazz = env->FindClass(className);
    if (clazz == nullptr) {
        return;
    }
    env->ThrowNew(clazz, message);
    env->DeleteLocalRef(clazz);
}

void reportException(JNIEnv* env, std::exception_ptr failure) {
    try {
        std::rethrow_exception(std::move(failure));
    } catch (const std::bad_alloc&) {
        throwNew(env, "java/lang/OutOfMemoryError", "Not enough memory for ASR");
    } catch (const std::invalid_argument& error) {
        throwNew(env, "java/lang/IllegalArgumentException", error.what());
    } catch (const std::exception& error) {
        throwNew(env, "java/lang/IllegalStateException", error.what());
    } catch (...) {
        throwNew(env, "java/lang/IllegalStateException", "ASR failure");
    }
}

std::shared_ptr<Context> findContext(jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    const auto found = registry.find(handle);
    return found == registry.end() ? std::shared_ptr<Context>() : found->second;
}

std::shared_ptr<Context> removeContext(jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    const auto found = registry.find(handle);
    if (found == registry.end()) {
        return std::shared_ptr<Context>();
    }
    std::shared_ptr<Context> context = std::move(found->second);
    registry.erase(found);
    return context;
}

jlong addContext(const std::shared_ptr<Context>& context) {
    std::lock_guard<std::mutex> lock(registryMutex);
    const std::uint64_t maxHandle =
        static_cast<std::uint64_t>(std::numeric_limits<jlong>::max());
    for (;;) {
        const std::uint64_t candidate = nextHandle;
        nextHandle = candidate >= maxHandle ? 1 : candidate + 1;
        if (candidate == 0) {
            continue;
        }
        const jlong handle = static_cast<jlong>(candidate);
        if (registry.find(handle) == registry.end()) {
            registry.emplace(handle, context);
            return handle;
        }
    }
}

void appendUtf8(std::string& out, std::uint32_t codePoint) {
    if (codePoint <= 0x7F) {
        out.push_back(static_cast<char>(codePoint));
    } else if (codePoint <= 0x7FF) {
        out.push_back(static_cast<char>(0xC0 | (codePoint >> 6)));
        out.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
    } else if (codePoint <= 0xFFFF) {
        out.push_back(static_cast<char>(0xE0 | (codePoint >> 12)));
        out.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (codePoint >> 18)));
        out.push_back(static_cast<char>(0x80 | ((codePoint >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
    }
}

std::string stringFromJava(JNIEnv* env, jstring value, jsize maxChars) {
    if (value == nullptr) {
        throw std::invalid_argument("Text argument is required");
    }
    const jsize length = env->GetStringLength(value);
    if (length < 0 || length > maxChars) {
        throw std::invalid_argument("Text argument is too long");
    }
    std::vector<jchar> units(static_cast<std::size_t>(length));
    if (length > 0) {
        env->GetStringRegion(value, 0, length, units.data());
        if (env->ExceptionCheck()) {
            throw std::runtime_error("Unable to read text argument");
        }
    }
    std::string result;
    result.reserve(static_cast<std::size_t>(length));
    for (jsize i = 0; i < length; ++i) {
        std::uint32_t codePoint = static_cast<std::uint32_t>(units[static_cast<std::size_t>(i)]);
        if (codePoint >= 0xD800 && codePoint <= 0xDBFF) {
            if (i + 1 < length) {
                const std::uint32_t low =
                    static_cast<std::uint32_t>(units[static_cast<std::size_t>(i + 1)]);
                if (low >= 0xDC00 && low <= 0xDFFF) {
                    codePoint = 0x10000 + ((codePoint - 0xD800) << 10) + (low - 0xDC00);
                    ++i;
                } else {
                    codePoint = 0xFFFD;
                }
            } else {
                codePoint = 0xFFFD;
            }
        } else if (codePoint >= 0xDC00 && codePoint <= 0xDFFF) {
            codePoint = 0xFFFD;
        }
        appendUtf8(result, codePoint);
    }
    return result;
}

bool isAsciiSpace(unsigned char value) {
    return value == ' ' || (value >= 0x09 && value <= 0x0D);
}

std::string trimAscii(const std::string& value) {
    std::size_t begin = 0;
    std::size_t end = value.size();
    while (begin < end && isAsciiSpace(static_cast<unsigned char>(value[begin]))) {
        ++begin;
    }
    while (end > begin && isAsciiSpace(static_cast<unsigned char>(value[end - 1]))) {
        --end;
    }
    return value.substr(begin, end - begin);
}

std::string normalizeLanguage(const std::string& input) {
    std::string value = trimAscii(input);
    if (value.empty()) {
        throw std::invalid_argument("Language is required");
    }
    for (char& character : value) {
        if (character >= 'A' && character <= 'Z') {
            character = static_cast<char>(character - 'A' + 'a');
        } else if (character == '_') {
            character = '-';
        }
    }
    if (value == "auto") {
        return value;
    }
    const std::size_t separator = value.find('-');
    const std::string base = value.substr(0, separator);
    if (base.size() < 2 || base.size() > 3) {
        throw std::invalid_argument("Unsupported ASR language");
    }
    for (char character : base) {
        if (character < 'a' || character > 'z') {
            throw std::invalid_argument("Unsupported ASR language");
        }
    }
    if (whisper_lang_id(base.c_str()) < 0) {
        throw std::invalid_argument("Unsupported ASR language");
    }
    return base;
}

void appendUnicodeEscape(std::string& out, std::uint32_t codePoint) {
    static const char hex[] = "0123456789abcdef";
    char escaped[6] = {'\\', 'u',
                       hex[(codePoint >> 12) & 0xF], hex[(codePoint >> 8) & 0xF],
                       hex[(codePoint >> 4) & 0xF], hex[codePoint & 0xF]};
    out.append(escaped, sizeof(escaped));
}

std::size_t decodeUtf8(const char* data, std::size_t remaining, std::uint32_t* codePoint) {
    const unsigned char* bytes = reinterpret_cast<const unsigned char*>(data);
    const unsigned char first = bytes[0];
    std::size_t length = 0;
    std::uint32_t value = 0;
    if (first < 0x80) {
        *codePoint = first;
        return 1;
    }
    if ((first & 0xE0) == 0xC0) {
        length = 2;
        value = first & 0x1F;
    } else if ((first & 0xF0) == 0xE0) {
        length = 3;
        value = first & 0x0F;
    } else if ((first & 0xF8) == 0xF0) {
        length = 4;
        value = first & 0x07;
    } else {
        return 0;
    }
    if (remaining < length) {
        return 0;
    }
    for (std::size_t i = 1; i < length; ++i) {
        const unsigned char next = bytes[i];
        if ((next & 0xC0) != 0x80) {
            return 0;
        }
        value = (value << 6) | (next & 0x3F);
    }
    if ((length == 2 && value < 0x80) ||
        (length == 3 && value < 0x800) ||
        (length == 4 && value < 0x10000) ||
        value > 0x10FFFF ||
        (value >= 0xD800 && value <= 0xDFFF)) {
        return 0;
    }
    *codePoint = value;
    return length;
}

void appendJsonString(std::string& out, const char* data, std::size_t length) {
    std::size_t i = 0;
    while (i < length) {
        const unsigned char character = static_cast<unsigned char>(data[i]);
        if (character == '"') {
            out += "\\\"";
            ++i;
        } else if (character == '\\') {
            out += "\\\\";
            ++i;
        } else if (character < 0x20 || character == 0x7F) {
            appendUnicodeEscape(out, character);
            ++i;
        } else if (character < 0x80) {
            out.push_back(static_cast<char>(character));
            ++i;
        } else {
            std::uint32_t codePoint = 0;
            const std::size_t decoded = decodeUtf8(data + i, length - i, &codePoint);
            if (decoded == 0) {
                out += "\\ufffd";
                ++i;
            } else if (codePoint >= 0x80 && codePoint <= 0x9F) {
                appendUnicodeEscape(out, codePoint);
                i += decoded;
            } else {
                out.append(data + i, decoded);
                i += decoded;
            }
        }
    }
}

std::string jsonFromSegments(whisper_context* context) {
    const int segmentCount = whisper_full_n_segments(context);
    if (segmentCount < 0) {
        throw std::runtime_error("Unable to read transcription");
    }
    if (segmentCount > kMaxSegments) {
        throw std::runtime_error("Transcription output is too large");
    }
    constexpr std::int64_t kMaxCentiseconds =
        std::numeric_limits<std::int64_t>::max() / 10;

    std::string json;
    json.reserve(64 + static_cast<std::size_t>(segmentCount) * 64);
    json += "{\"segments\":[";
    bool first = true;
    for (int i = 0; i < segmentCount; ++i) {
        const char* text = whisper_full_get_segment_text(context, i);
        if (text == nullptr) {
            continue;
        }
        std::size_t begin = 0;
        std::size_t end = std::strlen(text);
        while (begin < end && isAsciiSpace(static_cast<unsigned char>(text[begin]))) {
            ++begin;
        }
        while (end > begin && isAsciiSpace(static_cast<unsigned char>(text[end - 1]))) {
            --end;
        }
        if (begin == end) {
            continue;
        }
        const std::int64_t t0 = whisper_full_get_segment_t0(context, i);
        const std::int64_t t1 = whisper_full_get_segment_t1(context, i);
        if (t0 < 0 || t1 < t0 || t0 > kMaxCentiseconds || t1 > kMaxCentiseconds) {
            continue;
        }
        if (!first) {
            json += ',';
        }
        first = false;
        json += "{\"startMs\":";
        json += std::to_string(t0 * 10);
        json += ",\"endMs\":";
        json += std::to_string(t1 * 10);
        json += ",\"text\":\"";
        appendJsonString(json, text + begin, end - begin);
        json += "\"}";
        if (json.size() > kMaxJsonBytes) {
            throw std::runtime_error("Transcription output is too large");
        }
    }
    json += "]}";
    if (json.size() > kMaxJsonBytes) {
        throw std::runtime_error("Transcription output is too large");
    }
    return json;
}

jstring stringToJava(JNIEnv* env, const std::string& value) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) {
        return nullptr;
    }
    jclass charsetClass = env->FindClass("java/nio/charset/StandardCharsets");
    if (charsetClass == nullptr) {
        env->DeleteLocalRef(stringClass);
        return nullptr;
    }
    const jmethodID constructor =
        env->GetMethodID(stringClass, "<init>", "([BLjava/nio/charset/Charset;)V");
    const jfieldID utf8Field =
        env->GetStaticFieldID(charsetClass, "UTF_8", "Ljava/nio/charset/Charset;");
    if (constructor == nullptr || utf8Field == nullptr) {
        env->DeleteLocalRef(charsetClass);
        env->DeleteLocalRef(stringClass);
        return nullptr;
    }
    jobject utf8 = env->GetStaticObjectField(charsetClass, utf8Field);
    if (utf8 == nullptr) {
        env->DeleteLocalRef(charsetClass);
        env->DeleteLocalRef(stringClass);
        return nullptr;
    }
    const jsize size = static_cast<jsize>(value.size());
    jbyteArray bytes = env->NewByteArray(size);
    if (bytes == nullptr) {
        env->DeleteLocalRef(utf8);
        env->DeleteLocalRef(charsetClass);
        env->DeleteLocalRef(stringClass);
        return nullptr;
    }
    if (size > 0) {
        env->SetByteArrayRegion(bytes, 0, size,
                                reinterpret_cast<const jbyte*>(value.data()));
    }
    jstring result = static_cast<jstring>(env->NewObject(stringClass, constructor, bytes, utf8));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(utf8);
    env->DeleteLocalRef(charsetClass);
    env->DeleteLocalRef(stringClass);
    return result;
}

bool abortCallback(void* userData) {
    auto* context = static_cast<Context*>(userData);
    return context->abortRequested.load(std::memory_order_relaxed);
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_lmreader_core_translation_asr_WhisperNative_capabilities(JNIEnv* env, jobject) {
    try {
        return stringToJava(env, lmreader_asr::capabilitiesJson());
    } catch (...) {
        reportException(env, std::current_exception());
    }
    return nullptr;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lmreader_core_translation_asr_WhisperNative_create(
    JNIEnv* env, jobject, jstring modelPath, jstring backend) {
    try {
        const std::string path = stringFromJava(env, modelPath, 4096);
        if (path.empty() || path.size() > 4096 || path.find('\0') != std::string::npos) {
            throw std::invalid_argument("Model path is required");
        }
        std::ifstream modelFile(path, std::ios::binary);
        if (!modelFile.is_open()) {
            throw std::invalid_argument("Model file is not readable");
        }
        modelFile.close();

        const lmreader_asr::Backend requested =
            lmreader_asr::parseBackendOrThrow(stringFromJava(env, backend, 16));
        lmreader_asr::CreateResult created = lmreader_asr::create(path, requested);
        if (created.context == nullptr) {
            throw std::runtime_error("Unable to load ASR model");
        }
        std::unique_ptr<whisper_context, decltype(&whisper_free)> owner(
            created.context, &whisper_free);
        auto context = std::make_shared<Context>();
        context->ctx = created.context;
        owner.release();
        context->backendInfoJson = created.info.toJson();
        return addContext(context);
    } catch (...) {
        reportException(env, std::current_exception());
    }
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lmreader_core_translation_asr_WhisperNative_describe(JNIEnv* env, jobject, jlong handle) {
    try {
        const std::shared_ptr<Context> context = findContext(handle);
        if (!context) {
            throw std::invalid_argument("Invalid ASR handle");
        }
        return stringToJava(env, context->backendInfoJson);
    } catch (...) {
        reportException(env, std::current_exception());
    }
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lmreader_core_translation_asr_WhisperNative_transcribe(
    JNIEnv* env, jobject, jlong handle, jfloatArray samples, jstring language, jint threads) {
    try {
        const std::shared_ptr<Context> context = findContext(handle);
        if (!context) {
            throw std::invalid_argument("Invalid ASR handle");
        }

        std::lock_guard<std::mutex> inferenceLock(context->inference);
        context->abortRequested.store(false, std::memory_order_relaxed);

        if (samples == nullptr) {
            throw std::invalid_argument("Audio samples are required");
        }
        const jsize sampleCount = env->GetArrayLength(samples);
        if (sampleCount <= 0) {
            throw std::invalid_argument("Audio samples are empty");
        }
        if (sampleCount > kMaxSamples) {
            throw std::invalid_argument("Audio exceeds 35 seconds");
        }
        if (threads < 1 || threads > 4) {
            throw std::invalid_argument("Threads must be between 1 and 4");
        }
        const std::string normalizedLanguage =
            normalizeLanguage(stringFromJava(env, language, 64));

        std::vector<float> pcm(static_cast<std::size_t>(sampleCount));
        env->GetFloatArrayRegion(samples, 0, sampleCount, pcm.data());
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        if (context->abortRequested.load(std::memory_order_relaxed)) {
            throw std::runtime_error("Transcription aborted");
        }

        whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.strategy = WHISPER_SAMPLING_GREEDY;
        params.n_threads = threads;
        params.translate = false;
        params.no_context = true;
        params.no_timestamps = false;
        params.single_segment = false;
        params.print_special = false;
        params.print_progress = false;
        params.print_realtime = false;
        params.print_timestamps = false;
        params.token_timestamps = false;
        params.language = normalizedLanguage == "auto" ? nullptr : normalizedLanguage.c_str();
        params.detect_language = false;
        params.abort_callback = abortCallback;
        params.abort_callback_user_data = context.get();

        const int result = whisper_full(context->ctx, params, pcm.data(),
                                        static_cast<int>(sampleCount));
        if (result != 0) {
            if (context->abortRequested.load(std::memory_order_relaxed)) {
                throw std::runtime_error("Transcription aborted");
            }
            throw std::runtime_error("Transcription failed");
        }

        return stringToJava(env, jsonFromSegments(context->ctx));
    } catch (...) {
        reportException(env, std::current_exception());
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_lmreader_core_translation_asr_WhisperNative_abort(JNIEnv* env, jobject, jlong handle) {
    try {
        const std::shared_ptr<Context> context = findContext(handle);
        if (!context) {
            throw std::invalid_argument("Invalid ASR handle");
        }
        context->abortRequested.store(true, std::memory_order_relaxed);
    } catch (...) {
        reportException(env, std::current_exception());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_lmreader_core_translation_asr_WhisperNative_release(JNIEnv* env, jobject, jlong handle) {
    try {
        const std::shared_ptr<Context> context = removeContext(handle);
        if (!context) {
            throw std::invalid_argument("Invalid ASR handle");
        }
        // The shared_ptr above, plus any in-flight transcribe call, owns the
        // context until whisper_full returns. The destructor frees whisper
        // only after the last owner is gone.
    } catch (...) {
        reportException(env, std::current_exception());
    }
}
