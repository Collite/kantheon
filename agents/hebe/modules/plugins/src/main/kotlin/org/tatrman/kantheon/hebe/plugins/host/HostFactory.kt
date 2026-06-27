@file:Suppress("NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.plugins.host

import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.plugin.api.PluginManifest
import org.slf4j.Logger

class HostFactory(
    private val secretResolver: (String) -> String?,
    private val observer: Observer,
    private val logger: Logger,
) {
    fun create(
        pluginId: String,
        pluginManifest: PluginManifest,
    ): RealPluginHost =
        RealPluginHost(
            pluginId = pluginId,
            pluginManifest = pluginManifest,
            secretResolver = secretResolver,
            observer = observer,
            log = logger,
        )
}
