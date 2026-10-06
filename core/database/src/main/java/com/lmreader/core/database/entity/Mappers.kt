package com.lmreader.core.database.entity

import com.lmreader.core.database.dao.CardQueryRow
import com.lmreader.core.index.NaturalOrder
import com.lmreader.core.model.Category
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.LibrarySource
import com.lmreader.core.model.MangaCard
import com.lmreader.core.model.MangaRecord
import com.lmreader.core.model.MangaTranslationSettings
import com.lmreader.core.model.TranslationPageMode
import com.lmreader.core.model.BubbleFillMode
import com.lmreader.core.model.MetadataRecord
import com.lmreader.core.model.ReaderOrientation
import com.lmreader.core.model.ReadingMode
import com.lmreader.core.model.ReadingProgress
import com.lmreader.core.model.ShelfEntry
import com.lmreader.core.model.StyleMode

/**
 * 领域类型 ↔ Room 实体。
 *
 * 手写而不是用 MapStruct 之类生成器：字段数量有限，且转换里包含语义决定
 * （例如 `inShelf` 由 LEFT JOIN 的可空列推导），生成器反而更难看出这些判断。
 */

internal fun LibrarySourceEntity.toDomain(): LibrarySource = LibrarySource(
    sourceId = sourceId,
    kind = kind,
    treeUri = treeUri,
    displayPath = displayPath,
    providerLabel = providerLabel,
    displayName = displayName,
    recursive = recursive,
    mode = mode,
    orderIndex = orderIndex,
    permission = permission,
    revision = revision,
    lastScanAt = lastScanAt,
    lastScanStatus = lastScanStatus,
    lastScanError = lastScanError,
)

internal fun LibrarySource.toEntity(): LibrarySourceEntity = LibrarySourceEntity(
    sourceId = sourceId,
    kind = kind,
    treeUri = treeUri,
    displayPath = displayPath,
    providerLabel = providerLabel,
    displayName = displayName,
    recursive = recursive,
    mode = mode,
    orderIndex = orderIndex,
    permission = permission,
    revision = revision,
    lastScanAt = lastScanAt,
    lastScanStatus = lastScanStatus,
    lastScanError = lastScanError,
)

internal fun MangaEntity.toDomain(): MangaRecord = MangaRecord(
    mangaId = mangaId,
    anchorDocumentId = anchorDocumentId,
    sourceId = sourceId,
    sourceKind = sourceKind,
    layoutMode = layoutMode,
    displayName = displayName,
    author = author,
    hasMetadata = hasMetadata,
    summary = summary,
    coverDocumentId = coverDocumentId,
    coverChapterId = coverChapterId,
    coverProbedAt = coverProbedAt,
    metadataProbedAt = metadataProbedAt,
    chapterCount = chapterCount,
    chapterCountKnown = chapterCountKnown,
    countedChapterCount = countedChapterCount,
    countedChapterCountAt = countedChapterCountAt,
    availability = availability,
    discoveryGeneration = discoveryGeneration,
    discoveredAt = discoveredAt,
    updatedAt = updatedAt,
    // 覆盖值存名称。失配（旧版本写下的名字在当前枚举里已不存在）时回退 null
    // 即"用全局默认"，而不是抛异常——"打开漫画时用默认模式"远好于"打不开"。
    readerModeOverride = readerModeOverride?.let { stored ->
        ReadingMode.entries.firstOrNull { it.name == stored }
    },
    readerOrientationOverride = readerOrientationOverride?.let { stored ->
        ReaderOrientation.entries.firstOrNull { it.name == stored }
    },
    translationSettings = MangaTranslationSettings(
        sourceLanguage = translationSourceLanguage,
        autoDetectSource = translationAutoDetectSource,
        targetLanguage = translationTargetLanguage,
        // 存名称；失配（旧版本写过、枚举改名）时当成"没设置"，继续往分类/全局回退，
        // 而不是让详情页打不开。
        styleMode = translationStyleMode?.let { stored ->
            StyleMode.entries.firstOrNull { it.name == stored }
        },
        customStyle = translationCustomStyle,
        workflowId = translationWorkflowId,
        pageMode = translationPageMode?.let { name -> TranslationPageMode.entries.firstOrNull { it.name == name } },
        segThreshold = translationSegThreshold?.takeIf { it.isFinite() && it in 0f..1f },
        segTextScope = com.lmreader.core.model.SegTextScope.entries.firstOrNull { it.name == translationSegTextScope }
            ?: com.lmreader.core.model.SegTextScope.ALL,
        bubbleFillMode = translationBubbleFillMode?.let { name -> BubbleFillMode.entries.firstOrNull { it.name == name } },
        bubbleOpacityPercent = translationBubbleOpacity?.takeIf { it in 0..100 },
        bubbleTextPaddingPercent = translationBubblePadding?.takeIf { it in 0..20 },
        bubbleFont = translationBubbleFont?.let { name -> com.lmreader.core.model.BubbleFont.entries.firstOrNull { it.name == name } },
        bubbleFontScalePercent = translationBubbleFontScale?.takeIf { it in 50..150 },
        bubbleBold = translationBubbleBold,
        textDetectionThreshold = translationTextDetectionThreshold,
        freeTextMaskExpansionPercent = translationFreeTextMaskExpansion,
        freeTextMergeGapRatio = translationFreeTextMergeGapRatio,
    ),
)

internal fun MangaRecord.toEntity(sourceOrderIndex: Int): MangaEntity = MangaEntity(
    mangaId = mangaId,
    anchorDocumentId = anchorDocumentId,
    sourceId = sourceId,
    sourceKind = sourceKind,
    layoutMode = layoutMode,
    displayName = displayName,
    sortKey = NaturalOrder.sortKey(displayName),
    sourceOrderIndex = sourceOrderIndex,
    author = author,
    hasMetadata = hasMetadata,
    summary = summary,
    coverDocumentId = coverDocumentId,
    coverChapterId = coverChapterId,
    coverProbedAt = coverProbedAt,
    metadataProbedAt = metadataProbedAt,
    chapterCount = chapterCount,
    chapterCountKnown = chapterCountKnown,
    countedChapterCount = countedChapterCount,
    countedChapterCountAt = countedChapterCountAt,
    availability = availability,
    discoveryGeneration = discoveryGeneration,
    discoveredAt = discoveredAt,
    updatedAt = updatedAt,
    readerModeOverride = readerModeOverride?.name,
    readerOrientationOverride = readerOrientationOverride?.name,
    translationSourceLanguage = translationSettings.sourceLanguage,
    translationAutoDetectSource = translationSettings.autoDetectSource,
    translationTargetLanguage = translationSettings.targetLanguage,
    translationStyleMode = translationSettings.styleMode?.name,
    translationCustomStyle = translationSettings.customStyle,
    translationWorkflowId = translationSettings.workflowId,
    translationPageMode = translationSettings.pageMode?.name,
    translationSegThreshold = translationSettings.segThreshold,
    translationSegTextScope = translationSettings.segTextScope.name,
    translationBubbleFillMode = translationSettings.bubbleFillMode?.name,
    translationBubbleOpacity = translationSettings.bubbleOpacityPercent,
    translationBubblePadding = translationSettings.bubbleTextPaddingPercent,
    translationBubbleFont = translationSettings.bubbleFont?.name,
    translationBubbleFontScale = translationSettings.bubbleFontScalePercent,
    translationBubbleBold = translationSettings.bubbleBold,
    translationTextDetectionThreshold = translationSettings.textDetectionThreshold,
    translationFreeTextMaskExpansion = translationSettings.freeTextMaskExpansionPercent,
    translationFreeTextMergeGapRatio = translationSettings.freeTextMergeGapRatio,
)

internal fun ChapterEntity.toDomain(): ChapterRecord = ChapterRecord(
    chapterId = chapterId,
    mangaId = mangaId,
    documentId = documentId,
    kind = kind,
    title = title,
    sortKey = sortKey,
    position = position,
    modifiedAt = modifiedAt,
    pageCount = pageCount,
    coverDocumentId = coverDocumentId,
    contentRevision = contentRevision,
    discoveredAt = discoveredAt,
)

internal fun ChapterRecord.toEntity(): ChapterEntity = ChapterEntity(
    chapterId = chapterId,
    mangaId = mangaId,
    documentId = documentId,
    kind = kind,
    title = title,
    sortKey = sortKey,
    position = position,
    modifiedAt = modifiedAt,
    pageCount = pageCount,
    coverDocumentId = coverDocumentId,
    contentRevision = contentRevision,
    discoveredAt = discoveredAt,
)

internal fun ReadingProgressEntity.toDomain(): ReadingProgress = ReadingProgress(
    mangaId = mangaId,
    chapterId = chapterId,
    pageOrdinal = pageOrdinal,
    intraPageRatio = intraPageRatio,
    read = read,
    bookmark = bookmark,
    updatedAt = updatedAt,
)

internal fun ReadingProgress.toEntity(): ReadingProgressEntity = ReadingProgressEntity(
    mangaId = mangaId,
    chapterId = chapterId,
    pageOrdinal = pageOrdinal,
    intraPageRatio = intraPageRatio,
    read = read,
    bookmark = bookmark,
    updatedAt = updatedAt,
)

internal fun MetadataEntity.toDomain(): MetadataRecord = MetadataRecord(
    ownerId = ownerId,
    ownerType = ownerType,
    xml = xml,
    fields = com.lmreader.core.database.Converters.jsonToFields(fieldsJson),
    summary = summary,
    series = series,
    title = title,
    writer = writer,
    alternateSeries = alternateSeries,
    normalizedSearchText = normalizedSearchText,
    parseError = parseError,
    sourceLabel = sourceLabel,
    fingerprint = fingerprint,
    updatedAt = updatedAt,
)

internal fun MetadataRecord.toEntity(): MetadataEntity = MetadataEntity(
    ownerId = ownerId,
    ownerType = ownerType,
    xml = xml,
    fieldsJson = com.lmreader.core.database.Converters.fieldsToJson(fields),
    summary = summary,
    series = series,
    title = title,
    writer = writer,
    alternateSeries = alternateSeries,
    normalizedSearchText = normalizedSearchText,
    parseError = parseError,
    sourceLabel = sourceLabel,
    fingerprint = fingerprint,
    updatedAt = updatedAt,
)

internal fun CategoryEntity.toDomain(): Category = Category(
    categoryId = categoryId,
    name = name,
    styleMode = styleMode,
    customStyle = customStyle,
    orderIndex = orderIndex,
    revision = revision,
)

internal fun ShelfEntryEntity.toDomain(): ShelfEntry = ShelfEntry(
    mangaId = mangaId,
    categoryId = categoryId,
    addedAt = addedAt,
)

/**
 * 卡片投影行 → 领域卡片。
 *
 * [CardQueryRow.shelfCategoryId] 为空表示不在书架：使用 LEFT JOIN 而不是两次查询，
 * 否则每批 40 张卡片会变成 80 次查询（开发文档 8.1「不为每张卡片再查库」）。
 */
internal fun CardQueryRow.toCard(): MangaCard = MangaCard(
    mangaId = mangaId,
    displayName = displayName,
    summaryPreview = summaryPreview,
    sourceId = sourceId,
    coverDocumentId = coverDocumentId,
    coverChapterId = coverChapterId,
    coverProbedAt = coverProbedAt,
    sourceKind = sourceKind,
    layoutMode = layoutMode,
    chapterCount = chapterCount,
    chapterCountKnown = chapterCountKnown,
    countedChapterCount = countedChapterCount,
    countedChapterCountAt = countedChapterCountAt,
    inShelf = shelfCategoryId != null,
    availability = availability,
    hasArchiveChapters = hasArchiveChapters,
)
