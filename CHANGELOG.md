# Changelog / 更新记录

## v0.1.5 — Translation scheduling and reader editing / 翻译调度与阅读器编辑

- 资源利用／队列顺序优先、分资源限流、完整 SEG／OCR 缓存与通用阻塞检查。
- 漫画级 API 选择、离线语言置顶与下载入口、默认值及翻译状态改进。
- 气泡移动／缩放／旋转／字号／新建、逆向翻页修复、介绍复制及主屏幕浮球。
- Resource scheduling, per-comic APIs, complete preprocessing caches and consistent retry handling.
- Bubble editing and creation, reverse-paging fixes, description copying and home progress controls.

详见 [v0.1.5 中英发行说明 / Bilingual release notes](docs/releases/v0.1.5.md)。

## v0.1.0 — First release / 首版

First signed APK release, using a dedicated RSA-4096 release key.
首个已签名 APK 发行版，使用专用 RSA-4096 发布密钥。

- Local library/shelf, reading progress, image-folder/ZIP/CBZ/PDF reading.
- Local Seg/OCR, offline translation packs and configurable API workflows.
- Bubble overlays/edits, glossary, Cat-paw templates/editor/import/export.
- Translation/export queues, foreground notifications, pause/cancel/retry.
- PNG/JPEG/CBZ export snapshots, validation and temporary-file recovery.
- Backups with empty API keys, checked restore/rollback and startup recovery.
- About page with app version, GitHub project link and stable-release update checks.
- Simplified Chinese/English UI: system-following uses English for every non-Simplified-Chinese primary language.
- Release version `0.1.0`, code `2`, only arm64-v8a/x86_64; reproducible preparation and packaging instructions.

翻译与导出任务完成后自动出队，保留译文、章节完成状态与导出产物；启动清理旧完成任务，失败、中断与暂停记录仍可恢复。

Completed translation/export tasks leave their queues automatically while translations, chapter status and published files are retained. Startup reconciles old completed tasks; failed, interrupted and paused records remain recoverable.

对应：本地图库/书架/进度与四格式阅读，本地/API 翻译，气泡编辑与字典，猫爪工作流，任务通知及队列，三格式导出和临时恢复，备份回滚，以及修正后的中英语言回退。详细边界见[中英发行说明](docs/releases/v0.1.0.md)。
