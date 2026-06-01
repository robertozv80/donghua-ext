package com.donghuaext

import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MundoDonghuaProviderPlugin : Plugin() {
    override fun load() {
        registerMainAPI(MundoDonghuaProvider())
    }
}
