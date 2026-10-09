<p align="center">
  <img src="docs/assets/readme/hero.svg" alt="LM-Reader — Your stories. Your language." width="100%" />
</p>

<p align="center">
  <b>把本地漫画，读成自己的语言。</b><br />
  Android 本地漫画阅读器 · 离线与 API 翻译 · 猫爪工作流
</p>

<p align="center">
  <b>简体中文</b> · <a href="README.en.md">English</a> ·
  <a href="docs/USAGE.zh-CN.md">使用指南</a> ·
  <a href="https://github.com/tapdefenser/LM-Reader/releases">Releases</a> ·
  <a href="https://github.com/tapdefenser/LM-Reader/issues">反馈</a>
</p>

> **v0.1.5**：[下载已签名 APK](https://github.com/tapdefenser/LM-Reader-an-AI-manga-translation-reader-for-Android/releases/tag/v0.1.5)。Android 8.0+，支持 arm64-v8a / x86_64。更新内容与已知边界见[发行说明](docs/releases/v0.1.5.md)。

## 从阅读，到翻译与创作

LM-Reader 面向你自己的本地漫画目录。管理图库和阅读进度，在原图上显示译文，编辑气泡，再把需要的章节导出。

| 阅读与管理 | 翻译与处理 |
|---|---|
| **本地图库** — 单/多章节目录、增量扫描、分类、搜索和排序 | **离线翻译** — 本地 Seg/OCR，按方向下载机翻语言包 |
| **舒适阅读** — 图片目录、ZIP、CBZ、PDF；分页与条漫、缩放、裁白边 | **自选 API** — 图片/文本模型、提示词、术语与漫画译名字典 |
| **进度与书架** — 阅读位置、已读状态、收藏与分类 | **气泡编辑** — 译文浮层、人工修改、删除、撤销和保存 |
| **备份与恢复** — 设置、书架、进度、译文、工作流与恢复前回滚副本 | **猫爪工作流** — 可视化步骤、变量、循环、同步/异步与参考模板 |

翻译与导出队列支持排序、暂停、取消和重试，并提供后台任务通知。已完成任务自动出队，译文和已导出文件继续保留。导出支持 **PNG / JPEG 图片目录与 CBZ**；固定页面快照和发布日志用于失败重试与临时文件恢复。


## 开始使用

1. **加入目录**：首次打开时选择漫画目录，确认单/多章节结构后扫描。
2. **开始阅读**：打开漫画，按需加入书架、设置分类和阅读方式。
3. **选择翻译路线**：填写原文/目标语言，选择工作流；安装离线包，或配置并测试所需 API。
4. **翻译与编辑**：重译本页、修改气泡，或把章节加入翻译队列。
5. **导出与备份**：选择导出目录和格式；用备份保存设置、进度、译文和工作流。

详细步骤见[使用指南](docs/USAGE.zh-CN.md)和[猫爪工作流说明](docs/WORKFLOWS.zh-CN.md)。

## 设备与语言

支持 **Android 8.0 / API 26+**，首版打包 **arm64-v8a / x86_64**，不支持 32 位设备。提供跟随系统、浅色和深色主题。

在「设置 → 通用 → 应用语言」选择：

| 选项 | 界面语言 |
|---|---|
| 跟随系统 | 首选系统语言为简体中文时使用简中；其他语言（含繁体中文）使用英语 |
| 简体中文 / English | 使用手动选择的语言 |

`zh-Hans`、`zh-CN`、`zh-SG` 使用简中；`zh-Hant`、`zh-TW`、`zh-HK`、`zh-MO` 默认英语。界面语言不改变漫画标题、路径、用户输入或漫画翻译目标。

「设置 → 关于」提供当前版本、GitHub 项目主页与检查更新；发现最新公开正式 Release 后可进入发行页面。

## 数据与恢复

本地翻译使用设备上的模型。配置 API 工作流后，所选提供方可能收到页面图片、文字和提示词。API 密钥使用设备 Keystore 加密，用户备份默认排除密钥。

备份不包含原始漫画、模型、请求日志、导出产物或导出队列；恢复后需要重新授权目录、填写密钥和安装模型。进程终止后中断任务需要手动重试，任意工作流中间状态暂不支持断点恢复。详见[备份与任务恢复](docs/BACKUP.zh-CN.md)。

## 从源码构建

需要 **JDK 21**、Android SDK Platform **37.0**、Build Tools **36.0.0**、NDK **28.2.13676358**、CMake **3.22.1** 和 Python **3.9+**。版本来自 [gradle/release.properties](gradle/release.properties)。

```sh
python -m pip install PyYAML
python tools/fetch_vision_models.py
python tools/fetch_translation_sources.py
# 设置 JAVA_HOME，并在 local.properties 配置 sdk.dir。
./gradlew :app:assembleDebug :app:assembleRelease test :app:lintDebug :app:lintRelease
python tools/package_release.py --allow-unsigned
```

Windows 使用 `gradlew.bat`。未提供正式签名环境变量时，Release 产物为未签名候选；离线机翻包由用户运行时下载/导入。完整构建、签名与校验步骤见[发布准备](docs/RELEASING.zh-CN.md)。

## 文档与项目结构

- [文档目录](docs/README.md) · [English documentation](docs/README.en.md)
- [使用指南](docs/USAGE.zh-CN.md) · [工作流](docs/WORKFLOWS.zh-CN.md) · [备份恢复](docs/BACKUP.zh-CN.md)
- [架构](docs/ARCHITECTURE.md) · [模型与语言包](docs/MODEL_PACKS.md)
- [发行说明](docs/releases/v0.1.5.md) · [更新记录](CHANGELOG.md) · [第三方声明](THIRD_PARTY_NOTICES.md)

```text
app/          应用界面、阅读器、队列、备份与导出
core/         数据、存储、索引、API、视觉、机翻与工作流
build-logic/  Gradle 构建约定
gradle/       依赖与版本配置
tools/        固定版本源码、模型与发布校验工具
docs/         使用文档、发行说明与 README 图片
.github/      构建验证与问题模板
```

研究与实际复用来源见[来源说明](docs/UPSTREAM.md)。项目自身许可证尚未选定；第三方文件保留各自版权及许可证，Seg 权重独立再分发依据仍待核对。

[报告问题](https://github.com/tapdefenser/LM-Reader/issues)时，请提供版本、设备/Android 版本、界面语言、输入格式、复现步骤和已脱敏日志。
