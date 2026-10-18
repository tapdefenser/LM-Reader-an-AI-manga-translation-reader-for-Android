# Release And Signing

## Current Release: v0.1.11

Release date: 2026-10-19. Version `0.1.11`, code `13`, tag `v0.1.11`, APK `LM-Reader-v0.1.11-64bit.apk`. See the [current notes](releases/v0.1.11.md) and [GitHub Release](https://github.com/tapdefenser/LM-Reader-an-AI-manga-translation-reader-for-Android/releases/tag/v0.1.11).

Use the same build/sign/package workflow and certificate below. `tools/package_release.py` reads the current `gradle/release.properties`, requires committed sources and packages this version's notes and checksums. This release adds OPUS-MT with automatic package discovery and fixes external screen-translation settings entry points; see the current notes for verification scope. Open distribution and long-running ARM checks remain as documented below.

## v0.1.6 Reference

[简体中文](RELEASING.zh-CN.md) · [Bilingual release notes](releases/v0.1.6.md)

Release date: 2026-10-12. This release provides a signed APK on [GitHub Releases](https://github.com/tapdefenser/LM-Reader-an-AI-manga-translation-reader-for-Android/releases/tag/v0.1.6). The repository is public.

## Release assets

| Item | Configuration |
|---|---|
| Version / code | `0.1.6` / `8`, from `gradle/release.properties` |
| Tag | `v0.1.6` |
| Package | `com.lmreader`; Debug uses `com.lmreader.debug` |
| Android | min 26, target 36, compile 37 |
| ABIs | `arm64-v8a`, `x86_64` |
| Optimization | R8 and resource shrinking |
| Signing | Dedicated RSA-4096 key; APK v2/v3 verification passed |
| APK | `LM-Reader-v0.1.6-64bit.apk`; actual size is recorded in the attached metadata |

The APK SHA-256 is recorded in this release's `SHA256SUMS` and `release-metadata.json` assets.

Signer certificate SHA-256: `8b1051b89d4e4bf8c423e4f7a2ad19e9ef1cfce3cd19041997a75b838b65ecc7`. Future releases must reuse this key for normal upgrades.

Assets include the APK, `RELEASE_NOTES.md`, `SHA256SUMS`, `release-metadata.json`, `THIRD_PARTY_NOTICES.md` and `LICENSES.zip`. Extract the notices archive before checking document/license paths in the checksum list. Metadata records signing, certificate fingerprint, source revision, ABIs, Android library alignment and separate Hexagon DSP files.

## Build and package

Use JDK 21, Python 3.9+/PyYAML, SDK Platform 37.0, Build Tools 36.0.0, NDK 28.2.13676358 and CMake 3.22.1.

```sh
python -m pip install PyYAML
python tools/fetch_vision_models.py
python tools/fetch_translation_sources.py
python tools/test_ui_translations.py
./gradlew :app:assembleDebug :app:lintDebug test --no-parallel --max-workers=2
./gradlew :app:assembleRelease :app:lintRelease --no-parallel --max-workers=2
python tools/verify_native_jni.py --apk app/build/outputs/apk/release/app-release-unsigned.apk
```

On Windows use `gradlew.bat`. If packaging runs out of memory, increase the Java heap with `-Dorg.gradle.jvmargs=-Xmx6g`; reducing parallel work lowers memory usage.

Signing reads four local environment variables: `LMREADER_KEYSTORE_FILE`, `LMREADER_KEYSTORE_PASSWORD`, `LMREADER_KEY_ALIAS`, `LMREADER_KEY_PASSWORD`. Configure all or none. Without them, builds only produce an unsigned candidate. This release instead built the unsigned APK first, signed it with the out-of-repository release key, then packaged it with the command below.

```sh
./gradlew --no-configuration-cache :app:assembleRelease :app:lintRelease --no-parallel --max-workers=2
python tools/package_release.py --apk app/build/outputs/apk/release/app-release.apk
```

Packaging verifies package/version/SDK, CRC, signature, ABIs, Android ELF/ZIP 16 KB alignment, six model hashes and UI JSON. Hexagon DSP skeletons are recorded separately from Android ARM64 libraries. Signing and packaging require committed sources; unsigned or dirty candidates require `--allow-unsigned` for local preparation.

## Keys and upgrades

The release key is stored outside the repository. Its Windows DPAPI-encrypted password can only be decrypted by the corresponding Windows account. Securely back up the key and password separately before reinstalling Windows or moving computers. Never upload private keys, passwords or signing configuration caches. Signing builds disable configuration caching. See Android’s [signing documentation](https://developer.android.com/studio/publish/app-signing).

Debug/Release use separate packages; migrate with app backups. This release reuses the v0.1.0 signing certificate and upgrades v0.1.5 in place.

## Verification and limits

This update's Debug build/lint (0 errors, 110 warnings, 3 hints) and pre-release Release build/lint (0 errors, 112 warnings, 3 hints) passed; the full JVM check reported **573 passed, one environment-dependent skip and no failures**, and the six localization-tool tests passed. Signed APK checks include v2/v3, 16 KB ZIP/Android library LOAD alignment, DSP file classification, JNI bindings, six model hashes and UI JSON.

The screen translation changes were regression-tested on the MuMu emulator and a physical phone during the 11–12 October iterations: unit tests covered the model, workflow and app modules, and Android automation covered crop planning, masks and tracking, three-mode integration, queue scheduling, settings storage, standalone session database transactions and workflow hot reload. The build, signing and installation records from that iteration are in the [screen translation refactor and verification record](SCREEN_TRANSLATION_REFACTOR.zh-CN.md).

Release acceptance requires installing the actual signed APK, returning from settings children, completing SEG → OCR → translation and opening the translated page. Debug testing cannot replace minified-release acceptance. This release completed an in-place upgrade install that retained existing data, and the installed APK checksum matches the release package.

ARM long-running background/power behavior and real SD/cloud providers remain to be validated. Popup and menu detection still relies on verifiable surrounding source texture and does not guarantee that any opaque full-screen content can be distinguished. Project license selection, independent Seg redistribution evidence and complete dependency review remain unfinished; retained notices do not imply those reviews are complete. Repository visibility has not changed.
