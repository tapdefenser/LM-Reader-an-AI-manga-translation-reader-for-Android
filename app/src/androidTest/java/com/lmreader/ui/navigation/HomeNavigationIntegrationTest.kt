package com.lmreader.ui.navigation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.lmreader.di.AppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Exercises both navigation axes against the real home routes. */
class HomeNavigationIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val container = AppContainer.from(context)
    private val videoPermission = context.getSharedPreferences("video-permission", 0)
    private var completed = false
    private var mediaMode = "IMAGE"
    private var permissionRequested: Boolean? = null
    private lateinit var nav: NavHostController

    @Before fun prepare() = runBlocking {
        container.startupReady.await()
        completed = container.preferences.onboardingCompleted.first()
        mediaMode = container.preferences.homeMediaMode.first()
        permissionRequested = if (videoPermission.contains("requested"))
            videoPermission.getBoolean("requested", false) else null
        // Permission handling has its own tests; keep this navigation case in the app.
        videoPermission.edit().putBoolean("requested", true).commit()
        container.preferences.setOnboardingCompleted(true)
        container.preferences.setHomeMediaMode("IMAGE")
    }

    @After fun restore() = runBlocking {
        container.preferences.setHomeMediaMode(mediaMode)
        container.preferences.setOnboardingCompleted(completed)
        videoPermission.edit().apply {
            permissionRequested?.let { putBoolean("requested", it) } ?: remove("requested")
        }.commit()
        Unit
    }

    @Test fun mediaMenuKeepsTheCollectionAndSegmentsRestoreTheirEntries() {
        compose.setContent {
            MaterialTheme {
                nav = rememberNavController()
                LmReaderNavHost(container, nav)
            }
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("home-collection:BOOKSHELF").fetchSemanticsNodes().isNotEmpty()
        }
        val shelfEntry = entryId()
        compose.onNodeWithTag("home-collection:LIBRARY").performClick().assertIsSelected()
        val libraryEntry = entryId()

        choose(MainDestination.VIDEOS)
        awaitMode("VIDEO")
        assertCollection(Routes.LIBRARY, libraryEntry)
        compose.onNodeWithText("视频").assertIsDisplayed()
        compose.onNodeWithTag("home-collection:BOOKSHELF").performClick().assertIsSelected()
        assertCollection(Routes.BOOKSHELF, shelfEntry)
        assertEquals("VIDEO", runBlocking { container.preferences.homeMediaMode.first() })

        choose(MainDestination.IMAGES)
        awaitMode("IMAGE")
        assertCollection(Routes.BOOKSHELF, shelfEntry)
        compose.onNodeWithTag("home-collection:LIBRARY").performClick().assertIsSelected()
        assertCollection(Routes.LIBRARY, libraryEntry)

        choose(MainDestination.SETTINGS)
        compose.onNodeWithTag("home-collection:LIBRARY").assertDoesNotExist()
        // Opening a media entry from a non-home page lands in the library.
        compose.onRoot().performTouchInput {
            swipe(Offset(1f, center.y), Offset(width * .75f, center.y))
        }
        compose.onNodeWithTag("main-menu:VIDEOS").performClick()
        awaitMode("VIDEO")
        compose.runOnIdle { assertEquals(Routes.LIBRARY, nav.currentDestination?.route) }
        compose.onNodeWithTag("home-collection:LIBRARY").assertIsSelected()
    }

    private fun choose(destination: MainDestination) {
        compose.onNode(hasContentDescription("主菜单") or hasContentDescription("菜单")).performClick()
        compose.onNodeWithTag("main-menu:${destination.name}").performScrollTo().performClick()
    }

    private fun awaitMode(mode: String) {
        compose.waitUntil(10_000) { runBlocking { container.preferences.homeMediaMode.first() } == mode }
        compose.waitForIdle()
    }

    private fun entryId(): String = compose.runOnIdle { nav.currentBackStackEntry!!.id }

    private fun assertCollection(route: String, id: String) = compose.runOnIdle {
        assertEquals(route, nav.currentDestination?.route)
        assertEquals(id, nav.currentBackStackEntry!!.id)
    }
}
