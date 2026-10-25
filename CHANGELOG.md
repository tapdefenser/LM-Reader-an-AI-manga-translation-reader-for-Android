# Changelog / 更新记录

## Unreleased / 未发布

- Add video mask text color: Black, White or Automatic based on each region's background. Changes apply to existing overlays and review previews without rerunning translation. / 视频增加遮罩文字颜色：黑色、白色、自动（随背景亮度），保存即更新已有遮罩和校对预览，无需重译。
- Route capability repair by the actual missing ASR, translation, vision or API requirement, preserving the original waiting task and snapshot. / 按实际缺少的 ASR、机翻、视觉或 API 能力进入对应设置，保留等待任务与原配置。
- Clarify that automatic ASR uses CPU; mark GPU backends experimental and block Vulkan on the phone model with a confirmed native crash. / 明确 ASR 自动使用 CPU，GPU 标记为实验选项，并限制已确认原生崩溃机型上的 Vulkan。
- Complete missing English templates, check localization before CI toolchain downloads, and update bilingual video/build instructions. / 补齐英文模板，将文案检查移到 CI 工具链下载之前，并同步中英视频与构建说明。

## v0.1.11 - OPUS-MT and external translation settings / OPUS-MT 与应用外翻译设置

- 新增可选 OPUS-MT 本地机翻工作流，自动获取兼容语言包目录；优先直达，可经其他已安装语言中转，每个完整方向包不超过 350 MB。
- Add an optional OPUS-MT local workflow with automatic compatible-package discovery, direct-first routing through any installed language when needed, and a 350 MB cap per complete direction package.
- 修复应用外的通知入口及翻译球长按设置弹窗，不再拉起阅读器主界面；清理停用的本地翻译后端与资源。
- Keep screen-translation settings outside the reader task when opened from a notification or a ball long press; remove the retired local translation backend and its resources.

详见 [v0.1.11 中英发行说明 / Bilingual release notes](docs/releases/v0.1.11.md)。

## v0.1.10 - Screen translation and reader UI / 屏幕翻译与阅读界面

- 屏幕翻译球长按打开设置并震动反馈，阅读器页码移除背景底片，横屏主菜单支持上下滑动。
- Long-press the screen-translation ball for settings and haptic feedback, remove the reader page-counter background and allow vertical scrolling of the landscape main menu.

详见 [v0.1.10 中英发行说明 / Bilingual release notes](docs/releases/v0.1.10.md)。

## v0.1.9 - Continuous video translation / 视频连续翻译

- 视频模式改为两张截图的有序缓冲，SEG 完成后接续截图，队首回填完成才释放容量，截图延时与后续 SEG 重叠计算。
- 新增视频专用 SEG 区域相似度，默认 60%；相同字幕沿用原气泡、位置及待完成的翻译，避免重复 OCR 与翻译。
- 新字幕逐泡回填前保留旧译文，多选区独立处理；手动与浏览模式保留原有调度。
- Video uses a two-frame FIFO buffer, admits the next capture after SEG and frees capacity after the oldest frame finishes backfilling. Capture delay overlaps the next SEG pass.
- Add an independent video SEG-region similarity setting, defaulting to 60%. Matching subtitles retain bubble identity, text and pending work instead of repeating OCR and translation.
- Keep old text until replacement bubbles are filled and process selected areas independently, preserving manual and browse scheduling.

详见 [v0.1.9 中英发行说明 / Bilingual release notes](docs/releases/v0.1.9.md)。

## v0.1.8 - Reader chapter order / 阅读器章节顺序

- 阅读器沿用详情页保存的章节顺序，支持首字母、修改时间、自然数字正反向排序及手动拖动。
- 上一章、下一章、连续跨章和相邻章节预载统一使用该顺序，保留现有预载预算、缓存及位置定位逻辑。
- 排序后继续阅读仍恢复原章节与页码；封面和元数据补全继续使用自然序。
- The reader follows the chapter order saved on the detail page, including alphabetical, modification-time, natural-number and manual ordering.
- Adjacent-chapter buttons, continuous reading and neighboring-chapter preloading share that order while retaining existing preload budgets, caching and position anchoring.
- Resume keeps the saved chapter and page after reordering; cover and metadata selection retain natural order.

详见 [v0.1.8 中英发行说明 / Bilingual release notes](docs/releases/v0.1.8.md)。

## v0.1.7 - Queue restart and engine lifecycle / 队列恢复与引擎生命周期

- 修复取消全部任务后重新入队卡住的问题，包括引擎加载期间的取消；旧任务完成清理后再启动替代任务。
- 按实际驻留的引擎配置协调资源，只预热当前任务需要的模型；保留兼容及占用中的资源，避免影响屏幕翻译。
- 正常完成章节后继续执行工作流收尾步骤，不误判成任务取消。
- Fix queue restarts after cancelling all tasks, including cancellation during engine preparation; replacement work waits for old workers to finish cleanup.
- Reconcile actual resident engine configurations and warm only the current task's models, preserving compatible and occupied resources used by screen translation.
- Let post-publication workflow steps finish after chapter rows are completed.

详见 [v0.1.7 中英发行说明 / Bilingual release notes](docs/releases/v0.1.7.md)。

## v0.1.6 — Continuous browse translation and failure isolation / 浏览连续翻译与失败隔离

- 弹窗遮挡保留底层气泡、只识别实际新增区域、新旧文字按可调间距合并后重 SEG；译文逐泡回填，旧译文保留到替换完成。
- 独立会话数据库记录待填／已填／已过时区域；旧遮罩不会被迟到结果复活，失败与空结果不删除已发布译文。
- 单页失败隔离覆盖漫画队列、整漫画预处理与屏幕三种模式；含失败页的章节保留错误与重试入口。
- 引擎运行时自动应用屏幕设置与工作流修改，只重建变化的资源；录屏流结束后自动回退无障碍窗口截图。
- Browse retains underlying bubbles through popups, recognizes only newly exposed tiles, re-runs SEG after merging nearby text at an adjustable distance, and backfills translations bubble by bubble.
- A session database tracks pending, filled and obsolete regions; late results cannot revive old masks, and failures or empty results keep published translations.
- Per-page failure isolation covers the comic queue, whole-comic preparation and all three screen modes, retaining the error and retry entry for affected chapters.
- Screen settings and workflow edits apply while the engine runs with only changed resources rebuilt, and capture falls back to accessibility window screenshots when a recording stream ends.

详见 [v0.1.6 中英发行说明 / Bilingual release notes](docs/releases/v0.1.6.md)。

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
