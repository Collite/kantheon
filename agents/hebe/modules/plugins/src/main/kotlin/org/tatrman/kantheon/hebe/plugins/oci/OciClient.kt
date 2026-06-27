@file:Suppress("TooGenericExceptionCaught", "MagicNumber", "UseCheckOrError", "UnusedPrivateProperty", "MaxLineLength")

package org.tatrman.kantheon.hebe.plugins.oci

import org.tatrman.kantheon.hebe.config.SecretStoreProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.Logger

data class PulledArtifact(
    val archivePath: Path,
    val signaturePath: Path?,
    val digest: String,
)

class OciClient(
    private val registry: String,
    private val secretStore: SecretStoreProvider?,
    private val log: Logger,
) {
    private val httpClient =
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 30_000
            }
        }

    private val cacheDir: Path = Path.of(System.getProperty("user.home"), ".hebe", "cache", "oci")

    suspend fun pull(ref: String): PulledArtifact {
        val (registryHost, repo, tag) = parseRef(ref)
        log.info("Pulling OCI artifact: {}/{}:{}", registryHost, repo, tag)

        Files.createDirectories(cacheDir)

        val authToken = resolveAuthToken(registryHost)
        val manifest = fetchManifest(registryHost, repo, tag, authToken)
        val digest = manifest.digest
        val cachedPath = cacheDir.resolve(digest.replace(":", "_"))
        if (Files.exists(cachedPath)) {
            log.info("Cache hit for artifact {}, digest {}", ref, digest)
            return PulledArtifact(cachedPath, null, digest)
        }

        val archiveLayer =
            manifest.layers.firstOrNull()
                ?: throw IllegalStateException("No archive layer found in manifest")

        val archiveBytes = fetchLayer(registryHost, repo, archiveLayer.digest, authToken)
        Files.write(cachedPath, archiveBytes)
        log.info("Cached artifact {} at {}", ref, cachedPath)

        return PulledArtifact(cachedPath, null, digest)
    }

    private suspend fun fetchManifest(
        registryHost: String,
        repo: String,
        tag: String,
        authToken: AuthToken?,
    ): OciManifest {
        val url = "https://$registryHost/v2/$repo/manifests/$tag"
        val response =
            httpClient.get(url) {
                headers {
                    if (authToken != null) {
                        when (authToken) {
                            is AuthToken.Bearer -> append(HttpHeaders.Authorization, "Bearer ${authToken.token}")
                            is AuthToken.Basic -> append(HttpHeaders.Authorization, "Basic ${authToken.token}")
                        }
                    }
                    append(HttpHeaders.Accept, "application/vnd.oci.image.manifest.v1+json")
                    append(HttpHeaders.Accept, "application/vnd.docker.distribution.manifest.v2+json")
                }
            }
        val json = response.bodyAsText()
        return parseManifest(json)
    }

    private suspend fun fetchLayer(
        registryHost: String,
        repo: String,
        digest: String,
        authToken: AuthToken?,
    ): ByteArray {
        val url = "https://$registryHost/v2/$repo/blobs/$digest"
        val response =
            httpClient.get(url) {
                headers {
                    if (authToken != null) {
                        when (authToken) {
                            is AuthToken.Bearer -> append(HttpHeaders.Authorization, "Bearer ${authToken.token}")
                            is AuthToken.Basic -> append(HttpHeaders.Authorization, "Basic ${authToken.token}")
                        }
                    }
                }
            }
        return response.bodyAsBytes()
    }

    private sealed class AuthToken {
        data class Bearer(
            val token: String,
        ) : AuthToken()

        data class Basic(
            val token: String,
        ) : AuthToken()
    }

    private suspend fun resolveAuthToken(registryHost: String): AuthToken? =
        if (isAzureContainerRegistry(registryHost)) {
            fetchAcrToken(registryHost)?.let { AuthToken.Bearer(it) }
        } else {
            loadDockerConfigAuth(registryHost)?.let { AuthToken.Basic(it) }
        }

    private fun isAzureContainerRegistry(registryHost: String): Boolean = registryHost.endsWith(".azurecr.io")

    private suspend fun fetchAcrToken(registryHost: String): String? {
        val tenantId = System.getenv("AZURE_TENANT_ID") ?: return null
        val clientId = System.getenv("AZURE_CLIENT_ID") ?: return null
        val clientSecret = System.getenv("AZURE_CLIENT_SECRET") ?: return null

        log.debug("Using Azure credential chain for ACR auth")

        return try {
            val tokenResponse =
                httpClient.post("https://login.microsoftonline.com/$tenantId/oauth2/v2.0/token") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(
                        "client_id=$clientId&client_secret=$clientSecret&scope=$registryHost/.default&grant_type=client_credentials",
                    )
                }
            val tokenJson = tokenResponse.bodyAsText().parseJson().jsonObject
            tokenJson["access_token"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            log.warn("Failed to fetch ACR token: {}", e.message)
            null
        }
    }

    private fun loadDockerConfigAuth(registryHost: String): String? {
        return try {
            val dockerConfig = Path.of(System.getProperty("user.home"), ".docker", "config.json")
            if (!Files.exists(dockerConfig)) return null
            val content = Files.readString(dockerConfig)
            val json = content.parseJson().jsonObject
            val auths = json["auths"]?.jsonObject ?: return null
            val registryAuth = auths[registryHost]?.jsonObject ?: return null
            registryAuth["auth"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            log.debug("Failed to load docker config auth: {}", e.message)
            null
        }
    }

    private fun parseRef(ref: String): Triple<String, String, String> {
        val uri = URI("docker://$ref")
        val host = uri.host ?: throw IllegalArgumentException("Invalid OCI ref: $ref")
        val path = uri.path?.trimStart('/') ?: throw IllegalArgumentException("Invalid OCI ref: $ref")
        val lastColon = path.lastIndexOf(':')
        val repo = if (lastColon > 0) path.substring(0, lastColon) else path
        val tag = if (lastColon > 0) path.substring(lastColon + 1) else "latest"
        return Triple(host, repo, tag)
    }

    private fun parseManifest(json: String): OciManifest {
        val root = json.parseJson().jsonObject
        val manifestVersion = root["mediaType"]?.jsonPrimitive?.content ?: ""

        val configObj =
            root["config"]?.jsonObject
                ?: throw IllegalStateException("Missing config in OCI manifest")
        val configDigest =
            configObj["digest"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Missing config digest")
        val configSize = configObj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

        val layersArray = root["layers"]?.jsonArray ?: JsonArray(emptyList())
        val layers =
            layersArray.map { layerObj ->
                val obj = layerObj.jsonObject
                OciLayer(
                    digest = obj["digest"]?.jsonPrimitive?.content ?: "",
                    size = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                )
            }

        return OciManifest(
            mediaType = manifestVersion,
            digest = configDigest,
            config = OciLayer(configDigest, configSize),
            layers = layers,
        )
    }

    private fun String.parseJson(): kotlinx.serialization.json.JsonElement =
        kotlinx.serialization.json.Json
            .parseToJsonElement(this)

    data class OciManifest(
        val mediaType: String,
        val digest: String,
        val config: OciLayer,
        val layers: List<OciLayer>,
    )

    data class OciLayer(
        val digest: String,
        val size: Long,
    )
}
