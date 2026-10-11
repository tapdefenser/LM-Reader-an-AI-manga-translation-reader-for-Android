package com.lmreader.screen

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lmreader.di.AppContainer
import com.lmreader.R
import com.lmreader.core.model.ScreenTranslationMode
import com.lmreader.core.storage.settings.GeneralPreferences
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

/** Consent is obtained from a visible activity for every new capture session. */
class ScreenTranslationPermissionActivity : ComponentActivity() {
    private val controller by lazy { AppContainer.from(this).screenTranslation }
    private var accessibilityRequested = false
    private var waitingForResult = false
    private val accessibility = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        waitingForResult = false; begin(fromAccessibilitySettings = true)
    }
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        waitingForResult = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ContextCompat.startForegroundService(this, Intent(this, ScreenTranslationService::class.java).setAction(ScreenTranslationService.START).putExtra("capture", result.data))
        } else if (controller.preferences.settings.value.requiresAccessibility && Build.VERSION.SDK_INT >= 34 && ScreenAccessibilityService.instance != null) {
            ContextCompat.startForegroundService(this, Intent(this, ScreenTranslationService::class.java).setAction(ScreenTranslationService.START_ACCESSIBILITY))
        }
        finish()
    }
    private val overlay = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        waitingForResult = false
        if (Settings.canDrawOverlays(this)) beginCapture() else { controller.state.value = controller.state.value.copy(status = "请允许显示在其他应用上层"); finish() }
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { waitingForResult = false; begin() }
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.createConfigurationContext(android.content.res.Configuration(newBase.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(GeneralPreferences(newBase).effectiveLanguageTag))
        }))
    }
    override fun onStart() { super.onStart(); controller.setVisible(this, true) }
    override fun onStop() { controller.setVisible(this, false); super.onStop() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accessibilityRequested = savedInstanceState?.getBoolean("accessibility-requested") ?: false
        waitingForResult = savedInstanceState?.getBoolean("waiting-for-result") ?: false
        if (!waitingForResult) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                waitingForResult = true; notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else begin(fromAccessibilitySettings = accessibilityRequested)
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("accessibility-requested", accessibilityRequested)
        outState.putBoolean("waiting-for-result", waitingForResult)
        super.onSaveInstanceState(outState)
    }
    private fun begin(fromAccessibilitySettings: Boolean = false) {
        if (controller.state.value.started) { finish(); return }
        lifecycleScope.launch {
            val enabled = ScreenAccessibilityService.isEnabled(this@ScreenTranslationPermissionActivity)
            if (!enabled && !accessibilityRequested) {
                // One automatic visit per startup attempt; returning without
                // enabling must never reopen Settings in a loop.
                accessibilityRequested = true
                try { waitingForResult = true; accessibility.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return@launch }
                catch (_: ActivityNotFoundException) { waitingForResult = false; Toast.makeText(this@ScreenTranslationPermissionActivity, R.string.screen_translation_accessibility_settings_unavailable, Toast.LENGTH_SHORT).show() }
            }
            if (fromAccessibilitySettings && enabled) controller.preferences.update { it.copy(preferAccessibility = true) }
            val settings = controller.preferences.current()
            if (enabled && settings.usesAccessibility && ScreenAccessibilityService.instance == null)
                withTimeoutOrNull(2_000) { controller.accessibilityAvailable.first { it } }
            val assist = settings.usesAccessibility && ScreenAccessibilityService.instance != null
            if (settings.requiresAccessibility && !assist) {
                val message = getString(R.string.screen_translation_accessibility_required)
                controller.state.value = controller.state.value.copy(status = message)
                Toast.makeText(this@ScreenTranslationPermissionActivity, message, Toast.LENGTH_SHORT).show(); finish(); return@launch
            }
            if (settings.requiresAccessibility && Build.VERSION.SDK_INT < 34) {
                val message = "当前系统没有干净连续截图能力，请使用手动截图模式"
                controller.state.value = controller.state.value.copy(status = message)
                Toast.makeText(this@ScreenTranslationPermissionActivity, message, Toast.LENGTH_LONG).show(); finish(); return@launch
            }
            if (assist && Build.VERSION.SDK_INT >= 34 && !settings.requiresAccessibility) {
                ContextCompat.startForegroundService(this@ScreenTranslationPermissionActivity, Intent(this@ScreenTranslationPermissionActivity, ScreenTranslationService::class.java).setAction(ScreenTranslationService.START_ACCESSIBILITY)); finish()
            } else if (!assist && !Settings.canDrawOverlays(this@ScreenTranslationPermissionActivity)) {
                waitingForResult = true; overlay.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            else beginCapture()
        }
    }
    private fun beginCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        waitingForResult = true
        val config = if (Build.VERSION.SDK_INT >= 37 && controller.preferences.settings.value.requiresAccessibility) {
            MediaProjectionConfig.Builder().setSourceEnabled(MediaProjectionConfig.PROJECTION_SOURCE_DISPLAY, false)
                .setSourceEnabled(MediaProjectionConfig.PROJECTION_SOURCE_APP_CONTENT, false)
                .setSourceEnabled(MediaProjectionConfig.PROJECTION_SOURCE_APP, true)
                .setInitiallySelectedSource(MediaProjectionConfig.PROJECTION_SOURCE_APP).build()
        } else if (Build.VERSION.SDK_INT >= 34) MediaProjectionConfig.createConfigForUserChoice() else null
        capture.launch(if (config != null && Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(config) else manager.createScreenCaptureIntent())
    }
}
