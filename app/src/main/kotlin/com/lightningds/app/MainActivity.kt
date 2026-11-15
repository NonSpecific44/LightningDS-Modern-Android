package com.lightningds.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lightningds.app.ui.LightningAppUi
import com.lightningds.app.ui.components.LocalController
import com.lightningds.app.ui.theme.LightningTheme

class MainActivity : ComponentActivity() {
    private val controller get() = (application as LightningApp).controller
    /** Our own connection to the media session while on screen (the standard media3 setup). */
    private var sessionController: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by controller.settings.settings.collectAsStateWithLifecycle()
            LaunchedEffect(settings.keepScreenOn) {
                if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            LightningTheme(settings.themeMode, settings.dynamicColor) {
                CompositionLocalProvider(LocalController provides controller) {
                    LightningAppUi()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.onForeground()
        PlaybackService.start(this)
        if (sessionController == null) {
            sessionController = runCatching {
                val token = androidx.media3.session.SessionToken(this, android.content.ComponentName(this, PlaybackService::class.java))
                androidx.media3.session.MediaController.Builder(this, token).buildAsync()
            }.getOrNull()
        }
    }

    override fun onStop() {
        super.onStop()
        controller.onBackground()
    }

    override fun onDestroy() {
        sessionController?.let { androidx.media3.session.MediaController.releaseFuture(it) }
        sessionController = null
        super.onDestroy()
    }
}
