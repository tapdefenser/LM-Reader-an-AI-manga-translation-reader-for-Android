package com.lmreader.reliability

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import com.lmreader.di.AppContainer
import com.lmreader.ui.settings.backup.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupIntegrationTest {
    @Test fun previousAdditiveSchemaAndColumnOrderCanRestoreWithoutLosingSettings() = runBlocking(Dispatchers.IO) {
        container.startupReady.await()
        seedRow(container,"library_sources",mapOf("sourceId" to "s","kind" to "IMAGE_DIRECTORY","treeUri" to "content://fixture","permission" to "OK","mode" to "MULTI_CHAPTER"))
        seedRow(container,"mangas",mapOf("mangaId" to "m","anchorDocumentId" to "root/m","sourceId" to "s","sourceKind" to "IMAGE_DIRECTORY","layoutMode" to "MULTI_CHAPTER","availability" to "AVAILABLE","displayName" to "fixture","translationBubbleOpacity" to 100))
        val root=File(app.root,"old-schema").apply { mkdirs() }
        val db=container.database.openHelper.writableDatabase
        container.database.runInTransaction { BackupDatabase.snapshot(db,root,true) }
        for(table in BackupArchive.tables) {
            val file=File(root,"database/$table.json")
            val json=JSONObject(file.readText()).put("version",14)
            val cols=json.getJSONArray("columns")
            val order=(0 until cols.length()).filter { cols.getString(it)!="translationApiProfileId" }.reversed()
            json.put("columns",org.json.JSONArray(order.map { cols.getString(it) }))
            val rows=json.getJSONArray("rows")
            json.put("rows",org.json.JSONArray((0 until rows.length()).map { index ->
                val row=rows.getJSONArray(index);org.json.JSONArray(order.map { row.get(it) })
            }))
            file.writeText(json.toString())
        }
        container.database.runInTransaction { BackupDatabase.restore(db,root,false) }
        val saved=container.mangaRepository.translationSettings("m")
        assertNull(saved.apiProfileId);assertEquals(100,saved.bubbleOpacityPercent)
    }
    private lateinit var app: IsolatedApp
    private lateinit var container: AppContainer
    @Before fun setup() { app = IsolatedApp(ApplicationProvider.getApplicationContext()); container = AppContainer(app) }
    @After fun cleanup() = runBlocking { closeContainer(container) }
    @Test fun restorePreservesShelfProgressGlossaryEditsAndRemovesSecrets() = runBlocking(Dispatchers.IO) {
        container.startupReady.await()
        seedRow(container, "library_sources", mapOf("sourceId" to "s", "kind" to "IMAGE_DIRECTORY", "treeUri" to "content://old/tree/root", "permission" to "OK", "mode" to "MULTI_CHAPTER"))
        seedRow(container, "mangas", mapOf("mangaId" to "m", "anchorDocumentId" to "root/m", "sourceId" to "s", "sourceKind" to "IMAGE_DIRECTORY", "layoutMode" to "MULTI_CHAPTER", "availability" to "AVAILABLE", "displayName" to "fixture"))
        seedRow(container, "chapters", mapOf("chapterId" to "c", "mangaId" to "m", "documentId" to "root/m/c", "kind" to "IMAGE_DIRECTORY"))
        seedRow(container, "shelf_entries", mapOf("mangaId" to "m", "categoryId" to 0L))
        seedRow(container, "reading_progress", mapOf("mangaId" to "m", "chapterId" to "c", "pageOrdinal" to 9L))
        seedRow(container, "manga_glossary", mapOf("mangaId" to "m", "source" to "Akira", "target" to "阿基拉", "manual" to 1L))
        val store = container.localPageTranslator.artifacts
        val saved = store.save("fixture-page", "a".repeat(64), LocalTranslationLanguage.ENGLISH, LocalTranslationLanguage.CHINESE_SIMPLIFIED,
            40, 60, emptyList(), emptyList(), 10, BubbleRenderSettings(opacityPercent = 77))
        container.apiProfiles.save(ApiProfile("profile", ApiProfileKind.LLM, url = "https://example.com/v1", apiKey = "secret-do-not-export", model = "test"))
        container.generalPreferences.setTheme(com.lmreader.core.storage.settings.AppThemeMode.DARK)
        val backup = File(app.root, "backup.zip")
        container.backups.create(Uri.fromFile(backup))
        val extracted = File(app.root, "extracted"); backup.inputStream().use { BackupArchive.read(it, extracted) }
        assertFalse(File(extracted, "api.json").readText().contains("secret-do-not-export"))
        container.database.openHelper.writableDatabase.execSQL("DELETE FROM shelf_entries")
        container.database.openHelper.writableDatabase.execSQL("UPDATE reading_progress SET pageOrdinal = 1")
        store.delete(saved.pageId)
        container.backups.restore(Uri.fromFile(backup))
        assertEquals(0L, container.shelfRepository.categoryIdOf("m"))
        container.database.openHelper.writableDatabase.query("SELECT pageOrdinal FROM reading_progress WHERE mangaId='m'").use { assertTrue(it.moveToFirst()); assertEquals(9, it.getInt(0)) }
        assertEquals("阿基拉", container.translationRepository.glossary("m").first().target)
        assertEquals(saved.revision, store.load(saved.pageId, saved.sourceSha256)?.revision)
        assertEquals("", container.apiProfiles.profiles.first().single().apiKey)
        assertEquals(com.lmreader.core.storage.settings.AppThemeMode.DARK, container.generalPreferences.theme.value)
        assertTrue(container.translationQueue.paused.value); assertTrue(container.exportQueue.paused.value)
        assertFalse(File(app.noBackupFilesDir, "restore-pending").exists())
    }
    @Test fun invalidDatabaseRestoreRollsBackExistingUserData() = runBlocking(Dispatchers.IO) {
        container.startupReady.await()
        val root = File(app.root, "db").apply { mkdirs() }
        val db = container.database.openHelper.writableDatabase
        container.database.runInTransaction { BackupDatabase.snapshot(db, root, true) }
        db.execSQL("UPDATE categories SET name='Keep me' WHERE categoryId=0")
        val json = JSONObject(File(root, "database/categories.json").readText()).put("rows", org.json.JSONArray())
        File(root, "database/categories.json").writeText(json.toString())
        var rejected = false
        try { container.database.runInTransaction { BackupDatabase.restore(db, root, false) } } catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        db.query("SELECT name FROM categories WHERE categoryId=0").use { assertTrue(it.moveToFirst()); assertEquals("Keep me", it.getString(0)) }
    }
    @Test fun pendingRestoreJournalRecoversBeforeNormalStartupWork() = runBlocking(Dispatchers.IO) {
        container.startupReady.await()
        val db = container.database.openHelper.writableDatabase
        db.execSQL("UPDATE categories SET name='Before interruption' WHERE categoryId=0")
        val backup = File(app.root, "before.zip"); container.backups.create(Uri.fromFile(backup))
        val rollback = File(app.noBackupFilesDir, "backup-${java.util.UUID.randomUUID()}")
        backup.inputStream().use { BackupArchive.read(it, rollback) }
        db.execSQL("UPDATE categories SET name='Partial replacement' WHERE categoryId=0")
        File(app.noBackupFilesDir, "restore-pending").writeText(rollback.name)
        container.backups.recoverPendingRestore()
        db.query("SELECT name FROM categories WHERE categoryId=0").use { assertTrue(it.moveToFirst()); assertEquals("Before interruption", it.getString(0)) }
        assertFalse(File(app.noBackupFilesDir, "restore-pending").exists())
    }
    @Test fun brokenRecoveryJournalBlocksStartupWithoutStartingDatabaseWriters() = runBlocking(Dispatchers.IO) {
        val isolated = IsolatedApp(ApplicationProvider.getApplicationContext())
        File(isolated.noBackupFilesDir, "restore-pending").writeText("invalid-journal")
        val blocked = AppContainer(isolated)
        try {
            var rejected = false
            try { blocked.startupReady.await() } catch (_: Exception) { rejected = true }
            assertTrue(rejected)
            // Allow the startup waiters to observe the failure: none may crash the process.
            delay(200)
            assertNotNull(blocked.backups.failure.value)
            assertTrue(blocked.backups.recoveryRequired.value)
            assertTrue(File(isolated.noBackupFilesDir, "restore-pending").exists())
            assertTrue(blocked.translationQueue.items.value.isEmpty())
        } finally { closeContainer(blocked) }
    }
}
