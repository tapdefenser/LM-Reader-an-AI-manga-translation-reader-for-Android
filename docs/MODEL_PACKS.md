# Models and language packs / 模型与语言包

[Documentation / 文档](README.en.md) · [Third-party notices / 第三方声明](../THIRD_PARTY_NOTICES.md)

## Bundled vision assets / 内置视觉模型

`tools/fetch_vision_models.py` defines the fixed sources and SHA-256 values for six Seg/OCR model/dictionary files. Assets are prepared into `core/vision/src/main/assets/vision/models/`, which is ignored by Git. Both arm64-v8a and x86_64 package the native visual engine.

Seg weights come from the pinned upstream v3.5.5 APK. Independent training-origin/redistribution evidence remains unresolved; the MIT declaration for adapted execution code does not establish a weight license. The OCR weights/dictionaries come from pinned official PaddlePaddle model repositories. Original notices remain in `vision/notices` assets.

视觉模型通过固定来源与哈希准备，不向 Git 提交大二进制。Seg 权重的独立再分发依据仍待核对；OCR 使用固定官方来源，模型与代码声明分别保留。

## Offline translation / 离线机翻

Two dedicated translation options run on CPU: Bergamot/Marian and OPUS-MT. OPUS-MT reuses the app's existing ONNX Runtime and the SentencePiece library linked into the native translation engine. No additional Python or LLM runtime is bundled. Same-language pairs preserve the original text. Routing prefers a direct installed package, then the shortest installed path through any language, with at most three model stages and no cross-engine mixing. Packs are downloaded/imported at runtime and are not bundled in the APK.

Use **Settings → API and translation engines → Local translation engine**. Sources include Mozilla, a Hugging Face community copy, HF-Mirror and a user-configured catalog. Fetching a catalog downloads metadata only. Availability and model quality depend on the selected source/direction; a mirror may redirect to an overseas host.

离线包按方向安装，优先直达，其次选择同一引擎已安装包构成的最短路径，不限定英语中转，最多三段。获取列表只下载元数据；语言名称、代码与包名均可搜索，已安装包置顶。OPUS-MT 每个完整方向包限制为 350 MB，权重按需下载。翻译设置中选择“OPUS-MT 本地机翻”工作流；模型管理页的引擎页签只切换包列表和文本测试，不改变已排队任务。

### OPUS-MT discovery and execution / OPUS-MT 目录与执行

Opening the OPUS-MT model page automatically fetches a missing or day-old catalog. Manual refresh remains available. The app discovers ready ONNX repositories under `Xenova/opus-mt-*` on Hugging Face or HF-Mirror; it does not rely solely on an app-maintained language list. The initial adapter accepts explicit two-letter bilingual repositories, complete quantized encoder/merged-decoder packages below the size limit, and Apache-2.0/CC-BY-4.0 model-card licenses. Group-language aliases, missing files, oversized models and unknown licenses are excluded. This filter deliberately describes the supported export format, rather than promising every OPUS model is compatible.

Repository commits pin every download. LFS weights use SHA-256; small Git files use the Git blob SHA-1 digest (including the `blob <length>\0` header) advertised by the API, exact sizes and pinned URLs. A bundled pinned snapshot is an offline fallback, not the only discovery source. Failed refreshes keep the usable catalog and installed package descriptors. `tools/fetch_opus_catalog.py` refreshes the bundled fallback without downloading ONNX weights.

OPUS-MT uses greedy cached decoding, CPU threads bounded per session, and the shared translation resource pool. Cancellation is checked between generated tokens and route stages. This is an optional alternative: model size alone does not establish better speed or quality than Bergamot. QNN/NNAPI acceleration is not enabled for these exports. Their dynamic sequence/cache shapes, merged-decoder `If` branch and integer matrix operations require separate compatible exports and actual-device profiling before a hardware backend can be advertised. QNN HTP is NPU, distinct from QNN GPU; NNAPI is deprecated starting with Android 15.

Sources: [OPUS-MT models](https://github.com/Helsinki-NLP/OPUS-MT-train), [ready French–German export](https://huggingface.co/Xenova/opus-mt-fr-de), [QNN provider requirements](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html), [NNAPI](https://developer.android.com/ndk/guides/neuralnetworks).

## Catalog and installation / 目录与安装规则

- Custom catalogs use `catalog.json`: schema 1/2 `bergamot-v1` or schema 3 `local-nmt-v1`. Per-pack engine is `BERGAMOT` (legacy default) or `OPUS_MT`; unsupported engines are rejected. Each pack declares identity, version, source/target languages, file roles, original sizes/digests and compression/download paths. OPUS-MT roles are `encoder`, `decoder`, `srcVocab`, `trgVocab`, `vocab`, `config`; its Marian vocabulary IDs are mapped from SentencePiece pieces, not assumed to equal SentencePiece IDs.
- Models use int8 Marian `.bin`, shortlist `.bin` and SentencePiece `.spm` files. Transfers may use gzip or uncompressed files; installation validates original, decompressed hashes and sizes.
- Relative paths cannot escape the package. Catalog limits constrain response size, file roles, file/pack sizes and counts. Remote scripts and user-supplied YAML are not executed.
- Installed descriptors remain independent of refreshed catalogs. Source-specific caches are saved atomically; a late response from a previously selected source cannot replace the current source's catalog.
- Downloads use Range/strong ETag/If-Range and validate Content-Range. A 200 response to a resumed request starts over. Pausing preserves temporary downloads; unfinished compressed transfers restart after changing source.
- ZIP import permits only the declared original files, rejects path traversal, duplicates and unknown entries, and verifies expanded length/hash. All files must pass before same-filesystem atomic installation.

自定义目录、ZIP 导入和断点下载均校验路径、长度与哈希；暂存目录不能充当已安装模型。离开设置页后进程内下载可继续，系统结束进程后需手动继续，不自动恢复联网。

## Reproducible native sources / 原生源码

Run `python tools/fetch_translation_sources.py` before building. Commits/archive hashes and compatibility patches are public in `tools/translation_sources.lock.json` and the preparation script. CMake uses those pinned sources, rather than cloning dependencies during compilation.

Use NDK `28.2.13676358` and CMake `3.22.1`. The two native ABIs use 16 KB LOAD alignment. Translation calls lease bounded sessions from the shared resource pool. Bergamot checks cancellation at model/text/route boundaries; a running native call finishes before its result is discarded. OPUS-MT also checks cancellation between generated tokens.

`tools/export_translation_mirror.py` can prepare an explicitly selected pack set, catalog, ZIP files and licenses in a local folder. It does not start a server or publish a mirror. Keep each pack's original provenance and license texts; community declarations do not remove upstream terms.

## Notices / 声明

Bergamot and Mozilla model notices include MPL-2.0; native dependencies retain their individual declarations under `translation/licenses`. Modified covered sources must remain available with the corresponding release source. See [source/notice index](../THIRD_PARTY_NOTICES.md) and [release preparation](RELEASING.en.md) for outstanding distribution checks.
