package com.example

import org.tatrman.kantheon.hebe.plugin.api.HebePlugin
import org.tatrman.kantheon.hebe.plugin.api.PluginHost
import org.tatrman.kantheon.hebe.api.Tool

class HelloPlugin(wrapper: org.pf4j.PluginWrapper) : HebePlugin(wrapper) {
    override fun tools(host: PluginHost): List<Tool> = listOf(SayHelloTool(host))
}