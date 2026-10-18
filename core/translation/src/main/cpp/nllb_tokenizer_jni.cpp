#include <jni.h>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include "sentencepiece_processor.h"

namespace {
std::string bytes(JNIEnv* env, jbyteArray value) {
  std::string result(env->GetArrayLength(value), '\0');
  env->GetByteArrayRegion(value, 0, result.size(), reinterpret_cast<jbyte*>(result.data()));
  return result;
}
void fail(JNIEnv* env, const std::exception& error) {
  env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
sentencepiece::SentencePieceProcessor* processor(jlong handle) {
  if (!handle) throw std::runtime_error("NLLB tokenizer is closed");
  return reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lmreader_core_translation_NllbTokenizerNative_load(JNIEnv* env, jobject, jbyteArray path) {
  try {
    auto tokenizer = std::make_unique<sentencepiece::SentencePieceProcessor>();
    auto status = tokenizer->Load(bytes(env, path));
    if (!status.ok()) throw std::runtime_error(status.ToString());
    if (tokenizer->GetPieceSize() != 256000) throw std::runtime_error("Unexpected NLLB vocabulary size");
    return reinterpret_cast<jlong>(tokenizer.release());
  } catch (const std::exception& error) { fail(env, error); return 0; }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_lmreader_core_translation_NllbTokenizerNative_encode(JNIEnv* env, jobject, jlong handle, jbyteArray input) {
  try {
    std::vector<int> pieces;
    auto status = processor(handle)->Encode(bytes(env, input), &pieces);
    if (!status.ok()) throw std::runtime_error(status.ToString());
    std::vector<jint> ids;
    ids.reserve(pieces.size());
    // Fairseq reserves <s>, <pad>, </s>, <unk> at 0..3; SP reserves <unk>, <s>, </s> at 0..2.
    for (int piece : pieces) ids.push_back(piece == 0 ? 3 : piece == 1 ? 0 : piece == 2 ? 2 : piece + 1);
    auto result = env->NewIntArray(ids.size());
    if (result) env->SetIntArrayRegion(result, 0, ids.size(), ids.data());
    return result;
  } catch (const std::exception& error) { fail(env, error); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_lmreader_core_translation_NllbTokenizerNative_decode(JNIEnv* env, jobject, jlong handle, jintArray input) {
  try {
    std::vector<jint> ids(env->GetArrayLength(input));
    env->GetIntArrayRegion(input, 0, ids.size(), ids.data());
    std::vector<int> pieces;
    for (int id : ids) if (id >= 4 && id <= 256000) pieces.push_back(id - 1);
    std::string text;
    auto status = processor(handle)->Decode(pieces, &text);
    if (!status.ok()) throw std::runtime_error(status.ToString());
    auto result = env->NewByteArray(text.size());
    if (result) env->SetByteArrayRegion(result, 0, text.size(), reinterpret_cast<const jbyte*>(text.data()));
    return result;
  } catch (const std::exception& error) { fail(env, error); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_lmreader_core_translation_NllbTokenizerNative_close(JNIEnv*, jobject, jlong handle) {
  delete reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);
}
