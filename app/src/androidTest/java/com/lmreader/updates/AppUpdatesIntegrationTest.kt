package com.lmreader.updates

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.lmreader.core.api.ReleaseApk
import com.lmreader.core.api.UpdateStatus
import com.lmreader.core.model.UpdateCheckFrequency
import com.lmreader.core.storage.settings.UpdatePreferences
import com.lmreader.reliability.IsolatedApp
import com.lmreader.ui.settings.AboutScreen
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.*
import org.junit.Assert.*

class AppUpdatesIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = IsolatedApp(ApplicationProvider.getApplicationContext())
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = UpdatePreferences(app)
    private var server: MockWebServer? = null

    @After fun clean() {
        scope.cancel()
        val id = prefs.store.getLong("download-id", -1)
        if(id >= 0) UpdateApkDownloader(app).remove(id)
        server?.shutdown()
        app.root.deleteRecursively()
    }

    private fun capture(name: String) {
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory).mkdirs()
        File(directory, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }

    @Test fun aboutManualCheckOpensSharedReleaseLogAndPersistsAllFrequencies() {
        val release = UpdateStatus.Release("v0.2.0", "https://example.invalid", true, "修复游离文字排版\n支持同名漫画", listOf(
            ReleaseApk("LM-Reader-v0.2.0-64bit.apk", "https://example.invalid/update.apk", 1234)))
        prefs.setFrequency(UpdateCheckFrequency.OFF)
        val updates = AppUpdates(app, scope, prefs, { release })
        compose.setContent { MaterialTheme { AboutScreen(updates) {}; UpdateDialog(updates) } }
        compose.onNodeWithText("检查更新").performScrollTo().performClick()
        compose.waitUntil(5000) { updates.state.value.offer == release }
        compose.onNodeWithText("更新日志").assertIsDisplayed()
        compose.onNodeWithText(release.notes).assertIsDisplayed()
        compose.onNodeWithText("下载更新安装包").assertIsDisplayed()
        capture("update-release-log")
        compose.onNodeWithText("稍后再说").performClick()
        val labels = listOf("每次启动时", "每日首次启动时", "每三日", "关闭")
        UpdateCheckFrequency.entries.forEachIndexed { index, frequency ->
            val current = labels[prefs.frequency.value.ordinal]
            compose.onNodeWithText(current).performScrollTo().performClick()
            compose.onAllNodesWithText(labels[index]).onLast().performClick()
            compose.waitUntil(5000) { prefs.frequency.value == frequency }
            assertEquals(frequency, UpdatePreferences(app).frequency.value)
        }
        capture("about-update-frequency")
    }

    @Test fun startupChecksOncePerLaunchRespectsFrequencyAndManualCheckWorksWhenOff() {
        val calls = AtomicInteger()
        var now = 1791190800000L
        val updates = AppUpdates(app, scope, prefs, { calls.incrementAndGet(); UpdateStatus.NoPublicRelease }, clock = { now })
        prefs.setFrequency(UpdateCheckFrequency.EVERY_LAUNCH)
        compose.runOnIdle { updates.onAppLaunch(); updates.onAppLaunch() }
        compose.waitUntil(5000) { calls.get() == 1 && !updates.state.value.checking }
        compose.runOnIdle { updates.onAppBackground(true); updates.onAppLaunch() }
        assertEquals(1, calls.get())
        compose.runOnIdle { updates.onAppBackground(false); updates.onAppLaunch() }
        compose.waitUntil(5000) { calls.get() == 2 && !updates.state.value.checking }
        prefs.setFrequency(UpdateCheckFrequency.DAILY)
        compose.runOnIdle { updates.onAppBackground(false); updates.onAppLaunch() }
        assertEquals(2, calls.get())
        now += 86400000L
        compose.runOnIdle { updates.onAppBackground(false); updates.onAppLaunch() }
        compose.waitUntil(5000) { calls.get() == 3 && !updates.state.value.checking }
        prefs.setFrequency(UpdateCheckFrequency.OFF)
        now += 4 * 86400000L
        compose.runOnIdle { updates.onAppBackground(false); updates.onAppLaunch() }
        assertEquals(3, calls.get())
        compose.runOnIdle { updates.check() }
        compose.waitUntil(5000) { calls.get() == 4 && !updates.state.value.checking }
    }

    @Test fun downloadSurvivesCoordinatorRestartVerifiesRealSignedApkAndCreatesInstallerUri() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        // Optional generated tiny APK: same debug signer/package, versionCode 7 / versionName 0.2.0.
        Assume.assumeTrue(assets.list("").orEmpty().contains("update-fixture.apk"))
        val bytes = assets.open("update-fixture.apk").use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        server = MockWebServer().apply {
            start()
            enqueue(MockResponse().setBody(Buffer().write(bytes)).throttleBody(2048, 1, TimeUnit.SECONDS))
        }
        val apk = ReleaseApk("LM-Reader-v0.2.0-64bit.apk", server!!.url("/update.apk").toString(), bytes.size.toLong(), sha)
        val release = UpdateStatus.Release("v0.2.0", "https://example.invalid", true, "Update fixture release log", listOf(apk))
        val first = AppUpdates(app, scope, prefs, { release })
        compose.runOnIdle { first.download(release) }
        assertNotNull(server!!.takeRequest(10, TimeUnit.SECONDS))
        compose.waitUntil(5000) { prefs.store.getLong("download-id", -1) >= 0 }
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val restored = AppUpdates(app, scope, UpdatePreferences(app), { release })
        compose.waitUntil(30000) { restored.state.value.download?.phase in setOf(UpdateDownloadPhase.READY, UpdateDownloadPhase.FAILED) }
        val transfer = checkNotNull(restored.state.value.download)
        assertEquals(transfer.error, UpdateDownloadPhase.READY, transfer.phase)
        val file = checkNotNull(transfer.file)
        assertTrue(bytes.contentEquals(file.readBytes()))
        val downloader = UpdateApkDownloader(app)
        val installer = downloader.installerIntent(file)
        assertEquals("content", installer.data?.scheme)
        assertEquals(app.packageName + ".updates", installer.data?.authority)
        assertTrue(installer.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        app.contentResolver.openInputStream(installer.data!!).use { assertNotNull(it) }
        assertTrue(runCatching { downloader.verify(file, apk.copy(sha256 = "0".repeat(64)), "v0.2.0") }.isFailure)
        assertTrue(runCatching { downloader.verify(file, apk, "v0.1.4") }.isFailure)
        compose.runOnIdle { restored.showDownload() }
        compose.setContent { MaterialTheme { UpdateDialog(restored) } }
        compose.onNodeWithText(release.notes).assertIsDisplayed()
        compose.onNodeWithText("安装更新").assertIsDisplayed()
        capture("update-ready-to-install")
    }
}
