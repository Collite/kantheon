package org.tatrman.kantheon.hebe.tools.dispatch

import org.tatrman.kantheon.hebe.api.Tool

class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.spec.name] = tool
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun get(name: String): Tool? = tools[name]

    fun list(): List<Tool> = tools.values.toList()
}
