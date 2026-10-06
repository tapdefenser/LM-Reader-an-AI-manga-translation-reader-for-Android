package com.lmreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import com.lmreader.ui.i18n.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lmreader.core.model.ImageScaleType
import com.lmreader.core.model.ReaderHideThreshold
import com.lmreader.core.model.ReaderOrientation
import com.lmreader.core.model.ReaderSettings
import com.lmreader.core.model.ReaderTheme
import com.lmreader.core.model.ReadingMode
import com.lmreader.core.model.TapInvert
import com.lmreader.core.model.TapZones
import com.lmreader.core.model.ZoomStart
import com.lmreader.ui.common.LabeledSlider

/**
 * 阅读设置对话框（Mihon `ReaderSettingsDialog` 的三个分页）。
 *
 * 三个分页中的全部选项均为全局偏好，与「设置 → 阅读器」共享。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSettingsDialog(
    state: ReaderUiState,
    onDismiss: () -> Unit,
    onReadingMode: (ReadingMode) -> Unit,
    onOrientation: (ReaderOrientation) -> Unit,
    onUpdateGlobal: ((ReaderSettings) -> ReaderSettings) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableIntStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("阅读设置", style = MaterialTheme.typography.titleLarge)
            Text("全局设置，应用于所有漫画", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            TabRow(selectedTabIndex = tab) {
                listOf("阅读模式", "通用", "自定义滤镜").forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title, style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 上限而不是固定高度：内容少的机型不必留一大片空白，
                    // 内容多的机型也不会把按钮挤出屏幕。
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
            ) {
                when (tab) {
                    0 -> ReadingModePage(
                        state = state,
                        onReadingMode = onReadingMode,
                        onOrientation = onOrientation,
                    )

                    1 -> GeneralPage(state.settings, onUpdateGlobal)
                    else -> FilterPage(state.settings, onUpdateGlobal)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 阅读模式与屏幕方向均应用于所有漫画。 */
@Composable
private fun ReadingModePage(
    state: ReaderUiState,
    onReadingMode: (ReadingMode) -> Unit,
    onOrientation: (ReaderOrientation) -> Unit,
) {
    SectionTitle("阅读模式")
    Spacer(Modifier.height(8.dp))
    ChipRow(
        options = ReadingMode.entries,
        selected = state.readingMode,
        label = { it.label },
        onSelect = onReadingMode,
    )
    Spacer(Modifier.height(20.dp))
    SectionTitle("屏幕方向")
    ChipRow(
        options = ReaderOrientation.entries,
        selected = state.settings.orientation ?: ReaderOrientation.FREE,
        label = { it.label },
        onSelect = onOrientation,
    )
}

/**
 * 通用分页：全局设置。
 *
 * 只放能独立生效、不需要额外权限或系统交互的项。"双页"这类开发文档标为
 * 后续能力验证项的功能不在这里——给一个点了没反应的开关比不给更糟。
 */
@Composable
private fun GeneralPage(settings: ReaderSettings, onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit) {
    SectionTitle("显示")
    ChipRow(
        options = ReaderTheme.entries,
        selected = settings.theme,
        label = { it.label },
        onSelect = { theme -> onUpdate { it.copy(theme = theme) } },
    )
    ToggleRow("显示页码", settings.showPageNumber) { value ->
        onUpdate { it.copy(showPageNumber = value) }
    }
    ToggleRow("全屏", settings.fullscreen) { value ->
        onUpdate { it.copy(fullscreen = value) }
    }
    ToggleRow("阅读时常亮", settings.keepScreenOn) { value ->
        onUpdate { it.copy(keepScreenOn = value) }
    }

    Spacer(Modifier.height(20.dp))
    SectionTitle("分页")
    ChipRow(
        options = ImageScaleType.entries,
        selected = settings.imageScaleType,
        label = { it.label },
        onSelect = { scale -> onUpdate { it.copy(imageScaleType = scale) } },
    )
    Spacer(Modifier.height(8.dp))
    LabeledText("放大后起始位置")
    ChipRow(
        options = ZoomStart.entries,
        selected = settings.zoomStart,
        label = { it.label },
        onSelect = { start -> onUpdate { it.copy(zoomStart = start) } },
    )
    ToggleRow("裁白边", settings.cropBorders) { value ->
        onUpdate { it.copy(cropBorders = value) }
    }
    ToggleRow("自动放大宽图", settings.landscapeZoom) { value ->
        onUpdate { it.copy(landscapeZoom = value) }
    }
    // 「加载原图」放在这里而不是"显示"：它管的是**解码多少像素**，与画多大无关。
    // 切换后会立刻按新策略重新解码当前页（见 EnginePageView 的 LaunchedEffect 键）。
    ToggleRow(
        title = "加载原图（不降采样）",
        checked = settings.loadOriginalImage,
        subtitle = "开：细节最完整，但单页解码量回到原图大小，翻页更吃内存；" +
            "关：超过屏幕尺寸的图先降采样，翻页更稳。",
    ) { value ->
        onUpdate { it.copy(loadOriginalImage = value) }
    }

    Spacer(Modifier.height(20.dp))
    SectionTitle("点按区域")
    ChipRow(
        options = TapZones.entries,
        selected = settings.pagerTapZones,
        label = { it.label },
        onSelect = { zones -> onUpdate { it.copy(pagerTapZones = zones) } },
    )
    Spacer(Modifier.height(8.dp))
    LabeledText("反转点按区域")
    ChipRow(
        options = TapInvert.entries,
        selected = settings.pagerTapInvert,
        label = { it.label },
        onSelect = { invert -> onUpdate { it.copy(pagerTapInvert = invert) } },
    )

    Spacer(Modifier.height(20.dp))
    SectionTitle("条漫")
    ChipRow(
        options = TapZones.entries,
        selected = settings.webtoonTapZones,
        label = { it.label },
        onSelect = { zones -> onUpdate { it.copy(webtoonTapZones = zones) } },
    )
    Spacer(Modifier.height(8.dp))
    LabeledSlider(
        label = "侧边距",
        value = settings.webtoonSidePadding.toFloat(),
        range = 0f..25f,
        steps = 24,
        display = "${settings.webtoonSidePadding}%",
    ) { value -> onUpdate { it.copy(webtoonSidePadding = value.toInt()) } }
    ToggleRow("双击缩放", settings.webtoonDoubleTapZoom) { value ->
        onUpdate { it.copy(webtoonDoubleTapZoom = value) }
    }
    ToggleRow("禁止缩小", settings.webtoonDisableZoomOut) { value ->
        onUpdate { it.copy(webtoonDisableZoomOut = value) }
    }
    Spacer(Modifier.height(8.dp))
    LabeledText("滚动隐藏控制栏的灵敏度")
    ChipRow(
        options = ReaderHideThreshold.entries,
        selected = settings.hideThreshold,
        label = { it.label },
        onSelect = { threshold -> onUpdate { it.copy(hideThreshold = threshold) } },
    )

    Spacer(Modifier.height(20.dp))
    SectionTitle("控制")
    ToggleRow("音量键翻页", settings.volumeKeys) { value ->
        onUpdate { it.copy(volumeKeys = value) }
    }
    ToggleRow("反转音量键", settings.volumeKeysInverted) { value ->
        onUpdate { it.copy(volumeKeysInverted = value) }
    }
    ToggleRow("长按显示操作", settings.longTapActions) { value ->
        onUpdate { it.copy(longTapActions = value) }
    }
    ToggleRow("页切换动画", settings.pageTransitions) { value ->
        onUpdate { it.copy(pageTransitions = value) }
    }

    Spacer(Modifier.height(20.dp))
    SectionTitle("章节")
    ToggleRow("显示章节过渡页", settings.showChapterTransitions) { value ->
        onUpdate { it.copy(showChapterTransitions = value) }
    }
    Spacer(Modifier.height(8.dp))
    // 「预载页数」与「缓存章节数」**不在这里**：它们只在打开阅读器之前生效（分别决定
    // 预取多少字节、缓存多少章的页清单），放进阅读中的设置面板会让人以为改了就立刻生效。
    // 入口在 设置 → 阅读器，这里只留一句指路。
    LabeledText("预载页数与缓存章节数在「设置 → 阅读器」里调整，改动在下次打开阅读器时生效")
}

/**
 * 自定义滤镜分页（Mihon `ColorFilterPage`）。
 *
 * 亮度的三段语义由 `ReaderDisplayEffects` 实现，这里只给控件与说明文案——
 * 用户需要知道负值是"叠加变暗"而不是"把系统亮度调更低"，否则会以为没生效。
 */
@Composable
private fun FilterPage(settings: ReaderSettings, onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit) {
    SectionTitle("亮度")
    ToggleRow("自定义亮度", settings.customBrightness) { value ->
        onUpdate { it.copy(customBrightness = value) }
    }
    if (settings.customBrightness) {
        LabeledSlider(
            label = "亮度",
            value = settings.customBrightnessValue.toFloat(),
            range = -75f..100f,
            steps = 0,
            display = when {
                settings.customBrightnessValue == 0 -> "跟随系统"
                settings.customBrightnessValue > 0 -> "${settings.customBrightnessValue}%"
                else -> "暗化 ${-settings.customBrightnessValue}%"
            },
        ) { value -> onUpdate { it.copy(customBrightnessValue = value.toInt()) } }
        Text(
            text = "正值直接调高屏幕亮度；负值保持屏幕最低亮度并叠加一层暗色，" +
                "因此在亮度已经最低的设备上仍然能更暗。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(20.dp))
    SectionTitle("颜色")
    ToggleRow("灰度", settings.grayscale) { value ->
        onUpdate { it.copy(grayscale = value) }
    }
    ToggleRow("反色", settings.invertedColors) { value ->
        onUpdate { it.copy(invertedColors = value) }
    }
    if (settings.grayscale && settings.invertedColors) {
        Text(
            text = "两者同时开启时先反色后转灰，与 Mihon 的叠加顺序一致。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------ 复用控件

@Composable
private fun SectionTitle(text: String) {
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Text(text, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun LabeledText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 一排可横向滚动的选项。
 *
 * 用可横向滚动的 `Row` 而不是 `LazyRow`：选项都不多（最多 6 项），全部组合代价可忽略，
 * 而 `LazyRow` 会把屏幕外的项留到滚动时才组合——横向空间不足时最后一项就点不到。
 */
@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (option in options) {
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = {
                    Text(label(option), maxLines = 1, style = MaterialTheme.typography.labelSmall)
                },
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = onChange)
        }
        // 有代价的开关必须把代价写在旁边：否则用户只会在内存吃紧时才发现自己开了它。
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, end = 12.dp),
            )
        }
    }
}
