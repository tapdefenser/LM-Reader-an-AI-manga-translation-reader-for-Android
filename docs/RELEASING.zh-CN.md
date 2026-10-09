# v0.1.5 发布与签名

[English](RELEASING.en.md) · [中英发行说明](releases/v0.1.5.md)

发布日期：2026-10-09。本版提供已签名 APK：[GitHub Release](https://github.com/tapdefenser/LM-Reader-an-AI-manga-translation-reader-for-Android/releases/tag/v0.1.5)。仓库为公开仓库。

## 本版附件

| 项目 | 配置 |
|---|---|
| 版本 / versionCode | `0.1.5` / `7`，来自 `gradle/release.properties` |
| 标签 | `v0.1.5` |
| applicationId | `com.lmreader`；Debug 为 `com.lmreader.debug` |
| Android | min API 26、target API 36、compile SDK 37 |
| ABI | `arm64-v8a`、`x86_64` |
| Release 优化 | R8 与资源收缩 |
| 签名 | 专用 RSA-4096 密钥，APK v2/v3 校验通过 |
| APK | `LM-Reader-v0.1.5-64bit.apk`；实际大小见附件 metadata |

APK SHA-256 见本版附件 `SHA256SUMS` 与 `release-metadata.json`。

签名证书 SHA-256：`8b1051b89d4e4bf8c423e4f7a2ad19e9ef1cfce3cd19041997a75b838b65ecc7`。后续版本继续使用同一密钥，以便覆盖升级。

附件包括 APK、`RELEASE_NOTES.md`、`SHA256SUMS`、`release-metadata.json`、`THIRD_PARTY_NOTICES.md` 和 `LICENSES.zip`。解压声明包后可核对校验清单中的文档与许可证。Metadata 记录真实 APK 签名、证书指纹、源码提交、ABI、Android 原生库对齐及独立的 Hexagon DSP 文件。

## 从源码准备

使用 JDK 21、Python 3.9+/PyYAML、SDK Platform 37.0、Build Tools 36.0.0、NDK 28.2.13676358 和 CMake 3.22.1。

```sh
python -m pip install PyYAML
python tools/fetch_vision_models.py
python tools/fetch_translation_sources.py
./gradlew :app:assembleDebug :app:lintDebug test --no-parallel --max-workers=2
./gradlew :app:assembleRelease :app:lintRelease --no-parallel --max-workers=2
python tools/verify_native_jni.py --apk app/build/outputs/apk/release/app-release-unsigned.apk
```

Windows 使用 `gradlew.bat`。打包内存不足时可通过 `-Dorg.gradle.jvmargs=-Xmx6g` 增大 Java 堆；减少并行任务可降低占用。

正式构建读取四个本机环境变量：`LMREADER_KEYSTORE_FILE`、`LMREADER_KEYSTORE_PASSWORD`、`LMREADER_KEY_ALIAS`、`LMREADER_KEY_PASSWORD`。全部配置或全部清空；未配置时仅生成未签名候选。

```sh
./gradlew --no-configuration-cache :app:assembleRelease :app:lintRelease --no-parallel --max-workers=2
python tools/package_release.py --apk app/build/outputs/apk/release/app-release.apk
```

打包校验包名、版本、SDK、CRC、签名、ABI、Android ELF/ZIP 16 KB 对齐、六个模型和 UI JSON。Hexagon DSP skeleton 与 Android ARM64 库分别记录。未签名或脏工作区只可使用 `--allow-unsigned` 准备本地候选。

## 密钥与升级

签名密钥保存在仓库外。Windows DPAPI 加密的密码仅可由对应 Windows 账户解密；重装系统或换电脑前，另行安全备份密钥和密码。不要把私钥、密码或配置缓存上传 GitHub。签名构建关闭 configuration cache。Android [签名说明](https://developer.android.com/studio/publish/app-signing)解释了证书与升级规则。

Debug 与 Release 使用独立包名，通过应用备份迁移。本版沿用 v0.1.0 的发布证书。

## 验证与已知边界

本轮 Debug 构建及 Lint、发布前 Release 构建及 Lint 通过；全量 JVM **499 项通过、1 项跳过、0 项失败**。签名 APK 校验包括 v2/v3、16 KB ZIP 与 Android native 库 LOAD 对齐、DSP 文件分类、JNI 及六个模型。

本轮 MuMu Android 15 上 110 项功能回归通过、2 项环境相关跳过。实际签名、R8 压缩的正式 APK 已验证设置返回、主屏幕浮球、详情菜单、英语 SEG → OCR → 简体中文机翻、GPU 失败回退 CPU、气泡编辑入口及重启后译文显示。APK JNI 检查已接入 CI 与打包。

发布验收必须安装实际签名 APK，打开设置子页后返回，再完成 SEG → OCR → 机翻并打开译文页面；Debug 测试不能代替压缩正式版验收。本版已完成上述正式版验收，并覆盖升级到 Android 16 手机，安装身份与首次安装时间保留，设备 APK 校验值与发布包一致。

仍待 ARM 长时后台与省电、真实 SD/云提供方验收。项目许可证选择、Seg 权重独立分发依据及完整依赖审查仍未完成，现有第三方声明不表示已完成这些审查。仓库权限未改变。
