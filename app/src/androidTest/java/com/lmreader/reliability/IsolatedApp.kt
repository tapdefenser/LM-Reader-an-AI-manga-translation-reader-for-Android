package com.lmreader.reliability

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.lmreader.di.AppContainer
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import java.io.File
import java.util.UUID

class IsolatedApp(base: Context) : Application() {
    private val prefix = "reliability-${UUID.randomUUID()}"
    val root = File(base.cacheDir, prefix).apply { mkdirs() }
    init { attachBaseContext(base) }
    override fun getApplicationContext(): Context = this
    override fun getFilesDir() = File(root, "files").apply { mkdirs() }
    override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    override fun getNoBackupFilesDir() = File(root, "nobackup").apply { mkdirs() }
    override fun getDatabasePath(name: String) = File(root, name)
    override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences("$prefix-$name", mode)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
}

suspend fun closeContainer(container: AppContainer) {
    // Room observers must finish before the connection pool is closed.
    container.backgroundScope.coroutineContext.job.cancelAndJoin()
    container.workDatabase.close()
    container.database.close()
}

/** Populate all non-null columns, then override the fixture's meaningful fields. */
fun seedRow(container: AppContainer, table: String, values: Map<String, Any?>) {
    val db = container.database.openHelper.writableDatabase
    val row = linkedMapOf<String, Any?>()
    db.query("PRAGMA table_info(`$table`)").use { cursor ->
        while (cursor.moveToNext()) {
            val name = cursor.getString(1); val type = cursor.getString(2); val required = cursor.getInt(3) == 1
            row[name] = values[name] ?: if (required) { if (type == "TEXT") "" else 0L } else null
        }
    }
    db.execSQL("INSERT OR REPLACE INTO `$table` (${row.keys.joinToString { "`$it`" }}) VALUES (${row.keys.joinToString { "?" }})", row.values.toTypedArray())
}
