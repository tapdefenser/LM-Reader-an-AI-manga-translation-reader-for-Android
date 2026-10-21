package com.lmreader.core.storage.scan

import com.lmreader.core.index.ComicInfoFields
import com.lmreader.core.index.ComicInfoParser
import com.lmreader.core.index.SummaryBuilder
import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.ContentTree
import com.lmreader.core.model.MangaMetadataUpdate
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.MetadataOwnerType
import com.lmreader.core.model.MetadataRecord
import com.lmreader.core.model.SourcePermissionState
import com.lmreader.core.model.ResourceGovernor
import com.lmreader.core.model.InferencePriority
import com.lmreader.core.storage.access.TreeAccess
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 补全阶段：为已发现漫画补 ComicInfo 简介与作者（开发文档 6.1 第 2 步、7）。
 *
 * **封面不在这里**：封面改为图库/书架滚动时的懒加载（`CoverResolver`），因为放在
 * 扫描期会让每部漫画都多开一次章节目录，而补全每轮只处理 120 条，几万张卡片永远
 * 轮不到——真机上的表现就是"图库封面几乎全是破图"。
 *
 * 元数据优先级严格按开发文档 7.1：**首章 XML 优先，漫画顶层只作兜底**。不照搬
 * Mihon 把顶层 XML 复制回章节或创建 `.noxml` 的写入逻辑——本应用只读源。
 *
 * 本步覆盖范围（框架第 9 节已知限制）：只处理图片目录章节。归档内部成员清单与
 * PDF 栅格化属于"深入"阶段，因此归档/PDF 漫画在这里不读 XML，卡片显示来源徽标。
 *
 * 并发纪律：开发文档 6.3 要求"用户阅读优先于后台缩略图和批量翻译"，所以补全
 * 由调度层按固定并发（默认 2）驱动，本类自身不做并发控制。
 *
 * ## 读目录为什么必须走 [TreeAccess]（曾在这里踩过一次）
 *
 * 这里原本直接用 `DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)`
 * 拼 SAF URI 查询。在「全部文件访问」模式下 documentId 是**绝对路径**
 * （`/storage/emulated/0/Tachiyomi/local/…`），而 ExternalStorageProvider 的
 * documentId 是 `primary:Tachiyomi/local/…`——两者拼出来的 URI 查不到任何子项，
 * 于是**每一部漫画**都走"没有 XML"分支：`hasMetadata` 永远是 0、简介与作者永远是空。
 * 实测证据（模拟器，同一目录）：
 *
 * ```text
 * document/primary%3ATachiyomi%2Flocal%2F大大的小可爱%2F第1话/children
 *   → 17 行，其中有 ComicInfo.xml
 * document/%2Fstorage%2Femulated%2F0%2FTachiyomi%2Flocal%2F大大的小可爱%2F第1话/children
 *   → No result found.
 * ```
 *
 * 所以这里和扫描、封面、阅读器一样只通过 [TreeAccess] 取树与流：它按当前授权形态
 * 选择直接文件访问或 SAF，documentId 的口径由它统一，本类不认识具体的提供方。
 *
 * ## 依赖为什么是两个函数
 *
 * 与 [com.lmreader.core.storage.cover.CoverResolver] 同样的理由：本类只做
 * "按树 URI + documentId 列目录、读一个文件"两件事，收窄成两个函数之后它不认识
 * Android 侧的具体实现，纯 JVM 测试就能覆盖"首章有 XML / 首章没有、顶层兜底 /
 * 目录读不到"这些分支。
 */
class MetadataBackfillWorker(
    private val openTree: (treeUri: String, documentId: String) -> ContentTree?,
    private val openInputStream: (treeUri: String, documentId: String) -> InputStream?,
    private val mangaRepository: MangaRepository,
    private val admission: (suspend (List<String>) -> Int)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 生产装配用这个：目录与文件都只通过统一的树入口打开。 */
    constructor(
        treeAccess: TreeAccess,
        mangaRepository: MangaRepository,
        admission: (suspend (List<String>) -> Int)? = null,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        openTree = { treeUri, documentId -> treeAccess.openAt(treeUri, documentId) },
        openInputStream = { treeUri, documentId -> treeAccess.openInputStream(treeUri, documentId) },
        mangaRepository = mangaRepository,
        admission = admission,
        clock = clock,
    )

    /**
     * 补全一批漫画，返回真正写入新信息的数量。
     *
     * 单部失败不阻断其它作品（开发文档 3）：逐部捕获异常。授权失效的来源整体跳过，
     * 因为继续尝试只会为每部作品重复一次 SecurityException（验收 A07）。
     */
    suspend fun backfill(mangaIds: List<String>): Int = admission?.invoke(mangaIds) ?: backfillDirect(mangaIds)

    /** Only the registered executor (or an unconfigured test worker) runs a batch directly. */
    suspend fun backfillDirect(mangaIds: List<String>, progress: suspend (Int, Int) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.IO) {
        var updated = 0
        val brokenSources = mutableSetOf<String>()
        for ((index, mangaId) in mangaIds.withIndex()) {
            currentCoroutineContext().ensureActive()
            progress(index, updated)
            val target = try { mangaRepository.getBackfillTarget(mangaId) } catch(error: CancellationException) { throw error }
                catch(_: Exception) { null } ?: continue
            if (target.sourcePermission == SourcePermissionState.LOST) continue
            if (target.sourceTreeUri in brokenSources) continue
            val changed = try { backfillOne(mangaId) } catch(error: CancellationException) { throw error }
                catch(_: Exception) { brokenSources += target.sourceTreeUri; false }
            if (changed) updated++
        }
        progress(mangaIds.size, updated)
        updated
    }

    /**
     * 补全单部漫画的 ComicInfo；返回是否写入了新信息。
     *
     * **封面不在这里做**（原先在这里，已搬走）：封面现在由图库滚动时的
     * `CoverResolver` 懒加载，扫描只写路径。留在这里会让每部漫画都多开一次章节目录，
     * 而补全每轮只处理 120 条，几万张卡片永远轮不到。
     *
     * @param force 已经探测过的也重读一次。默认 false：本方法现在也会被**图库/书架的
     *   滚动懒加载**调用（见 `CoverMetadataWriter`：更新封面时顺手更新简介），
     *   那里每张卡片都会来一次，没有这条守卫就会把已经读过目录的作品重复枚举。
     *   扫描期的补全队列本来就只取 `metadataProbedAt IS NULL`，传默认值即可。
     */
    suspend fun backfillOne(mangaId: String, force: Boolean = false): Boolean =
        withContext(Dispatchers.IO + InferencePriority(ResourceGovernor.MAINTENANCE)) {
            ResourceGovernor.shared.use("io") { backfillOneDirect(mangaId, force) }
        }

    private suspend fun backfillOneDirect(mangaId: String, force: Boolean): Boolean {
        val target = mangaRepository.getBackfillTarget(mangaId) ?: return false
        // 探过一次就不再开目录（"读到"与"确实没有"都算探过，见 metadataProbedAt 的注释）。
        if (!force && target.manga.metadataProbedAt != null) return false
        val treeUri = target.sourceTreeUri
        val firstChapter = target.chapters.firstOrNull()

        // ComicInfo：首章优先，顶层兜底（开发文档 7.1 第 1–3 条）。
        val records = mutableListOf<MetadataRecord>()
        var chapterRecord: MetadataRecord? = null
        if (firstChapter != null && firstChapter.kind == ChapterKind.IMAGE_DIRECTORY) {
            val xml = readComicInfo(treeUri, firstChapter.documentId)
            if (xml != null) {
                chapterRecord = ComicInfoParser.parse(
                    ownerId = firstChapter.chapterId,
                    ownerType = MetadataOwnerType.CHAPTER,
                    xml = xml,
                    sourceLabel = "第一章 ComicInfo.xml",
                )
                records += chapterRecord
            }
        }

        // 顶层兜底只在首章没有 XML 时才读取：两处都读会给出两个互相矛盾的简介来源。
        // 它对两类真实目录都有用：Mihon 本地源写在漫画顶层的 ComicInfo.xml，
        // 以及多章节里首章是归档（本步不解析归档内部）而顶层有 XML 的情况。
        if (chapterRecord == null) {
            val xml = readComicInfo(treeUri, target.manga.anchorDocumentId)
            if (xml != null) {
                records += ComicInfoParser.parse(
                    ownerId = mangaId,
                    ownerType = MetadataOwnerType.MANGA,
                    xml = xml,
                    sourceLabel = "漫画目录顶层 ComicInfo.xml（首章无 XML 时的兜底，开发文档 7.1 第 3 条）",
                )
            }
        }

        if (records.isEmpty()) {
            // 没有 XML：记为"探测过了、确实没有"，但不清空已有简介
            // （applyMetadataUpdate 的 null 语义就是不覆盖）。
            mangaRepository.applyMetadataUpdate(
                MangaMetadataUpdate(
                    mangaId = mangaId,
                    metadataProbedAt = clock(),
                    searchText = "",
                    at = clock(),
                ),
            )
            return false
        }

        val primary = chapterRecord ?: records.first()
        val summary = SummaryBuilder.build(primary, excludeName = target.manga.displayName)
        // 图库卡片预览取的是**漫画级记录**里的 summary（`COALESCE(md.summary, m.summary)`），
        // 详情页取的是 `mangas.summary`。两处必须是同一段文字：顶层兜底那条记录
        // 在解析时还不知道漫画展示名，若原样落库，图库会显示带「名称：」的一版、
        // 详情页显示去掉它的一版。
        val storedRecords = records.map { record ->
            if (record.ownerId == mangaId) record.copy(summary = summary) else record
        }
        mangaRepository.applyMetadataUpdate(
            MangaMetadataUpdate(
                mangaId = mangaId,
                summary = summary,
                // 作者按上游语义取（开发文档 7、上游参考与复用边界 2）：
                // Mihon/Tachiyomi 是 Writer，EhViewer 的 Writer 是社团、作者在 Penciller。
                author = ComicInfoFields.author(primary.fields),
                hasMetadata = true,
                metadataProbedAt = clock(),
                records = storedRecords,
                // 搜索投影：名称与别名由查询侧另行拼接（本步 search 已同时匹配
                // displayName），这里只负责 XML 侧文本（开发文档 6.4）。
                searchText = primary.normalizedSearchText,
                at = clock(),
            ),
        )
        return true
    }

    /**
     * 读目录下的 ComicInfo.xml 原文。
     *
     * 只查**直接子项**里的 `ComicInfo.xml`：两侧上游都写在漫画目录或章节目录的第一层
     * （Mihon 的 `Downloader.createComicInfoFile` 写在章节目录，EhViewer 的
     * `writeComicInfo` 写在图库目录）。归档内部的成员属于 P2，本步不打开归档。
     *
     * 大小上限在读取时生效（开发文档 7.1「设置 2MiB 默认上限」）：先读满上限 + 1 字节，
     * 解析器再对超限内容给出 parseError，原文仍然保留可展示。
     */
    private suspend fun readComicInfo(treeUri: String, containerDocumentId: String): String? {
        val tree = openTree(treeUri, containerDocumentId) ?: return null
        val target = runCatching { tree.listChildren() }.getOrNull()
            ?.firstOrNull { ComicInfoParser.isComicInfoFileName(it.name) }
            ?: return null
        return runCatching {
            openInputStream(treeUri, target.documentId)?.use { stream ->
                val buffer = ByteArray(ComicInfoParser.MAX_BYTES.toInt() + 1)
                var total = 0
                while (total < buffer.size) {
                    val read = stream.read(buffer, total, buffer.size - total)
                    if (read <= 0) break
                    total += read
                }
                String(buffer, 0, total, Charsets.UTF_8)
            }
        }.getOrNull()
    }
}
