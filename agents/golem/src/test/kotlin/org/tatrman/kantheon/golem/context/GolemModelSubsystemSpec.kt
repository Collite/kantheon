package org.tatrman.kantheon.golem.context

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.tatrman.meta.v1.GetModelResponse
import org.tatrman.meta.v1.ModelBundle
import org.tatrman.meta.v1.PackageVersion
import org.tatrman.veles.client.MetadataGrpcClient
import org.tatrman.kantheon.golem.GolemReadiness
import org.tatrman.kantheon.golem.api.refreshRoutes
import org.tatrman.kantheon.golem.prompts.PromptStore
import org.tatrman.kantheon.golem.shem.assembledShemContext
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

private fun modelResponse(hash: String): GetModelResponse =
    GetModelResponse
        .newBuilder()
        .setModel(
            ModelBundle.newBuilder().addPackageVersions(
                PackageVersion.newBuilder().setPackageName("erp").setContentHash(hash),
            ),
        ).build()

/** Mount `intent.yaml` under a fresh temp Shem-bundle dir and return its root. */
private fun mountedShemDir(): java.nio.file.Path {
    val root = Files.createTempDirectory("golem-shem-sub")
    val dir = root.resolve("prompts").resolve("cs")
    dir.createDirectories()
    dir.resolve("intent.yaml").writeText("{{ question }}")
    return root
}

private fun subsystem(client: MetadataGrpcClient): GolemModelSubsystem =
    GolemModelSubsystem(
        shem = assembledShemContext(),
        packageContext = PackageContext(client, packages = listOf("erp")),
        promptStore = PromptStore(shemDir = mountedShemDir(), locale = "cs", fallback = { emptyMap() }),
        ariadneClient = client,
    )

private fun happyClient(): MetadataGrpcClient {
    val client = mockk<MetadataGrpcClient>()
    coEvery { client.getModel(any(), any(), any(), any(), any()) } returns modelResponse("h1")
    return client
}

/**
 * T8 — readiness gating (DB ∧ Shem model+prompts) and the `/v1/refresh` ops route.
 */
class GolemModelSubsystemSpec :
    StringSpec({

        "readiness is false until the configured Shem's model + prompts have loaded" {
            runTest {
                val sub = subsystem(happyClient())
                val readiness = GolemReadiness(dbReady = true, model = sub)

                readiness.isReady() shouldBe false
                sub.load()
                readiness.isReady() shouldBe true
            }
        }

        "readiness fails when the DB is not ready even if the model loaded" {
            runTest {
                val sub = subsystem(happyClient())
                sub.load()
                GolemReadiness(dbReady = false, model = sub).isReady() shouldBe false
            }
        }

        "a Shem-less (skeleton) subsystem is ready on the DB gate alone" {
            val empty =
                GolemModelSubsystem(shem = null, packageContext = null, promptStore = null, ariadneClient = null)
            empty.isReady shouldBe true
            GolemReadiness(dbReady = true, model = empty).isReady() shouldBe true
        }

        "a failed initial model load leaves the pod not-ready (load is warn-and-continue)" {
            runTest {
                val client = mockk<MetadataGrpcClient>()
                coEvery { client.getModel(any(), any(), any(), any(), any()) } throws RuntimeException("ariadne down")
                val sub = subsystem(client)

                sub.load() // must not throw
                sub.isReady shouldBe false // model never loaded
            }
        }

        "POST /v1/refresh re-pulls and returns 200" {
            testApplication {
                val sub = subsystem(happyClient())
                application { routing { refreshRoutes(sub) } }
                client.post("/v1/refresh").status shouldBe HttpStatusCode.OK
                sub.isReady shouldBe true
            }
        }

        "POST /v1/refresh returns 503 when the reload fails" {
            testApplication {
                val client = mockk<MetadataGrpcClient>()
                coEvery { client.getModel(any(), any(), any(), any(), any()) } throws RuntimeException("down")
                application { routing { refreshRoutes(subsystem(client)) } }
                this.client.post("/v1/refresh").status shouldBe HttpStatusCode.ServiceUnavailable
            }
        }
    })
