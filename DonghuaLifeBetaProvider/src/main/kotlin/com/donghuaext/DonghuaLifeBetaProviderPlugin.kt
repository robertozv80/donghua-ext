package com.donghuaext

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DonghuaLifeBetaProviderPlugin : Plugin() {
    override fun load(context: Context) {
        // Register all providers from this module
        registerMainAPI(DonghuaLifeBetaProvider())
    }
}
