# Models and language packs / 模型与语言包

[Documentation / 文档](README.en.md) · [Third-party notices / 第三方声明](../THIRD_PARTY_NOTICES.md)

## Bundled vision assets / 内置视觉模型

`tools/fetch_vision_models.py` defines the fixed sources and SHA-256 values for six Seg/OCR model/dictionary files. Assets are prepared into `core/vision/src/main/assets/vision/models/`, which is ignored by Git. Both arm64-v8a and x86_64 package the native visual engine.

Seg weights come from the pinned upstream v3.5.5 APK. Independent training-origin/redistribution evidence remains unresolved; the MIT declaration for adapted execution code does not establish a weight license. The OCR weights/dictionaries come from pinned official PaddlePaddle model repositories. Original notices remain in `vision/notices` assets.

视觉模型通过固定来源与哈希准备，不向 Git 提交大二进制。Seg 权重的独立再分发依据仍待核对；OCR 使用固定官方来源，模型与代码声明分别保留。

## Offline translation / 离线机翻

NLLB-200 distilled 600M INT8 is available from the **NLLB-200 multilingual** Hugging Face and HF-Mirror sources. Fetch the model list, then download its single shared package (about 900 MB). It exposes 202 language/script choices and directly translates between supported pairs, without an English pivot or repeated per-pair downloads. Installed NLLB models take priority for supported pairs; Bergamot packs remain installed and serve other available directions. The CPU session is shared across directions and runs one translation at a time to avoid duplicating the large model. Downloads and ZIP imports use the same atomic installation and SHA-256 verification as existing packs. Weights are not bundled in the APK and retain CC-BY-NC-4.0 terms.

新增 NLLB-200 专用多语种机翻引擎：在下载源中选择 NLLB-200 的 Hugging Face 或 HF-Mirror，获取列表后下载约 900 MB 的共享模型，即可在受支持的语言／书写形式之间直译。安装后优先使用 NLLB，无需经英语中转；旧 Bergamot 包继续保留。较大的模型更占内存，CPU 速度可能较慢；多个翻译方向共用一个推理会话。正在运行的屏幕翻译会重新协调模型，主动停止的引擎保持停止。

The Bergamot/Marian JNI engine runs on CPU and accepts typed text batches. Same-language pairs preserve the original text; a missing direct direction can route through English when both packs are installed. Packs are downloaded/imported at runtime and are not bundled in the APK.

Use **Settings → API and translation engines → Local translation engine**. Sources include Mozilla, a Hugging Face community copy, HF-Mirror and a user-configured catalog. Fetching a catalog downloads metadata only. Availability and model quality depend on the selected source/direction; a mirror may redirect to an overseas host.

离线包按方向安装。直接方向不可用时可经英语中转，需要安装两段语言包。获取列表只下载元数据；语言名称、代码与包名均可搜索，已安装包置顶。

## Catalog and installation / 目录与安装规则

- Custom catalogs use `catalog.json`: legacy schema 1/2 uses `bergamot-v1`; schema 3 also supports `local-nmt-v1` with a per-pack `engine` (`BERGAMOT` or `NLLB`). Each pack declares identity, version, source/target languages, file roles, original sizes/SHA-256 and compression/download paths. NLLB declares `encoder`, `decoder` and `tokenizer` roles and derives its multilingual coverage from the fixed FLORES token map.
- Models use int8 Marian `.bin`, shortlist `.bin` and SentencePiece `.spm` files. Transfers may use gzip or uncompressed files; installation validates original, decompressed hashes and sizes.
- Relative paths cannot escape the package. Catalog limits constrain response size, file roles, file/pack sizes and counts. Remote scripts and user-supplied YAML are not executed.
- Installed descriptors remain independent of refreshed catalogs. Source-specific caches are saved atomically; a late response from a previously selected source cannot replace the current source's catalog.
- Downloads use Range/strong ETag/If-Range and validate Content-Range. A 200 response to a resumed request starts over. Pausing preserves temporary downloads; unfinished compressed transfers restart after changing source.
- ZIP import permits only the declared original files, rejects path traversal, duplicates and unknown entries, and verifies expanded length/hash. All files must pass before same-filesystem atomic installation.

自定义目录、ZIP 导入和断点下载均校验路径、长度与哈希；暂存目录不能充当已安装模型。离开设置页后进程内下载可继续，系统结束进程后需手动继续，不自动恢复联网。

## Reproducible native sources / 原生源码

Run `python tools/fetch_translation_sources.py` before building. Commits/archive hashes and compatibility patches are public in `tools/translation_sources.lock.json` and the preparation script. CMake uses those pinned sources, rather than cloning dependencies during compilation.

Use NDK `28.2.13676358` and CMake `3.22.1`. The two native ABIs use 16 KB LOAD alignment. Native translation calls are serialized; cancellation is checked at model/text/intermediate-route boundaries, and a currently executing native call must finish before its result is discarded.

`tools/export_translation_mirror.py` can prepare an explicitly selected pack set, catalog, ZIP files and licenses in a local folder. It does not start a server or publish a mirror. Keep each pack's original provenance and license texts; community declarations do not remove upstream terms.

## Notices / 声明

Bergamot and Mozilla model notices include MPL-2.0; native dependencies retain their individual declarations under `translation/licenses`. Modified covered sources must remain available with the corresponding release source. See [source/notice index](../THIRD_PARTY_NOTICES.md) and [release preparation](RELEASING.en.md) for outstanding distribution checks.
