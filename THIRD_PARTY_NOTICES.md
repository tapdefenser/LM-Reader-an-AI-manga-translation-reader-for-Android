# Third-party sources and notices / 第三方来源与声明

This is a source/notice index, not a replacement for the original license texts or a statement that every model is cleared for redistribution. The project's own license has not yet been selected. 第三方文件保留各自版权及许可证，项目自身许可证尚未选定。

| Component / 组件 | Source and retained notices / 来源与声明 |
|---|---|
| Adapted Seg execution code | manga-translator-android, fixed source `809452a4a6b10f520559ad6439e3a85d8ae4c680`; MIT headers retained. [MIT notice](core/vision/src/main/assets/vision/notices/manga-translator-MIT.txt) |
| Seg weights | Extracted from upstream v3.5.5 APK. Independent training origin/redistribution terms remain unverified; no MIT inference from the code. [Model notice](core/vision/src/main/assets/vision/notices/Models.txt) |
| Paddle OCR weights/dictionaries | Official pinned PaddlePaddle model repositories, model-card Apache-2.0 declarations. [Notice](core/vision/src/main/assets/vision/notices/PaddleOCR-Apache-2.0.txt); hashes/URLs in [preparation script](tools/fetch_vision_models.py) |
| Bergamot translator | MPL-2.0; pinned Android source and dependencies in [source lock](tools/translation_sources.lock.json). [Original license](core/translation/src/main/assets/translation/licenses/bergamot-LICENSE) |
| whisper.cpp / ggml ASR runtime (CPU, Vulkan, optional OpenCL) | MIT; pinned v1.9.5 source and archive checksum in [ASR source lock](tools/asr_sources.lock.json). [Upstream ggml authors' license](core/translation/src/main/assets/translation/licenses/whisper.cpp-MIT.txt). ASR model weights are downloaded/imported separately and retain their catalog attribution. |
| Khronos Vulkan, SPIR-V and OpenCL headers | Fixed archives in the same [ASR source lock](tools/asr_sources.lock.json); original [Vulkan headers license](core/translation/src/main/assets/translation/licenses/Vulkan-Headers-LICENSE.md), [SPIR-V headers license](core/translation/src/main/assets/translation/licenses/SPIRV-Headers-LICENSE.txt) and [OpenCL headers license](core/translation/src/main/assets/translation/licenses/OpenCL-Headers-LICENSE.txt) are bundled. The Android loader adaptation is retained in the project CMake source. |
| Marian and ssplit | MIT, original dependency notices retained in [translation/licenses](core/translation/src/main/assets/translation/licenses) |
| SentencePiece, Ruy, onnxjs, fmt, PCRE2 and other native dependencies | Individual Apache/MIT/BSD licenses, retained in the same directory; inspect the corresponding file |
| Offline language packs | Downloaded/imported separately. Mozilla MPL-2.0 and community model-card declarations are preserved independently; see [pack protocol](docs/MODEL_PACKS.md) |
| LiteRT / ONNX Runtime | Runtime distributions with Apache-2.0 / MIT declarations; visual engine notes retain the origins |
| ONNX Runtime QNN plugin / Qualcomm QNN runtime | QNN plugin `com.qualcomm.qti:onnxruntime-android-qnn:2.6.0` ([original MIT license](core/vision/src/main/assets/vision/notices/QNN-plugin-MIT.txt)) and runtime `com.qualcomm.qti:qnn-runtime:2.50.0` from Maven Central. The proprietary runtime has separate [AI Stack license](core/vision/src/main/assets/vision/notices/QNN-AI-Stack-License.pdf) and [original notices](core/vision/src/main/assets/vision/notices/QNN-NOTICE.txt), retained from its AAR. These runtime terms are distinct from ONNX Runtime's MIT license. |
| AndroidX, Compose, Kotlin, OkHttp and reader image library | Dependency coordinates/versions in [version catalog](gradle/libs.versions.toml); upstream notices remain applicable. Complete transitive notice review remains a release task |

Full visual/translation license texts are bundled in APK assets under `vision/notices` and `translation/licenses`; the packaging tool copies them alongside the candidate. Build preparation exposes pinned source archives and compatibility patches through `tools/fetch_translation_sources.py`; modified covered source must be available with the corresponding release source.

研究参考 EhViewer、Mihon 和 manga-translator-android 的范围与实际复用记录见[上游参考与复用边界](docs/UPSTREAM.md)。研究参考不等于纳入全部上游代码，也不能覆盖权重或依赖的独立声明。
