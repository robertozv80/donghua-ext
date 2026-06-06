package com.donghuaext

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnimeGratisProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeGratisProvider())
    }
}
