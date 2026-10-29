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
    }

    override fun onStop() {
        super.onStop()
        controller.onBackground()
    }
}
