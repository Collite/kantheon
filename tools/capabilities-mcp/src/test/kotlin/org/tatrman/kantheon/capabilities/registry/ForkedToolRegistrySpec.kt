package org.tatrman.kantheon.capabilities.registry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.capabilities.loader.ManifestYamlLoader
import org.tatrman.kantheon.capabilities.v1.Capability
import java.io.File

/**
 * Fork Stage 4.1 T1 — registry completeness.
 *
 * The forked constellation's full tool surface must be discoverable from
 * capabilities-mcp:
 *   - `run_query`              → theseus.query:v1   (+ theseus.compile:v1)
 *   - the Ariadne model tools  → ariadne.{get_model,get_object,list_objects,list_queries,search,resolve_area}:v1
 *   - `Match`                  → echo.match:v1
 *   - `Analyze`                → kadmos.analyze:v1
 *
 * Two guarantees, per the contract (tasks-p4-s4.1 T1):
 *  1. **Cold start** — capabilities-mcp ships bootstrap fixtures so the inventory
 *     is complete even before any pod heartbeats.
 *  2. **No drift** — each bootstrap fixture must agree, name + schema, with the
 *     authoritative runtime manifest that the owning MCP wrapper actually
 *     registers (fixtures cover bootstrap; runtime supersedes). We read the
 *     sibling modules' manifests directly so a change there fails this test until
 *     the fixture is re-synced.
 *
 * The heartbeat path is exercised with a mocked client (register the parsed
 * ToolCapability straight into a real registry) — true all-pods heartbeat
 * verification lives in the separate integration suite (planning-conventions §4).
 */
class ForkedToolRegistrySpec :
    StringSpec({

        val expectedInventory =
            listOf(
                "theseus.query:v1",
                "theseus.compile:v1",
                "ariadne.get_model:v1",
                "ariadne.get_object:v1",
                "ariadne.list_objects:v1",
                "ariadne.list_queries:v1",
                "ariadne.search:v1",
                "echo.match:v1",
                "kadmos.analyze:v1",
            )

        // capability_id -> the owning MCP wrapper's source-of-truth runtime manifest.
        val runtimeSource =
            mapOf(
                "theseus.query:v1" to "tools/theseus-mcp/src/main/resources/manifests/tools/query.yaml",
                "theseus.compile:v1" to "tools/theseus-mcp/src/main/resources/manifests/tools/compile.yaml",
                "ariadne.get_model:v1" to "tools/ariadne-mcp/src/main/resources/manifests/tools/get_model.yaml",
                "ariadne.get_object:v1" to "tools/ariadne-mcp/src/main/resources/manifests/tools/get_object.yaml",
                "ariadne.list_objects:v1" to "tools/ariadne-mcp/src/main/resources/manifests/tools/list_objects.yaml",
                "ariadne.list_queries:v1" to "tools/ariadne-mcp/src/main/resources/manifests/tools/list_queries.yaml",
                "ariadne.search:v1" to "tools/ariadne-mcp/src/main/resources/manifests/tools/search.yaml",
                "echo.match:v1" to "tools/echo-mcp/src/main/resources/manifests/tools/match.yaml",
                "kadmos.analyze:v1" to "tools/kadmos-mcp/src/main/resources/manifests/tools/analyze.yaml",
            )

        fun bootstrapById(): Map<String, Capability> {
            val registry = InMemoryRegistry()
            val base = repoRoot().resolve("tools/capabilities-mcp/src/main/resources/manifests").toPath()
            ManifestYamlLoader(filesystemBase = base).loadAll(registry)
            return registry.list().associateBy { it.capability.naturalId() }.mapValues { it.value.capability }
        }

        "bootstrap fixtures cold-start the full forked tool inventory" {
            val ids = bootstrapById().keys
            ids shouldContainAll expectedInventory
        }

        "each bootstrap fixture agrees with its runtime source manifest (no drift)" {
            val bootstrap = bootstrapById()
            expectedInventory.forEach { id ->
                val source =
                    ManifestYamlLoader.parseTool(File(repoRoot(), runtimeSource.getValue(id)).readText())
                val fixture = bootstrap[id].shouldNotBeNull()
                fixture.hasTool() shouldBe true
                // Proto equality: names + schema (category, version, description,
                // endpoint, search tags, cost hints) must match the runtime manifest.
                fixture.tool shouldBe source
            }
        }

        "a runtime heartbeat supersedes the bootstrap fixture without losing the inventory" {
            val registry = InMemoryRegistry()
            val base = repoRoot().resolve("tools/capabilities-mcp/src/main/resources/manifests").toPath()
            ManifestYamlLoader(filesystemBase = base).loadAll(registry)

            val fixtureEntry =
                registry.list().first { it.capability.naturalId() == "theseus.query:v1" }
            fixtureEntry.lastHeartbeatAt shouldBe null // fixture: pruner-exempt

            // Simulate the owning pod's heartbeat (mocked client → direct register).
            val live =
                ManifestYamlLoader.parseTool(
                    File(repoRoot(), runtimeSource.getValue("theseus.query:v1")).readText(),
                )
            val regId = registry.register(Capability.newBuilder().setTool(live).build(), fromFixture = false)

            val afterEntry =
                registry.list().first { it.capability.naturalId() == "theseus.query:v1" }
            // Same natural id → same registration id (idempotent), now heartbeat-backed.
            regId shouldBe fixtureEntry.registrationId
            afterEntry.lastHeartbeatAt.shouldNotBeNull()
            // Inventory still complete after the supersede.
            registry.list().map { it.capability.naturalId() } shouldContainAll expectedInventory
        }
    })

/** Walk up from the test working directory to the repo root (marked by settings.gradle.kts). */
internal fun repoRoot(): File {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
        if (File(dir, "settings.gradle.kts").isFile) return dir
        dir = dir.parentFile
    }
    error("could not locate repo root (settings.gradle.kts) from ${File("").absolutePath}")
}
