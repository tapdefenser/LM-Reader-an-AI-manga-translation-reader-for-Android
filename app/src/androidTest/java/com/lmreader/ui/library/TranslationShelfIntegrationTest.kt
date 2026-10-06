package com.lmreader.ui.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lmreader.core.database.DatabaseProvider
import com.lmreader.core.database.LmReaderDatabase
import com.lmreader.core.model.TranslationRequest
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** A private in-memory database exercises queue/shelf transactions without starting the worker. */
class TranslationShelfIntegrationTest {
    private lateinit var db: LmReaderDatabase
    private lateinit var repositories: DatabaseProvider.Components
    private val request = TranslationRequest("zh-Hans", "en", false, "{}", 100L)

    @Before fun seed() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LmReaderDatabase::class.java).build()
        repositories = DatabaseProvider.create(db)
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO library_sources (sourceId, kind, treeUri, displayPath, recursive, mode, orderIndex, permission, revision) VALUES ('source', 'IMAGE_DIRECTORY', 'content://fixture', 'fixture', 1, 'MULTI_CHAPTER', 0, 'OK', 1)")
        sql.execSQL("INSERT INTO mangas (mangaId, anchorDocumentId, sourceId, sourceKind, layoutMode, displayName, sortKey, sourceOrderIndex, hasMetadata, chapterCountKnown, availability, discoveryGeneration, discoveredAt, updatedAt, translationAutoDetectSource) VALUES ('manga', 'manga', 'source', 'IMAGE_DIRECTORY', 'MULTI_CHAPTER', 'Fixture', 'fixture', 0, 0, 1, 'AVAILABLE', 1, 0, 0, 0)")
        sql.execSQL("INSERT INTO chapters (chapterId, mangaId, documentId, kind, title, sortKey, position, contentRevision, discoveredAt) VALUES ('chapter', 'manga', 'chapter', 'IMAGE_DIRECTORY', 'Chapter', 'chapter', 0, 1, 0)")
    }

    @After fun close() { db.close() }

    @Test fun enqueueAddsToShelfAndRepeatedSelectionRestoresRemovedManga() = runBlocking {
        assertNull(repositories.shelf.categoryIdOf("manga"))
        assertEquals(1, repositories.translations.enqueue("manga", listOf("chapter", "chapter"), request))
        assertEquals(0L, repositories.shelf.categoryIdOf("manga"))
        assertNotNull(db.shelfDao().getCategory(0L))
        repositories.shelf.removeFromShelf("manga")
        assertEquals(0, repositories.translations.enqueue("manga", listOf("chapter"), request))
        assertEquals(0L, repositories.shelf.categoryIdOf("manga"))
        assertEquals(1, db.translationDao().byManga("manga").size)
    }

    @Test fun automaticCollectionPreservesCategoryAndAddedTime() = runBlocking {
        val category = repositories.shelf.createCategory("Favorites")
        repositories.shelf.addToShelf("manga", category.categoryId)
        val before = db.shelfDao().getEntry("manga")
        repositories.translations.enqueue("manga", listOf("chapter"), request)
        repositories.shelf.ensureOnShelf("manga") // Reader page translation uses the same operation.
        assertEquals(before, db.shelfDao().getEntry("manga"))
    }

    @Test fun emptyInvalidOrFailedRequestsDoNotLeaveShelfOrQueueEntries() = runBlocking {
        assertEquals(0, repositories.translations.enqueue("manga", emptyList(), request))
        assertTrue(runCatching { repositories.translations.enqueue("manga", listOf("chapter"), request.copy(sourceLanguage = null)) }.isFailure)
        assertNull(repositories.shelf.categoryIdOf("manga"))
        assertTrue(runCatching { repositories.translations.enqueue("missing-manga", listOf("chapter"), request) }.isFailure)
        assertTrue(db.translationDao().byChapters(listOf("chapter")).isEmpty())
        assertEquals(0, db.shelfDao().countAll())
    }
}
