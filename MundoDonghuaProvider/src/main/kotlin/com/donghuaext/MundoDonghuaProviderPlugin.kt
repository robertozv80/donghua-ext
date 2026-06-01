package com.donghuaext

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MundoDonghuaProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MundoDonghuaProvider())
    }
}
