#include <jni.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <stdexcept>
#include <unordered_map>
#include <vector>
#include "sentencepiece_processor.h"

namespace {
struct Codec { sentencepiece::SentencePieceProcessor source, target; };
std::mutex mutex;
std::atomic<jlong> sequence{1};
std::unordered_map<jlong, std::shared_ptr<Codec>> codecs;
std::string bytes(JNIEnv* env, jbyteArray value) {
  std::string text(env->GetArrayLength(value), '\0');
  env->GetByteArrayRegion(value, 0, text.size(), reinterpret_cast<jbyte*>(text.data()));
  return text;
}
jbyteArray array(JNIEnv* env, const std::string& text) {
  auto result = env->NewByteArray(text.size());
  env->SetByteArrayRegion(result, 0, text.size(), reinterpret_cast<const jbyte*>(text.data()));
  return result;
}
void check(const sentencepiece::util::Status& status) {
  if (!status.ok()) throw std::runtime_error(status.ToString());
}
std::shared_ptr<Codec> get(jlong id) {
  std::lock_guard<std::mutex> lock(mutex);
  const auto it = codecs.find(id);
  if (it == codecs.end()) throw std::runtime_error("Tokenizer is closed");
  return it->second;
}
void fail(JNIEnv* env, const std::exception& error) {
  env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_lmreader_core_translation_MarianTokenizerNative_open(JNIEnv* env, jobject, jbyteArray source, jbyteArray target) {
  try {
    auto codec = std::make_shared<Codec>();
    check(codec->source.Load(bytes(env, source))); check(codec->target.Load(bytes(env, target)));
    const auto id = sequence++;
    std::lock_guard<std::mutex> lock(mutex); codecs[id] = std::move(codec); return id;
  } catch (const std::exception& error) { fail(env, error); return 0; }
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_lmreader_core_translation_MarianTokenizerNative_encode(JNIEnv* env, jobject, jlong id, jbyteArray text) {
  try {
    std::vector<std::string> pieces; check(get(id)->source.Encode(bytes(env, text), &pieces));
    auto result = env->NewObjectArray(pieces.size(), env->FindClass("[B"), nullptr);
    for (size_t i = 0; i < pieces.size(); ++i) {
      auto value = array(env, pieces[i]); env->SetObjectArrayElement(result, i, value); env->DeleteLocalRef(value);
    }
    return result;
  } catch (const std::exception& error) { fail(env, error); return nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_lmreader_core_translation_MarianTokenizerNative_decode(JNIEnv* env, jobject, jlong id, jobjectArray input) {
  try {
    std::vector<std::string> pieces;
    for (jsize i = 0; i < env->GetArrayLength(input); ++i) {
      auto piece = static_cast<jbyteArray>(env->GetObjectArrayElement(input, i));
      pieces.push_back(bytes(env, piece)); env->DeleteLocalRef(piece);
    }
    std::string text; check(get(id)->target.Decode(pieces, &text)); return array(env, text);
  } catch (const std::exception& error) { fail(env, error); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_lmreader_core_translation_MarianTokenizerNative_close(JNIEnv*, jobject, jlong id) {
  std::lock_guard<std::mutex> lock(mutex); codecs.erase(id);
}
