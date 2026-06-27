package oras

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest

open class OrasPublishTask : DefaultTask() {

    @get:Input
    open var repository: String = ""

    @get:Input
    open var tag: String = ""

    @get:Input
    open var authToken: String = ""

    @TaskAction
    fun publish() {
        val jarFile = project.file("build/libs/${project.name}-${project.version}.jar")
        if (!jarFile.exists()) {
            throw IllegalStateException("JAR not found: ${jarFile.absolutePath}. Run './gradlew jar' first.")
        }

        if (repository.isBlank()) {
            throw IllegalStateException("repository property is required")
        }
        if (tag.isBlank()) {
            throw IllegalStateException("tag property is required")
        }

        println("Publishing ${jarFile.name} to $repository:$tag")

        val client = HttpClient.newHttpClient()
        val sha = sha256(jarFile)

        // Upload blob (layer)
        val blobUri = URI("$repository/blobs/uploads/?digest=sha256:$sha")
        val putRequest = HttpRequest.newBuilder(blobUri)
            .header("Content-Type", "application/vnd.oci.image.layer.v1.tar+gzip")
            .apply {
                if (authToken.isNotBlank()) {
                    header("Authorization", "Bearer $authToken")
                }
            }
            .PUT(HttpRequest.BodyPublishers.ofFile(jarFile.toPath()))
            .build()

        val putResponse = client.send(putRequest, HttpResponse.BodyHandlers.ofString())
        if (putResponse.statusCode() !in listOf(200, 201)) {
            throw RuntimeException("Blob upload failed: ${putResponse.statusCode()} ${putResponse.body()}")
        }
        println("Blob uploaded successfully")

        // Create and push manifest
        val manifest = buildManifest(sha)
        val manifestUri = URI("$repository:$tag")
        val manifestRequest = HttpRequest.newBuilder(manifestUri)
            .header("Content-Type", "application/vnd.oci.image.manifest.v1+json")
            .apply {
                if (authToken.isNotBlank()) {
                    header("Authorization", "Bearer $authToken")
                }
            }
            .PUT(HttpRequest.BodyPublishers.ofString(manifest))
            .build()

        val manifestResponse = client.send(manifestRequest, HttpResponse.BodyHandlers.ofString())
        if (manifestResponse.statusCode() !in listOf(200, 201)) {
            throw RuntimeException("Manifest push failed: ${manifestResponse.statusCode()} ${manifestResponse.body()}")
        }
        println("Published $repository:$tag successfully")
    }

    private fun buildManifest(sha: String): String {
        val jarFile = project.file("build/libs/${project.name}-${project.version}.jar")
        val size = jarFile.length()
        return """
        {
            "schemaVersion": 2,
            "mediaType": "application/vnd.oci.image.manifest.v1+json",
            "config": {
                "mediaType": "application/vnd.oci.image.config.v1+json",
                "size": 37,
                "digest": "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
            },
            "layers": [
                {
                    "mediaType": "application/vnd.oci.image.layer.v1.tar+gzip",
                    "size": $size,
                    "digest": "sha256:$sha"
                }
            ]
        }
        """.trimIndent()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(file.readBytes())
        return hash.joinToString("") { "%02x".format(it) }
    }
}

val orasPublish by tasks.registering(OrasPublishTask::class) {
    group = "publishing"
    description = "Push plugin JAR to an OCI registry using ORAS protocol"
}

tasks.configureEach("orasPublish") {
    (this as OrasPublishTask).apply {
        repository = project.findProperty("oras.repository") as? String ?: ""
        tag = project.findProperty("oras.tag") as? String ?: ""
        authToken = project.findProperty("oras.authToken") as? String ?: ""
    }
}