package com.luciferdonghua

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class LuciferDonghuaProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LuciferDonghuaProvider())
    }
}
