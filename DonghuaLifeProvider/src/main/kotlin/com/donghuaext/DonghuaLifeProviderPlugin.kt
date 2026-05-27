package com.donghuaext

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DonghuaLifeProviderPlugin : Plugin() {
    override fun load() {
        registerMainAPI(DonghuaLifeProvider())
    }
}