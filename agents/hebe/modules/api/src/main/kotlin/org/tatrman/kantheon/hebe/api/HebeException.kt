package org.tatrman.kantheon.hebe.api

sealed class HebeException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class Config(
        message: String,
    ) : HebeException(message)

    class Provider(
        val retriable: Boolean,
        message: String,
        cause: Throwable? = null,
    ) : HebeException(message, cause)

    class Tool(
        val tool: String,
        val retriable: Boolean,
        message: String,
    ) : HebeException(message)

    class Plugin(
        val pluginId: String,
        message: String,
        cause: Throwable? = null,
    ) : HebeException(message, cause)

    class Security(
        message: String,
    ) : HebeException(message)

    class PolicyDenied(
        message: String,
    ) : HebeException(message)

    class Approval(
        message: String,
    ) : HebeException(message)

    class Memory(
        message: String,
    ) : HebeException(message)

    class Channel(
        val channel: String,
        message: String,
        cause: Throwable? = null,
    ) : HebeException(message, cause)
}
