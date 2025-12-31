package recloudstream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class TamilDhoolClickPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(TamilDhoolClickProvider())
    }
}
