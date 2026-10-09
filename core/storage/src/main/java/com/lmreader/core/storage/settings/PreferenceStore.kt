package com.lmreader.core.storage.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import java.util.concurrent.ConcurrentHashMap

/**
 * 每个实际设置文件唯一的 DataStore 实例。
 *
 * **为什么必须集中在这里**：`preferencesDataStore` 的委托是"每个属性一个实例"，
 * 两个类各自声明一次同名的 `Context.preferencesStore` 就会打开同一个文件两次，
 * DataStore 会直接抛
 * `IllegalStateException: There are multiple DataStores active for the same file`。
 * 这不是警告而是崩溃，而且只在**两个类都被用到**的路径上才触发——本轮就是这样：
 * 书架只碰 [AppPreferences]，一切正常；一进阅读器碰到 [ReaderPreferences]，立刻崩溃。
 *
 * [AppPreferences] 与 [ReaderPreferences] 通过这里取用同一实例。
 * 按文件而不是属性缓存，隔离的应用上下文不会共享另一个目录的数据。
 */
internal object PreferenceStores {
    private val stores = ConcurrentHashMap<String, DataStore<Preferences>>()
    fun get(context: Context, name: String): DataStore<Preferences> {
        val file = context.applicationContext.preferencesDataStoreFile(name).canonicalFile
        return stores.computeIfAbsent(file.path) { PreferenceDataStoreFactory.create(produceFile = { file }) }
    }
}

internal val Context.preferenceStore: DataStore<Preferences>
    get() = PreferenceStores.get(this, "lmreader_preferences")
