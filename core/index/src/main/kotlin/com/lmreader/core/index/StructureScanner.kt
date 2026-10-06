package com.lmreader.core.index

import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.ChildNode
import com.lmreader.core.model.ContentTree
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.MangaAvailability
import com.lmreader.core.model.MangaRecord
import com.lmreader.core.model.MetadataCandidate
import com.lmreader.core.model.MetadataOwnerType
import com.lmreader.core.model.MimeTypes
import com.lmreader.core.model.StableId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 结构扫描器。纯算法：只通过 [ContentTree] 读取目录，不认识 SAF/Room/Compose。
 *
 * 调用约定：每个来源同时只有一个扫描任务（开发文档 6.3）；本类不做并发控制，
 * 由调度方保证。事件必须边发现边发出，不能全部收集完再发（开发文档 6.1）。
 *
 * **工厂必须按次传入**：扫描器实例可以被多个来源复用（算法本身无状态），
 * 因此它不能持有某一个来源的 [TreeFactory]。构造参数只是默认值，
 * [scan] 的 `factory` 会覆盖它。留这个默认值是为了让单元测试可以写成
 * `StructureScanner(fakeFactory)`；生产代码必须显式传工厂——真机上曾因为
 * "扫描器持有占位工厂、调用方忘了传真实工厂"导致每个子目录都打不开却没有任何异常。
 */
class StructureScanner(
    private val defaultTreeFactory: TreeFactory,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 扫描一棵已授权的内容树，并按发现顺序回调 [events]。
     *
     * @param factory 本次扫描使用的工厂（按该来源的授权树构造）；默认用构造时的工厂。
     * 返回的 [ScanSummary] 是本次运行的汇总；`completed = false` 表示被取消或
     * 存在 IO 失败，落库方据此禁止删除判定（开发文档 6.2）。
     */
    suspend fun scan(
        request: ScanRequest,
        root: ContentTree,
        factory: TreeFactory = defaultTreeFactory,
        events: suspend (ScanEvent) -> Unit,
    ): ScanSummary = ScanRun(request, root, factory, events, clock).execute()
}

/** 一次扫描的可变状态；扫描器本身无状态，同一实例可被多个来源复用。 */
private class ScanRun(
    private val request: ScanRequest,
    private val root: ContentTree,
    private val factory: TreeFactory,
    private val events: suspend (ScanEvent) -> Unit,
    private val clock: () -> Long,
) {
    private val fullyEnumerated = LinkedHashSet<String>()
    private val candidates = ArrayList<MetadataCandidate>()
    private val diagnostics = ArrayList<String>()
    private val failedPaths = LinkedHashSet<String>()
    private val visited = LinkedHashSet<String>()
    private var mangaCount = 0
    private var chapterCount = 0
    private var cancelled = false

    /**
     * 调用 [isLeafImageChapter] 的次数，即"为了确认章节而打开并检查子目录"的次数。
     *
     * 它是"找到一个章节就跳过其余文件夹"这条规则的**可观测指标**：
     * 真机实测 762 部漫画时为 1823 次（每部约 2 次：一次探测作品目录本身不是章节，
     * 一次在其内部命中第一章）。这个数字显著大于漫画数就说明跳过规则没有生效，
     * 因此把它随扫描结果一起报出来，而不是只留在开发者的脑子里。
     */
    var leafProbes: Int = 0
        private set

    private val rootRef = DirRef(
        tree = root,
        documentId = request.rootDocumentId,
        name = root.rootName,
        path = request.displayPath.ifBlank { root.rootName },
    )

    suspend fun execute(): ScanSummary {
        try {
            // 两种解释（图片目录 / CBZ-ZIP-PDF）共用**一次**遍历：一个目录的直接章节是
            // 「它直接含的归档文件」与「它直接子目录里的叶子图片目录」的并集，有章节它就是
            // 一部漫画。`request.sourceKind` 因此不再决定扫描行为，只决定卡片的身份与徽标
            // （用户要求：不要再让用户为了一个目录去两张表各加一遍）。
            when (request.layoutMode) {
                LayoutMode.MULTI_CHAPTER -> scanManga(rootRef, depth = 0)
                LayoutMode.SINGLE_CHAPTER -> scanSingle(rootRef, isRoot = true)
            }
        } catch (cancellation: CancellationException) {
            // 取消不向外抛：ScanSummary.completed 就是「不完整」的表达方式
            // （开发文档 6.2「取消不能把未完成结果标记为完整」）。工作已经在
            // 当前挂起点停止，不会再有新事件产生。
            cancelled = true
        }
        return ScanSummary(
            generation = request.generation,
            mangas = mangaCount,
            chapters = chapterCount,
            directoriesVisited = visited.size,
            diagnostics = diagnostics.toList(),
            failedPaths = failedPaths.toList(),
            completed = !cancelled && failedPaths.isEmpty(),
            leafChapterProbes = leafProbes,
        )
    }

    // ---------------------------------------------------------------- 多章节

    /**
     * 多章节：**图片目录与归档共用同一条判定**。
     *
     * 一个目录的直接章节 = 「它直接含的 CBZ/ZIP/PDF」∪「它直接子目录里的叶子图片目录」
     * （[isLeafImageChapter]，即没有子目录、没有归档、至少一张图片）。只要有一个章节，
     * 这个目录就是一部漫画。这样同一个目录（例如 Tachiyomi 的 `/Tachiyomi/local`，里面
     * 既有"章节是文件夹"的作品、也有"章节是压缩包"的作品）**一条来源就能扫全**，
     * 不再需要用户把它在两张表里各加一遍。
     *
     * 判定与停止规则（用户明确要求，也是这个模式唯一必要的成本）：
     * 1. 先看直接归档文件：**零额外 IO**（子项列表已经在手），把**全部**直接归档登记为
     *    章节，并因此可以声明章节数已知（开发文档 5.3 第 10 行）；
     * 2. 否则逐个检查直接子目录，**找到第一个** [isLeafImageChapter] 就成立；
     * 3. 一旦成立，**该目录下的其余子项全部跳过**：它们要么是同一部作品的其它章节，
     *    要么是这部作品的附属内容，都不是新的漫画。因此每部作品最多只打开一个子目录，
     *    而不是枚举几百个章节文件夹。
     *
     * 第 2 条成立的前提是"章节"判得准。[isLeafImageChapter] 因此要求子目录
     * **既没有子目录、也没有 CBZ/ZIP/PDF**：真机 `/Tachiyomi/local` 里
     * `Jyminish  OOHS/`（`cover.jpg` + 两个章节 `.zip`，没有子目录）曾被当成父目录的
     * 一个章节，于是授权根被判成一部叫 `local` 的漫画、其余 41 个子文件夹全部没被检查；
     * 现在它的章节是那两个压缩包，它自己就是一部漫画。
     *
     * 反过来说：判定"这里不是漫画"必须把直接子目录都检查完（否则会把漫画误判成
     * 包裹目录，继续往下把「第一章」当成新作品）。
     *
     * 与开发文档的偏离都记录在此，避免以后被当成 bug 改回去：
     * - **图片目录**漫画的锚点章节是"枚举/排序后第一个被确认为叶子的子目录"，
     *   不保证是自然序第一章（归档漫画不适用：第 1 条会登记全部归档）。封面与简介由
     *   补全阶段按自然序重取（开发文档 7.2），不影响展示；
     * - 图片目录漫画被判定后仍会跳过其余子目录（开发文档 5.1 第 12 行的样例属于这种
     *   情况，本实现按用户要求以跳过换取性能）；但**归档**漫画不会：归档不是它的章节，
     *   所以子目录照常继续扫描——否则站点目录里一个顺手下载的 `.zip` 会吞掉整个站点；
     * - 混放目录（既直接含归档、又含图片子目录）只取归档为章节，发诊断但继续，
     *   按开发文档 5.1 第 6/11 行的"优先解释为多章节"处理。
     *
     * depth 用于实现"未勾选子目录时只把根自身及根的直接子目录当作漫画候选"：
     * depth == 0 的那一层永远要进，再深才看 recursive。
     */
    private suspend fun scanManga(dir: DirRef, depth: Int) {
        val children = dir.enumerateOnce() ?: return
        val childDirs = children.filter { it.isDirectory }
        val hasDirectImage = children.any { it.isSupportedImage() }

        if (hasDirectImage && childDirs.isNotEmpty()) {
            diagnose(dir.path, MESSAGE_MIXED)
        }

        // 1) 直接含归档文件：**全部**按名称自然序登记为章节；子目录不当作章节，
        //    而是继续向下扫描（同级的 `.zip` 与作品目录可以共存，见下方说明）。
        //
        //    为什么这里不像子目录那样"只取第一个"：归档文件的章节清单**零额外 IO**——
        //    子项列表已经在第 150 行拿到手，逐个归档变成 ChapterSpec 只是内存里的 map，
        //    不需要打开压缩包。真正昂贵的是"枚举每个归档的页数、打开每个 PDF"，
        //    那才是「深入」阶段的事（开发文档 6.1）。这条分支曾经只取自然序第一个
        //    并声明 `chaptersFullyEnumerated = false`，后果是开发文档 5.3 第 10 行
        //    的样例（`作品/01.cbz、02.zip、03.pdf` 应为「共 3 章」）永远做不到：
        //    唯一能补齐的 `ChapterResolver` 只挂在详情页的「更新章节」上，扫描管线从不
        //    自动调用它。于是真机上 `NovaSamus/` 的 59 个 zip 只索引出 1 个。
        //
        //    更严重的是它还会**破坏用户数据**：`scanManga` 通过 `enumerateOnce()`
        //    已经把本目录记入 `fullyEnumerated`，而 `upsertScanResult` 的删除判定
        //    只看 `anchorEnumerated`。用户手动「更新章节」补齐 60 章之后，下一次扫描
        //    重发这份只有 1 章的清单，会把另外 59 个章节行删掉，连带删掉引用它们的
        //    阅读进度（`reading_progress` 没有外键级联）。
        //
        //    因为归档完备而断言"章节数已知"是成立的：本分支已经列出了该目录的
        //    **全部**直接归档，而子目录按下面的规则不算本目录的章节（它们会作为
        //    更深的作品各自被发现），因此这个声明不会被"目录里还有子目录"推翻。
        val archives = children
            .filter { it.isArchiveFile() }
            .sortedWith(CHAPTER_NAME_ORDER)
        if (archives.isNotEmpty()) {
            emitManga(
                anchor = dir,
                chapters = archives.map { archive ->
                    ChapterSpec(
                        documentId = archive.documentId,
                        title = MimeTypes.nameWithoutExtension(archive.name),
                        kind = ChapterKind.ARCHIVE,
                        modifiedAt = archive.lastModified,
                    )
                },
                anchorChildren = children,
                chaptersFullyEnumerated = true,
            )
            // 本目录的章节是这些压缩包，但它的**子目录不是章节**：目录里同时有压缩包和子目录时，
            // 子目录更可能是更深的作品目录。旧实现在这里直接 return，于是一个"站点目录里躺着一个
            // 顺手下载的 .zip"的形态会把整个站点压成一张以站点命名的卡片，站点下所有漫画再也
            // 不会被发现。真机样例：`/Tachiyomi/downloads/Sunday Web Every (JA)/` 里同时有
            // `ジャイアントお嬢様.zip` 与 `ジャイアントお嬢様/`（224 章）、`メガトンチルドレン/`，
            // 结果另一站点同名的那部被整站吞掉，搜索只剩一部。因此这里发诊断后继续向下扫描。
            if (childDirs.isNotEmpty()) {
                diagnose(dir.path, MESSAGE_ARCHIVE_MIXED)
                descend(dir, childDirs, depth)
            }
            return
        }

        // 2) 先按名称自然序排候选，再"找到第一个叶子就停"：
        // - 排序让锚点章节**确定、且通常是第一章**，而不是枚举顺序碰巧返回的那个
        //   （真机上出现过先返回「第10话」，会让封面在不同设备上不一致）；
        // - 只对排序后的前几项做叶子判定，命中即 break，其余子文件夹全部跳过。
        // 排序不读磁盘（一次 listChildren 已在上面完成），成本可忽略。
        for (child in childDirs.sortedWith(CHAPTER_NAME_ORDER)) {
            // 每轮都检查取消：父目录的 children 已经在内存里，循环体本身不必然挂起，
            // 不显式检查就会出现"取消之后又冒出一部漫画"（验收 A09 要求无错序结果）。
            currentCoroutineContext().ensureActive()
            // 叶子判定本来就要列一次这个子目录，页数就用那份**已经在手里**的列表数，
            // 零额外 IO（用户口径："更新章节的同时应该要数每章页数"，扫描这一章同理）。
            val pages = leafChapterPageCount(dir, child) ?: continue
            emitManga(
                anchor = dir,
                chapters = listOf(
                    ChapterSpec(
                        documentId = child.documentId,
                        title = child.name,
                        kind = ChapterKind.IMAGE_DIRECTORY,
                        modifiedAt = child.lastModified,
                        pageCount = pages,
                    ),
                ),
                anchorChildren = children,
                // 只探测到一个章节，因此章节数是"已知下限"而不是准确总数
                // （开发文档 5.1「不得把探测到一章伪报成完整的一章」）。
                chaptersFullyEnumerated = false,
            )
            // 找到一个章节就够：其余子文件夹全部跳过。
            return
        }

        if (depth == 0 && childDirs.isEmpty() && hasDirectImage) {
            // 根特例：授权根本身就是叶子图片目录，说明用户选中的是单章节本体
            // （开发文档 5.1 第 3 行）。只对根生效，不把任意深层叶子误认成独立漫画。
            diagnose(dir.path, MESSAGE_ROOT_LEAF)
            emitManga(
                anchor = dir,
                chapters = listOf(ChapterSpec(request.rootDocumentId, dir.name, ChapterKind.IMAGE_DIRECTORY)),
                anchorChildren = children,
                chaptersFullyEnumerated = true,
            )
            return
        }
        if (depth == 0 && childDirs.isEmpty()) {
            diagnose(dir.path, MESSAGE_EMPTY_ROOT)
            return
        }

        // 走到这里说明这个目录不是漫画（没有直接章节），继续向下找。
        descend(dir, childDirs, depth)
    }

    /**
     * 把 [children] 当作漫画候选继续向下扫描。
     *
     * 递归开关只在**根的直接子目录以下**生效：`depth == 0` 那一层永远要进。
     * 已判定为漫画的目录也要用同一套规则检查它剩下的子目录（见归档分支），
     * 否则"站点目录里有一个压缩包"这种形态会把整个站点吞成一张卡片。
     */
    private suspend fun descend(dir: DirRef, children: List<ChildNode>, depth: Int) {
        if (!request.recursive && depth > 0) return
        for (child in children) {
            // 打开下一个目录之前检查取消：`openChild` 与随后的枚举都可能真的落盘，
            // 而循环本身不必然挂起——不检查就会出现"取消之后又读了一个目录"
            // （验收 A09：取消后不得继续产生结果）。
            currentCoroutineContext().ensureActive()
            val sub = openChild(dir, child) ?: continue
            scanManga(sub, depth + 1)
        }
    }

    // ---------------------------------------------------------------- 单章节

    /**
     * 单章节：**图片目录与归档共用同一条判定**（框架 4.3 第二段）。
     *
     * 一个目录出卡片的方式有两种，互不冲突：
     * 1. 它**直接含的每个 CBZ/ZIP/PDF** 各自是一本共 1 章的漫画，章节标题 = 去扩展名的
     *    文件名（开发文档 1.3「归档单章节」）；
     * 2. 它自己**没有子目录、没有归档、且至少有一张图片**时，它是一本共 1 章的漫画，
     *    唯一的章节就是它自己。
     *
     * 为了让 `作者/短篇/001.jpg` 得到「短篇」而不是「作者」（开发文档 5.3 第 7、13 行的
     * 示例结构），中间容器的判定是：**只要还有子目录就继续向下**——既有图片又有子目录的
     * 目录既不是章节也不是漫画，它只是包裹目录，发诊断后继续递归（开发文档 5.1）。
     *
     * 根自身是叶子图片目录因此自然覆盖：根没有子目录且直接含图片时，它自己就是唯一章节
     * （开发文档 5.1 根特例），不需要额外分支。
     */
    private suspend fun scanSingle(dir: DirRef, isRoot: Boolean) {
        val children = dir.enumerateOnce() ?: return
        val childDirs = children.filter { it.isDirectory }
        val hasDirectImage = children.any { it.isSupportedImage() }
        // 直接含 CBZ/ZIP/PDF 的目录不按"图片单章节"处理：它的章节是那些压缩包，
        // 而一张 `cover.jpg` 会让它看起来像"有图片且没有子文件夹"（Tachiyomi 本地库里
        // "章节就是压缩包"的漫画文件夹正是这种形态）。判别条件少了这一条时，
        // 父目录会被误判成单章节、其余子文件夹全部被跳过。
        val hasDirectArchive = children.any { it.isArchiveFile() }

        // 每个直接归档文件自身是一张卡片：卡片与章节共用同一物理身份。
        for (file in children.filter { it.isArchiveFile() }) {
            val title = MimeTypes.nameWithoutExtension(file.name)
            emitManga(
                anchor = DirRef(dir.tree, file.documentId, title, "${dir.path}/${file.name}"),
                chapters = listOf(ChapterSpec(file.documentId, title, ChapterKind.ARCHIVE, file.lastModified)),
                anchorChildren = emptyList(),
                chaptersFullyEnumerated = true,
            )
        }

        if (childDirs.isEmpty()) {
            when {
                hasDirectImage && !hasDirectArchive -> emitManga(
                    anchor = dir,
                    chapters = listOf(
                        ChapterSpec(
                            documentId = dir.documentId,
                            title = dir.name,
                            kind = ChapterKind.IMAGE_DIRECTORY,
                            modifiedAt = dir.modifiedAt,
                            // 图片列表已经在手里（上面 enumerateOnce 拿到了），页数顺手就有了。
                            pageCount = children.count { it.isSupportedImage() },
                        ),
                    ),
                    anchorChildren = children,
                    // 单章节模式下一张卡片就是这一个目录，章节数是结构定义。
                    chaptersFullyEnumerated = true,
                )

                isRoot && !hasDirectImage && !hasDirectArchive -> diagnose(dir.path, MESSAGE_EMPTY_ROOT)
            }
            return
        }

        if (hasDirectImage) diagnose(dir.path, MESSAGE_MIXED)
        // 未勾选子目录时只把根自身及根的直接子目录当作候选；更深的叶子目录属于
        // "候选内部的章节层"，不越过递归开关（开发文档 5.1）。
        if (!request.recursive && !isRoot) return
        for (child in childDirs) {
            val sub = openChild(dir, child) ?: continue
            scanSingle(sub, isRoot = false)
        }
    }

    // ---------------------------------------------------------------- 基础设施

    /**
     * 记录"正在枚举"的目录并发出进度事件。
     *
     * 每次都发（不只是首次访问）：界面要显示"正在扫描：<当前目录>"，
     * 只在首次访问时发会让这一行停在上一个目录上不动
     * （用户要求：扫描中显示正在扫描的路径）。
     */
    private suspend fun markVisited(documentId: String, path: String?) {
        currentCoroutineContext().ensureActive()
        visited += documentId
        events(
            ScanEvent.Progress(
                directoriesVisited = visited.size,
                mangasDiscovered = mangaCount,
                chaptersDiscovered = chapterCount,
                currentPath = path,
            ),
        )
    }

    /**
     * 完整枚举直接子项。成功即记入 [fullyEnumerated]（开发文档 6.2 的删除判定前提），
     * 失败只影响该分支并让 `completed = false`，不阻断其它作品（验收 A07）。
     */
    private suspend fun enumerate(dir: DirRef): List<ChildNode>? {
        markVisited(dir.documentId, dir.path)
        return try {
            dir.tree.listChildren().also { fullyEnumerated += dir.documentId }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            fail(dir.path, error)
            null
        }
    }

    private suspend fun openChild(parent: DirRef, child: ChildNode): DirRef? {
        val path = "${parent.path}/${child.name}"
        markVisited(child.documentId, path)
        return try {
            val tree = factory.open(child)
            if (tree == null) {
                fail(path, null)
                null
            } else {
                DirRef(tree, child.documentId, child.name, path, child.lastModified)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            fail(path, error)
            null
        }
    }

    /**
     * leafChapter(d)：直接子项中至少有一张受支持图片、**没有**子目录、且**没有** CBZ/ZIP/PDF
     * （开发文档 5.1）。
     *
     * 判定顺序是「先确认没有子目录，再确认有图片」（框架 4.3）：先看到图片不能
     * 断言没有子目录（验收 A04）。这里用 [ContentTree.hasDirectoryChildren] 提前
     * 短路，避免为判定叶子而枚举整棵子树。
     *
     * **为什么要排除含归档的目录**（真机实测补上的规则）：Tachiyomi 的本地库里，
     * "章节就是压缩包"的漫画文件夹长这样：`漫画名/cover.jpg` + `漫画名/第1话.zip`……
     * 它没有子目录，唯一的一张图片是封面。若按"有图片且没有子目录"判定它就是单章节，
     * 它的**父目录**（来源目录或授权根）会被判成漫画、其余子文件夹全部跳过——真机
     * `/Tachiyomi/local`（51 个子文件夹）因此只扫出 1 张名叫 `local` 的卡片。
     * 正确解释是：它是**一部漫画**，章节是那些压缩包（现在由 [scanManga] 的第一条规则
     * 在同一个来源里产出，不再需要另一张表）。
     */
    /**
     * 子目录是不是"叶子图片目录"；是的话返回它的**页数**（图片张数），不是则 null。
     *
     * 返回值从 `Boolean` 改成"页数或 null"是为了顺手把页数带走：判定本来就要枚举一次
     * 这个子目录，那份列表已经在手里，数图片不额外读盘。页数口径与阅读器
     * `PageSource` 一致（`isSupportedImage`），因此详情页显示的"共 X 页"与打开后
     * 实际能翻的页数不会分叉。
     */
    private suspend fun leafChapterPageCount(parent: DirRef, child: ChildNode): Int? {
        leafProbes++
        val ref = openChild(parent, child) ?: return null
        return try {
            // 用一次枚举同时回答"有没有子目录"和"有没有图片"，并把它缓存在 ref 上：
            // 若判定为章节就到此为止（用户要求的跳过），若判定为包裹目录，
            // 后面的递归会直接复用这份结果，不再重读同一个目录。
            val children = ref.enumerateOnce() ?: return null
            when {
                children.any { it.isDirectory } -> null
                children.any { it.isArchiveFile() } -> null
                else -> children.count { it.isSupportedImage() }.takeIf { it > 0 }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            fail("${parent.path}/${child.name}", error)
            null
        }
    }

    /** 取该目录的子项，命中缓存则不重读磁盘。 */
    private suspend fun DirRef.enumerateOnce(): List<ChildNode>? {
        cachedChildren?.let { return it }
        val listed = enumerate(this) ?: return null
        cachedChildren = listed
        return listed
    }

    private suspend fun diagnose(path: String, message: String) {
        diagnostics += "$path：$message"
        events(ScanEvent.Diagnostic(path, message))
    }

    private suspend fun fail(path: String, error: Exception?) {
        val message = error?.let { failure ->
            // 带上异常类型：`SecurityException: Permission Denial…` 与
            // `FileNotFoundException: …` 需要完全不同的处理，只留 message
            // 会把两者显示成同一句话，真机排障时无法区分。
            val text = failure.message?.takeIf { it.isNotBlank() } ?: "无附加信息"
            "${failure::class.simpleName}：$text"
        } ?: "无法打开该目录"
        failedPaths += path
        events(ScanEvent.Failed(path, message))
    }

    /**
     * 发出一条漫画发现事件，并立刻把当前累计的 [ScanResult] 交给调用方。
     *
     * `fullyEnumeratedContainers` 取「此刻已完整枚举」的快照：每次事件里的锚点目录
     * 一定包含在内（章节只可能是锚点的直接子项），因此章节删除判定不会因为
     * 分批发事件而失去依据。
     */
    private suspend fun emitManga(
        anchor: DirRef,
        chapters: List<ChapterSpec>,
        anchorChildren: List<ChildNode>,
        /**
         * 章节清单是否已完整枚举。
         *
         * 发现阶段只探测**一个**直接章节就足以断定「这是一部漫画」，因此章节数是
         * 「已知下限」而不是总数（开发文档 5.1「不得把探测到一章伪报成完整的一章」）。
         * 完整清单属于「深入」阶段（开发文档 6.1 第 3 步、详情页的「更新章节」）。
         */
        chaptersFullyEnumerated: Boolean,
    ) {
        val now = clock()
        val mangaId = StableId.mangaId(anchor.documentId, request.sourceKind)
        val chapterRecords = chapters.map { spec ->
            ChapterRecord(
                chapterId = StableId.chapterId(spec.documentId, spec.kind),
                mangaId = mangaId,
                documentId = spec.documentId,
                kind = spec.kind,
                title = spec.title,
                sortKey = NaturalOrder.sortKey(spec.title),
                // position 由落库时的插入计划决定（ChapterOrdering）：
                // 扫描器不知道用户选的是哪种排序，这里给 0 只是占位。
                position = 0L,
                modifiedAt = spec.modifiedAt,
                // 页数只在"叶子判定时顺带数到"的章节上有（见 ChapterSpec.pageCount）；
                // 归档与未探测的章节仍是 null——不假装已知（开发文档 6.1）。
                pageCount = spec.pageCount,
                // 封面由补全阶段填（框架 6.3 / 开发文档 7.2），发现阶段不解码图片。
                coverDocumentId = null,
                contentRevision = INITIAL_CONTENT_REVISION,
                discoveredAt = now,
            )
        }.sortedWith(CHAPTER_ORDER)

        collectMetadataCandidates(anchor, chapters, anchorChildren)

        val manga = MangaRecord(
            mangaId = mangaId,
            anchorDocumentId = anchor.documentId,
            // 重叠授权时由调用方保证 request.sourceId 已是 effectiveSourceId（开发文档 6.4）。
            sourceId = request.sourceId,
            sourceKind = request.sourceKind,
            layoutMode = request.layoutMode,
            displayName = anchor.name,
            author = null,
            hasMetadata = false,
            summary = null,
            coverDocumentId = null,
            coverChapterId = null,
            // 只有真正枚举完章节候选时才声明「章节数已知」；
            // 否则界面显示「已发现 N 章，更新中」，不把下限当总数。
            chapterCount = chapterRecords.size,
            chapterCountKnown = chaptersFullyEnumerated,
            availability = MangaAvailability.AVAILABLE,
            discoveryGeneration = request.generation,
            discoveredAt = now,
            updatedAt = now,
        )

        val result = ScanResult(
            sourceId = request.sourceId,
            sourceKind = request.sourceKind,
            sourceRevision = request.sourceRevision,
            generation = request.generation,
            manga = manga,
            chapters = chapterRecords,
            fullyEnumeratedContainers = fullyEnumerated.toSet(),
            metadataCandidates = candidates.toList(),
        )
        mangaCount++
        chapterCount += chapterRecords.size
        events(ScanEvent.MangaDiscovered(result = result, totalDiscovered = mangaCount))
    }

    /**
     * 登记元数据候选位置（框架 4.1）。
     *
     * 发现阶段只登记**已经看到**的位置，不打开归档：归档页清单是「深入」阶段的事
     * （开发文档 6.1），打开每个 CBZ 去猜 ComicInfo 会把发现阶段变成解压阶段。
     */
    private fun collectMetadataCandidates(
        anchor: DirRef,
        chapters: List<ChapterSpec>,
        anchorChildren: List<ChildNode>,
    ) {
        for (spec in chapters) {
            if (spec.kind != ChapterKind.ARCHIVE) continue
            candidates += MetadataCandidate(
                ownerDocumentId = spec.documentId,
                ownerType = MetadataOwnerType.CHAPTER,
                archiveMemberPath = null,
                label = "${spec.title}：归档内 ComicInfo.xml（补全阶段打开归档后定位成员路径）",
            )
        }

        val comicInfo = anchorChildren.firstOrNull {
            !it.isDirectory && ComicInfoParser.isComicInfoFileName(it.name)
        } ?: return

        val selfChapter = chapters.singleOrNull()?.takeIf { it.documentId == anchor.documentId }
        candidates += if (selfChapter != null) {
            // 单章漫画：目录里的 ComicInfo.xml 就是这一章自己的元数据（开发文档 7.1 第 1 条）。
            MetadataCandidate(
                ownerDocumentId = selfChapter.documentId,
                ownerType = MetadataOwnerType.CHAPTER,
                archiveMemberPath = null,
                label = "${selfChapter.title} ComicInfo.xml",
            )
        } else {
            // 多章漫画：顶层 ComicInfo.xml 只在第一章没有 XML 时兜底（开发文档 7.1 第 3 条）。
            MetadataCandidate(
                ownerDocumentId = anchor.documentId,
                ownerType = MetadataOwnerType.MANGA,
                archiveMemberPath = null,
                label = "漫画顶层兜底",
            )
        }
    }

    /** 目录引用：树 + 稳定身份 + 展示名 + 诊断用路径。 */
    private class DirRef(
        val tree: ContentTree,
        val documentId: String,
        val name: String,
        val path: String,
        /**
         * 目录自身的修改时间；null = 提供方没给（根目录就属于这种：它是授权根，
         * 不是某个父目录的子项，扫描器拿不到它的 ChildNode）。
         *
         * 只用于「按修改时间排序」：章节是图片目录时，它的"修改时间"就是目录的 mtime；
         * 章节是归档文件时用文件自己的 mtime（见 emitManga 的调用点）。
         */
        val modifiedAt: Long? = null,
    ) {
        /**
         * 已枚举的子项缓存。
         *
         * 每个目录在一次扫描里会被访问两次：一次是父目录判断"它是不是章节"
         * （isLeafImageChapter），一次是真正递归进去。缓存让第二次不再重读磁盘——
         * 真机上这是扫描耗时里最容易被忽略的一半（File.listFiles 与 SAF 查询都不便宜）。
         *
         * 生命周期只到本次扫描结束：ContentTree 实例本身是按次创建的，
         * 因此不存在"读到过期目录内容"的风险（开发文档 6.1 发现阶段只读元数据）。
         */
        var cachedChildren: List<ChildNode>? = null
    }

    private data class ChapterSpec(
        val documentId: String,
        val title: String,
        val kind: ChapterKind,
        /** 目录/归档文件的修改时间；排序抽屉的「按修改时间排序」用它。 */
        val modifiedAt: Long? = null,
        /**
         * 页数；null = 本次没数出来（归档要打开压缩包才知道，属于 P2 的缺口）。
         *
         * 只有"判定叶子图片目录时列表已经在手里"的那些章节填得上——发现阶段只探测
         * 一个章节，所以多章节模式下通常是第一章。完整清单由详情页的「更新章节」出，
         * 那条路径同样顺手数页数（`ChapterResolver`）。
         */
        val pageCount: Int? = null,
    )

    private companion object {
        /** 章节顺序按自然序（框架 4.3「排序」）；标题相同时用 documentId 保证确定性。 */
        val CHAPTER_ORDER = Comparator<ChapterRecord> { left, right ->
            val byTitle = NaturalOrder.compare(left.title, right.title)
            if (byTitle != 0) byTitle else left.documentId.compareTo(right.documentId)
        }

        const val INITIAL_CONTENT_REVISION = 1L

        /**
         * 候选子目录的检查顺序：名称自然序。
         *
         * 只影响"先检查哪一个"，不改变"找到一个章节就停、其余全部跳过"的规则；
         * 目的是让被选中的锚点章节确定且通常是第一章（目录枚举顺序在真实文件系统上
         * 不保证，真机上出现过先返回「第10话」）。
         */
        val CHAPTER_NAME_ORDER = Comparator<ChildNode> { a, b -> NaturalOrder.compare(a.name, b.name) }

        const val MESSAGE_MIXED = "目录同时包含图片和子目录，未按叶子章节处理（开发文档 5.1）"
        const val MESSAGE_ARCHIVE_MIXED = "目录同时包含压缩包和子目录：压缩包按本目录的章节，子目录继续作为漫画扫描（开发文档 5.1）"
        const val MESSAGE_ROOT_LEAF = "根目录自身是叶子图片目录，按根特例生成共 1 章的漫画（开发文档 5.1）"

        /**
         * 根目录里既没有图片也没有归档：不生成卡片。
         *
         * 两种解释已经合并，所以提示同时提到两边，否则用户会以为"只有图片才算数"
         * ——归档章节现在同样由这一次扫描产出。
         */
        const val MESSAGE_EMPTY_ROOT = "目录内没有受支持的图片，也没有 CBZ/ZIP/PDF，未生成卡片（开发文档 5.1/5.2）"
    }
}
