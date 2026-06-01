package com.donghuaext

import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DonghuaLifeProviderPlugin : Plugin() {
    override fun load() {
        registerMainAPI(DonghuaLifeProvider())
    }
}
