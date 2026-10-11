#include <jni.h>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <atomic>
#include "translator/parser.h"
#include "translator/service.h"
#include "common/logging.h"

namespace {
using namespace marian::bergamot;
std::mutex guard;
std::atomic<int> activeInference{0};
std::atomic<int> peakInference{0};
struct InferenceScope {
  InferenceScope() {
    int count = ++activeInference;
    int peak = peakInference.load();
    while (count > peak && !peakInference.compare_exchange_weak(peak, count)) {}
  }
  ~InferenceScope() { --activeInference; }
};
// Each key owns a decoder and its workspace. Registry ownership is independent
// of inference lifetime, so unloading an idle key never interrupts another key.
struct Session {
  std::mutex mutex;
  std::shared_ptr<TranslationModel> model;
  size_t requestId = 0;
  std::string translate(std::string input) {
    InferenceScope inference;
    ResponseOptions options;
    options.HTML = false;
    options.alignment = false;
    options.qualityScores = false;
    options.sentenceMappings = false;
    Response result;
    std::optional<TranslationCache> cache;
    AggregateBatchingPool pool;
    auto request = model->makeRequest(requestId++, std::move(input),
        [&result](Response&& response) { result = std::move(response); }, options, cache);
    pool.enqueueRequest(model, request);
    Batch batch;
    marian::Ptr<TranslationModel> next;
    while (pool.generateBatch(next, batch)) next->translateBatch(0, batch);
    return result.target.text;
  }
};
std::unordered_map<std::string, std::shared_ptr<Session>> models;
std::once_flag initialize;
std::unique_ptr<marian::bergamot::Logger> logger;

std::string bytes(JNIEnv* env, jbyteArray value) {
  const auto count = env->GetArrayLength(value);
  std::string result(count, '\0');
  env->GetByteArrayRegion(value, 0, count, reinterpret_cast<jbyte*>(result.data()));
  return result;
}
void fail(JNIEnv* env, const std::exception& error) {
  // Error messages are ASCII diagnostics. Text payloads always use UTF-8 byte arrays.
  env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_lmreader_core_translation_BergamotNative_load(JNIEnv* env, jobject, jbyteArray key, jbyteArray config) {
  try {
    std::call_once(initialize, [] {
      marian::setThrowExceptionOnAbort(true);
      logger = std::make_unique<marian::bergamot::Logger>(marian::bergamot::Logger::Config{});
    });
    const auto id = bytes(env, key);
    { std::lock_guard<std::mutex> lock(guard); if (models.count(id)) return; }
    auto session = std::make_shared<Session>();
    auto options = parseOptionsFromString(bytes(env, config), true, "");
    session->model = std::make_shared<TranslationModel>(options);
    // TranslationModel loads its backend lazily. Warm the actual decoder now,
    // rather than reporting a ready engine with only vocabulary files loaded.
    session->translate("Warm up.");
    std::lock_guard<std::mutex> lock(guard);
    models.emplace(id, std::move(session));
  } catch (const std::exception& error) { fail(env, error); }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_lmreader_core_translation_BergamotNative_translate(JNIEnv* env, jobject, jbyteArray key, jbyteArray input) {
  try {
    std::shared_ptr<Session> session;
    { std::lock_guard<std::mutex> lock(guard); session = models.at(bytes(env, key)); }
    std::lock_guard<std::mutex> lock(session->mutex);
    const auto output = session->translate(bytes(env, input));
    auto array = env->NewByteArray(static_cast<jsize>(output.size()));
    if (array) env->SetByteArrayRegion(array, 0, static_cast<jsize>(output.size()), reinterpret_cast<const jbyte*>(output.data()));
    return array;
  } catch (const std::exception& error) { fail(env, error); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_lmreader_core_translation_BergamotNative_unload(JNIEnv* env, jobject, jbyteArray key) {
  std::lock_guard<std::mutex> lock(guard);
  models.erase(bytes(env, key));
}

extern "C" JNIEXPORT void JNICALL
Java_com_lmreader_core_translation_BergamotNative_release(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> lock(guard);
  models.clear();
}

// High-water mark of actual decoder calls, including warm-up. This diagnoses
// resource allocation without measuring coroutine starts or device-dependent speed.
extern "C" JNIEXPORT jint JNICALL
Java_com_lmreader_core_translation_BergamotNative_maximumConcurrency(JNIEnv*, jobject, jboolean reset) {
  return reset ? peakInference.exchange(activeInference.load()) : peakInference.load();
}
