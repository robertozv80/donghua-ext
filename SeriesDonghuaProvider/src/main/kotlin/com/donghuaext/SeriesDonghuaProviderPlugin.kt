package com.donghuaext

import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class SeriesDonghuaProviderPlugin : Plugin() {
    override fun load() {
        registerMainAPI(SeriesDonghuaProvider())
    }
}
