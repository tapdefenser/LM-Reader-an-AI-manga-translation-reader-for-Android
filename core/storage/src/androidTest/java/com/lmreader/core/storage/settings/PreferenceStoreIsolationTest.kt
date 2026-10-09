package com.lmreader.core.storage.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.lmreader.core.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PreferenceStoreIsolationTest {
    @Test fun sameFileSharesItsStoreAndSeparateApplicationDirectoriesNeverShareSettingsOrKeys() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "store-isolation-${UUID.randomUUID()}")
        class ApplicationDirectory(private val folder: File) : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = folder.apply { mkdirs() }
        }
        val first = ApplicationDirectory(File(root, "a"))
        val same = ApplicationDirectory(File(root, "a"))
        val second = ApplicationDirectory(File(root, "b"))
        try {
            assertSame(first.preferenceStore, same.preferenceStore)
            assertNotSame(first.preferenceStore, second.preferenceStore)
            val key = stringPreferencesKey("fixture")
            first.preferenceStore.edit { it[key] = "first-directory" }
            assertNull(second.preferenceStore.data.first()[key])
            assertEquals("first-directory", same.preferenceStore.data.first()[key])
            val profile = ApiProfile("fixture", ApiProfileKind.LLM, url = "https://example.com/v1", model = "fixture",
                apiKey = "synthetic-test-key")
            ApiProfileStore(first).save(profile)
            assertEquals(profile, ApiProfileStore(same).profiles.first().single())
            assertTrue(ApiProfileStore(second).profiles.first().isEmpty())
        } finally {
            check(root.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }
}
