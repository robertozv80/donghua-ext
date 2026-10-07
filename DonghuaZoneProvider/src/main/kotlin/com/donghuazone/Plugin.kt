package com.donghuazone

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DonghuaZoneProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DonghuaZoneProvider())
    }
}
