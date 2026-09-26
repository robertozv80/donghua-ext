package com.donghuatiodonghua

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TioDonghuaProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TioDonghuaProvider())
    }
}
