<p align="center">
  <img src="docs/assets/readme/hero.svg" alt="LM-Reader — Your stories. Your language." width="100%" />
</p>

<p align="center">
  <b>Read your local comics in your own language.</b><br />
  Android comic reader · Offline &amp; API translation · Cat-paw workflows
</p>

<p align="center">
  <a href="README.md">简体中文</a> · <b>English</b> ·
  <a href="docs/USAGE.en.md">User guide</a> ·
  <a href="https://github.com/tapdefenser/LM-Reader/releases">Releases</a> ·
  <a href="https://github.com/tapdefenser/LM-Reader/issues">Feedback</a>
</p>

> **v0.1.5:** [Download the signed APK](https://github.com/tapdefenser/LM-Reader-an-AI-manga-translation-reader-for-Android/releases/tag/v0.1.5). Android 8.0+, arm64-v8a / x86_64. See [release notes](docs/releases/v0.1.5.md) for changes and known limitations.

## Read, translate and create

LM-Reader works with your own local comic folders. Organize your library and reading progress, display translations over original pages, edit bubbles and export the chapters you need.

| Reading & management | Translation & processing |
|---|---|
| **Local library** — Single/multi-chapter folders, incremental scans, categories, search and sorting | **Offline translation** — Local Seg/OCR with optional translation packs |
| **Comfortable reading** — Image folders, ZIP, CBZ and PDF; paged/strip modes, zoom and margin cropping | **Your APIs** — Image/text models, prompts, terminology and a per-comic glossary |
| **Progress & shelf** — Reading position, read status, favorites and categories | **Bubble editing** — Translation overlays, manual changes, deletion, undo and save |
| **Backup & recovery** — Settings, shelf, progress, translations, workflows and pre-restore rollback copies | **Cat-paw workflows** — Visual steps, variables, loops, sync/async execution and templates |

Translation/export queues support ordering, pause, cancel and retry, with background task notifications. Completed tasks leave automatically while saved translations and exported files remain. Export **PNG / JPEG image folders or CBZ**; fixed page snapshots and publishing journals support retry and temporary-file recovery.


## Get started

1. **Add a folder:** select your comic directory on first launch, choose its single/multi-chapter layout and scan it.
2. **Start reading:** open a comic, add it to the shelf and adjust categories and reading preferences.
3. **Choose a translation route:** set source/target languages and a workflow; install offline packs, or configure and test its APIs.
4. **Translate and edit:** retranslate a page, edit bubbles, or queue chapters for translation.
5. **Export and back up:** choose an output folder/format and save settings, progress, translations and workflows in a backup.

See the [user guide](docs/USAGE.en.md) and [Cat-paw workflow guide](docs/WORKFLOWS.en.md).

## Devices and language

Requires **Android 8.0 / API 26+**. The first APK includes **arm64-v8a / x86_64** only; 32-bit devices are unsupported. System, light and dark themes are available.

Choose **Settings → General → App language**:

| Selection | UI language |
|---|---|
| Follow system | Simplified Chinese for a Simplified Chinese primary system language; English for every other language, including Traditional Chinese |
| Simplified Chinese / English | Always use the manual selection |

`zh-Hans`, `zh-CN` and `zh-SG` use Chinese; `zh-Hant`, `zh-TW`, `zh-HK` and `zh-MO` default to English. UI language does not change comic titles, paths, user input or a comic's translation target.

**Settings → About** shows the app version, opens the GitHub project page and checks the latest public stable Release, with a link to its release page.

## Data and recovery

Local translation uses models on the device. Configured API workflows may send images, text and prompts to the chosen provider. API keys are encrypted with the device's Keystore and excluded from user backups by default.

Backups exclude original comics, models, request logs, exported files and export queues. After restoration, reauthorize folders, enter keys and install models again. Process termination requires manual retry of interrupted tasks; arbitrary workflow intermediate state is not checkpointed. See [backup and recovery](docs/BACKUP.en.md).

## Build from source

Use **JDK 21**, Android SDK Platform **37.0**, Build Tools **36.0.0**, NDK **28.2.13676358**, CMake **3.22.1** and Python **3.9+**. Version configuration lives in [gradle/release.properties](gradle/release.properties).

```sh
python -m pip install PyYAML
python tools/fetch_vision_models.py
python tools/fetch_translation_sources.py
# Set JAVA_HOME and sdk.dir in local.properties.
./gradlew :app:assembleDebug :app:assembleRelease test :app:lintDebug :app:lintRelease
python tools/package_release.py --allow-unsigned
```

On Windows, use `gradlew.bat`. Missing formal signing environment variables produce an unsigned Release candidate. Offline translation packs are downloaded/imported at runtime. See [release preparation](docs/RELEASING.en.md) for signing and packaging checks.

## Documentation and layout

- [English documentation](docs/README.en.md) · [中文文档](docs/README.md)
- [User guide](docs/USAGE.en.md) · [Workflows](docs/WORKFLOWS.en.md) · [Backup/recovery](docs/BACKUP.en.md)
- [Architecture](docs/ARCHITECTURE.md) · [Models and language packs](docs/MODEL_PACKS.md)
- [Release notes](docs/releases/v0.1.5.md) · [Changelog](CHANGELOG.md) · [Third-party notices](THIRD_PARTY_NOTICES.md)

```text
app/          UI, reader, queues, backup and export
core/         Data, storage, indexing, API, vision, translation and workflows
build-logic/  Gradle conventions
gradle/       Dependencies and version configuration
tools/        Pinned source/model preparation and release checks
docs/         User documentation, release notes and README images
.github/      Build verification and issue templates
```

See [upstream sources](docs/UPSTREAM.md) for research and reuse. The project's own license has not yet been selected; third-party notices remain applicable. Independent redistribution evidence for Seg weights is pending.

[Report an issue](https://github.com/tapdefenser/LM-Reader/issues) with app version, device/Android version, UI language, input format, reproduction steps and redacted logs.
