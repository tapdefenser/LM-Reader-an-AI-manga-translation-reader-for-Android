# 第一批后续缺口：成果保护与能力检查

日期：2026-10-20。对应最终计划 1.4、1.5；下文保留第一批首轮 DS 只读调查、Codex 复核的静态基线，不能据此推断当时已修复或通过设备测试。**第二轮已实施 1.4 并完成限定验收，最新事实、验证及边界见[执行进度](2026-10-20-execution-progress.zh-CN.md)的第二轮记录；1.5 仍待完成。**

## 1.4 已有保护

- `ReaderPageArtifactStore.kt` 已有原图 SHA-256 校验、临时文件 + fsync + rename、`expectedRevision`/`requireAbsent` 保存前置条件；人工保存通过 `saveEdits` 使用 revision。
- `TranslationTaskSnapshot.kt` 在入队时捕获程序、工作流版本、语言、文风、识别参数、显示样式和去密钥的 API 配置；队列继续校验 `queuedAt/configSnapshot`。
- 清除页与发布共用 `pageWriteMutex`，单页重译取消并等待上一次单页任务结束。
- 导出已有 `snapshot.json` 时复用相同内容，publication journal/receipt 处理发布中断；不能把这些已有能力再次登记为本批新增。

## 1.4 必须接续的缺口

| 优先顺序 | 代码证据与影响 | 下次最小实施及验收 |
|---|---|---|
| 1 | `AndroidWorkflowHost.admitPage` → `LocalPageTranslator.cached` → `ReaderPageArtifactStore.load` 只比较页与源 hash。成果虽保存语言，但没有工作流语义身份，语言也未用于任务缓存匹配。`chapterFinished` 使用 `artifacts.has` 统计，可能把旧成果算进新任务。 | 区分“旧成果可查看”和“匹配当前任务”；补稳定语义指纹（语言、程序/必要引擎及语义配置，排除纯显示样式和凭据），旧数据标为未知匹配。覆盖换目标语言、换工作流、只改显示样式；旧成果未匹配时不能自动发新付费请求。 |
| 2 | `AndroidWorkflowHost.publishPage` 普通发布未传 revision 前置条件；`TranslationQueueCoordinator.canPublish` 比较任务状态但不比较人工成果版本。人工保存先完成时，后到的机器保存仍可能覆盖。`cancel` 与发布也未共用同一状态提交边界。 | 页准入时捕获成果版本并做发布 CAS，明确人工正式成果/重译覆盖范围；设计可控的“人工保存后机器迟到”“取消发生在发布检查之后”用例，保护更新版本。不能只加一把锁就声称竞态解决。 |
| 3 | `ExportTask` 入队捕获格式/目录，`prepareSnapshot` 首次执行时才取成果；渲染使用成果保存时的 `renderSettings`。没有任务创建时的语义身份和当前样式快照。 | 在创建任务时捕获导出配置，重试/恢复沿用同一快照；覆盖入队等待期间改样式、已抓取后改源文件、恢复任务。复用现有 journal，不重建导出系统。 |

可复用的设备测试：`ReaderPageArtifactStoreTest`、`LocalPageTranslationIntegrationTest`、`QueueSchedulingIntegrationTest`、`ExportIntegrationTest`。本轮仅读取其相关覆盖，不宣称已运行这些用例。DS 原报告有将 `TranslationQueueCoordinator.canPublish/pagePublished` 行号写到 `AndroidWorkflowHost` 的归属笔误，本表按实际类归属复核后记录。

## 1.5 已有能力与缺口

- 已有：仅工作流含 API 才要求 API 配置；本地工作流不依赖 API。入队校验结构和 API 绑定；本地翻译按节点与语言方向查模型路由。失败队列重试保留原快照。
- 缺口：`PageTranslationDialog` 缺本地模型时禁用确认，只有提示，没有直接下载并返回原任务的动作；`MangaDetailViewModel.enqueue` 捕获快照/API 错误时主要给瞬时消息。需共用可操作的准备结果，定位缺失项并保留待继续的任务参数。
- 缺口：API 没有明确的图片能力预检，不能用一次真实付费调用来探测。漫画级 `withApiOverride` 当前会覆盖所有 API 节点，界面虽说明范围，但混合视觉/文本流程仍需按兼容能力收窄。
- 恢复边界：删除并重建 API 配置后，重试仍引用旧 profile ID。现有可行路径是取消旧任务、重新选择配置并入队；新配置补齐后的继续路径尚未统一。

上述 1.4 缺口已在第二轮接入保护和导出捕获；旧成果不匹配时保留并停下，批量强制覆盖入口未新增。当前下一项为 1.5 准备/补齐继续路径；本文的早期缺口描述继续作为实施依据与历史证据保留。完整插件平台、Agent、视频输入场景和 iOS 按最终计划的后续批次执行。
