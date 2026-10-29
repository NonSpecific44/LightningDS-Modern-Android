package com.lightningds.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

class LightningApp : Application(), SingletonImageLoader.Factory {
    lateinit var controller: AppController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = AppController(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { controller.http })) }
        .crossfade(true)
        .build()
}

object BuildConfigCompat {
    fun versionName(context: android.content.Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
}
