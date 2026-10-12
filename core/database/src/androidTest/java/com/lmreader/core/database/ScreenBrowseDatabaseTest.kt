package com.lmreader.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScreenBrowseDatabaseTest {
    @Test fun obsoleteGeometryKeepsItsTextAndSuccessorParametersUntilReplacementIsReady() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ScreenBrowseDatabase::class.java).build()
        fun row(id: String, status: String, children: String, text: String) = ScreenBrowseRegionEntity("session", id, 7, 2,
            status, "{\"sourceBounds\":[20,40,140,140],\"capturedContentBounds\":[10,20,70,70]}", children, "Original", text)
        try {
            val dao = database.regions()
            val old = row("old", "OBSOLETE", "[\"new\"]", "Readable old text")
            val next = row("new", "PENDING", "[]", "")
            dao.replaceScene("session", listOf(old, next))
            assertEquals(setOf(old, next), dao.regions("session").toSet())
            val filled = next.copy(status = "FILLED", translatedText = "New complete text")
            dao.replaceScene("session", listOf(filled))
            assertEquals(listOf(filled), dao.regions("session"))
            dao.replaceScene("session", emptyList()); assertTrue(dao.regions("session").isEmpty())
        } finally { database.close() }
    }
}
