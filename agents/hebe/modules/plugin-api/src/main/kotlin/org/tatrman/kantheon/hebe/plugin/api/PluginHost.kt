package org.tatrman.kantheon.hebe.plugin.api

import org.tatrman.kantheon.hebe.api.Observer

interface PluginHost {
    val pluginId: String
    val manifest: PluginManifest

    fun http(): GatedHttpClient

    fun env(name: String): String?

    fun secret(name: String): SecretHandle?

    val observer: Observer
    val log: org.slf4j.Logger
}

interface GatedHttpClient {
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        auth: SecretHandle? = null,
    ): HttpResponse

    suspend fun post(
        url: String,
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
        auth: SecretHandle? = null,
    ): HttpResponse

    suspend fun put(
        url: String,
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
        auth: SecretHandle? = null,
    ): HttpResponse

    suspend fun delete(
        url: String,
        headers: Map<String, String> = emptyMap(),
        auth: SecretHandle? = null,
    ): HttpResponse

    suspend fun patch(
        url: String,
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
        auth: SecretHandle? = null,
    ): HttpResponse
}

data class HttpResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
)

data class PluginManifest(
    val hebeApiVersion: String,
    val capabilities: Set<Capability>,
    val permissions: Set<Permission>,
    val allowlistDomains: List<String>,
    val signature: String?,
    val publisherKey: String?,
)
