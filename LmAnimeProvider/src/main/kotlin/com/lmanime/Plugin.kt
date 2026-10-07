package com.lmanime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class LmAnimeProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LmAnimeProvider())
    }
}
