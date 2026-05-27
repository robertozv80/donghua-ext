package com.donghuaext

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SeriesDonghuaProviderPlugin : Plugin() {
    override fun load() {
        registerMainAPI(SeriesDonghuaProvider())
    }
}