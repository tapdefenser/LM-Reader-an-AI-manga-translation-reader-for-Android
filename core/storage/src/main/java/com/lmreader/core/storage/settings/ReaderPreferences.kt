package com.lmreader.core.storage.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lmreader.core.model.ReaderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 阅读器偏好（开发文档 12、14「阅读器」分组）。
 *
 * 存储策略：**整份 [ReaderSettings] 序列化成一行键值**，而不是六十个独立偏好键。
 *
 * 为什么这样选：
 * - 阅读器的设置几乎总是被整体读取（布局、手势、绘制都要同一份快照），逐键读取会产生
 *   六十次 `data` 流发射与六十次 map；
 * - 加字段不需要动存储键，也就不会出现"新字段忘了加键"这类静默丢失；
 * - 写入是一致的一份值，避免了逐键写入时"写了一半被读到"的中间态。
 *
 * 代价与对策：格式是自定义的 `key=value` 行文本，因此解析**逐字段容错**——单个字段
 * 无法识别时只回退该字段的默认值，不影响其余字段；整段损坏时整份回退默认值。这比 JSON
 * 少一个依赖，而这里的数据是扁平标量与枚举，不需要 JSON 的表达力。
 *
 * 全部阅读偏好均为全局设置，包括阅读模式和屏幕方向。
 */
class ReaderPreferences(private val context: Context) {

    /** 当前阅读器设置。发出的每一份都是完整且自洽的快照。 */
    val settings: Flow<ReaderSettings> = context.preferenceStore.data
        .map { prefs -> ReaderSettingsCodec.decode(prefs[KEY_READER_SETTINGS]) }

    /**
     * 以读改写的方式更新。
     *
     * 变换函数接收**当时最新的**快照而不是调用方手里那份：设置界面逐项修改，
     * 用户快速连点两个开关时，第二次修改必须建立在第一次的结果之上。
     */
    suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        context.preferenceStore.edit { prefs ->
            val current = ReaderSettingsCodec.decode(prefs[KEY_READER_SETTINGS])
            prefs[KEY_READER_SETTINGS] = ReaderSettingsCodec.encode(transform(current))
        }
    }

    /** 恢复全部阅读器设置为出厂默认。 */
    suspend fun resetToDefaults() {
        context.preferenceStore.edit { it.remove(KEY_READER_SETTINGS) }
    }

    private companion object {
        val KEY_READER_SETTINGS = stringPreferencesKey("reader_settings_v1")
    }
}
