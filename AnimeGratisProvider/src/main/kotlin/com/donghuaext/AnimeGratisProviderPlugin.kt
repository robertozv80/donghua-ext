package com.donghuaext

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimeGratisProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeGratisProvider())
    }
}
