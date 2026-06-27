package org.tatrman.kantheon.golem.context

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.tatrman.ariadne.v1.EntityDetail
import org.tatrman.ariadne.v1.GetModelResponse
import org.tatrman.ariadne.v1.ModelBundle
import org.tatrman.ariadne.v1.ModelBundleEntity
import org.tatrman.ariadne.v1.ModelBundleQuery
import org.tatrman.ariadne.v1.ObjectDescriptor
import org.tatrman.ariadne.v1.PackageVersion
import org.tatrman.ariadne.v1.ResolveAreaResponse
import org.tatrman.kantheon.ariadne.client.MetadataGrpcClient
import org.tatrman.kantheon.capabilities.v1.AgentKind
import org.tatrman.kantheon.capabilities.v1.IntentKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

private val OVERLAY_YAML =
    """
    apiVersion: kantheon.shem/v1
    kind: golem-shem
    source:
      repo: ai-models
      agentDef: agents/ucetnictvi.yaml
      id: ucetnictvi
      label: "Účetnictví"
      areas: [accounting]
    overlay:
      visibility_roles:
        - kantheon-area-accounting
    """.trimIndent()

/** Mount a temp Shem bundle: `shem.yaml` overlay + `prompts/cs/intent.yaml`. */
private fun mountedShemBundle(): Path {
    val root = Files.createTempDirectory("golem-shem-boot")
    root.resolve("shem.yaml").writeText(OVERLAY_YAML)
    val prompts = root.resolve("prompts").resolve("cs")
    prompts.createDirectories()
    prompts.resolve("intent.yaml").writeText("{{ question }}")
    return root
}

private fun bootClient(): MetadataGrpcClient {
    val client = mockk<MetadataGrpcClient>(relaxed = true)
    coEvery { client.resolveArea("accounting") } returns
        ResolveAreaResponse
            .newBuilder()
            .addAllPackages(listOf("obchodni_doklady", "ucetnictvi"))
            .setDescription("Účetnictví a navazující obchodní doklady")
            .addAllTags(listOf("finance"))
            .setFound(true)
            .build()
    coEvery { client.getModel(any(), any(), any(), any(), any()) } returns
        GetModelResponse
            .newBuilder()
            .setModel(
                ModelBundle
                    .newBuilder()
                    .addPackageVersions(PackageVersion.newBuilder().setPackageName("ucetnictvi").setContentHash("h1"))
                    .addEntities(
                        ModelBundleEntity
                            .newBuilder()
                            .setObjectDescriptor(
                                ObjectDescriptor.newBuilder().setLocalName("ucet").setDescription("Účetní účet"),
                            ).setDetail(EntityDetail.newBuilder().addAliases("account")),
                    ).addPatternQueries(
                        ModelBundleQuery
                            .newBuilder()
                            .setObjectDescriptor(ObjectDescriptor.newBuilder().setLocalName("zustatkyUctu")),
                    ),
            ).build()
    return client
}

/**
 * T7 — boot the Shem subsystem from a mounted `shem.yaml` + prompts against a mocked
 * Ariadne client, then assert the **assembled** `AgentCapability` (the registration
 * payload) + readiness gate. Drives `fromConfig` via a real config but injects the
 * mocked client through a test-only constructor path so no gRPC is needed.
 */
class ShemAssemblyBootSpec :
    StringSpec({

        "boot assembles the overlay + resolved area + model into the registered AgentCapability" {
            runTest {
                val client = bootClient()
                // fromConfig resolves areas at construction; mirror that wiring with the mocked client.
                val sub = GolemModelSubsystem.build(mountedShemBundle(), client, locale = "cs")

                // Pre-load: overlay-only fields are present, model-derived empty, not ready.
                sub.shem!!.agentId shouldBe "golem-ucetnictvi"
                sub.shem!!.visibilityRoles shouldContainExactly listOf("kantheon-area-accounting")
                sub.isReady shouldBe false

                sub.load()

                val cap = sub.shem!!.manifest
                cap.agentKind shouldBe AgentKind.AREA_QA
                cap.agentId shouldBe "golem-ucetnictvi"
                cap.displayName shouldBe "Účetnictví"
                cap.areaName shouldBe "accounting"
                cap.intentKindsSupportedList shouldContainExactly listOf(IntentKind.PROCEDURAL)
                cap.serviceEndpoint shouldBe "http://golem-ucetnictvi.kantheon.svc.cluster.local:7420"
                cap.visibilityRolesList shouldContainExactly listOf("kantheon-area-accounting")
                // description_for_router seeded from the resolved area (overlay omitted it).
                cap.descriptionForRouter shouldContain "Účetnictví a navazující obchodní doklady"
                // model-derived
                cap.areaEntitiesList shouldContainExactly listOf("ucet")
                cap.preferredQueriesList shouldContainExactly listOf("zustatkyUctu")
                cap.areaTerminologyList.single().term shouldBe "ucet"
                cap.areaTerminologyList.single().synonymsList shouldContainExactly listOf("account")

                sub.isReady shouldBe true
                sub.close()
            }
        }
    })
