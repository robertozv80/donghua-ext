package com.donghuaworld

import android.content.Context
import com.lagradost.cloudstream3.plugins.Plugin

class DonghuaWorldPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DonghuaWorldProvider())
    }
}
