package org.tatrman.kantheon.testkit.integration

import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * Guards the fixture *paths* the WireMock loader depends on — with no container
 * and no Docker, so it fails in milliseconds and says what is missing.
 *
 * Why this exists. `WireMockAdmin.importMappingsFromResource` resolves
 * `wiremock/<context>/<scenario>/mappings.json` off the classpath and throws
 * `IllegalStateException` when it is absent. That is correct behaviour, but it
 * surfaces *inside* [InClusterWireMockLoaderSpec] after a container has already
 * started, as an opaque ISE on the import line — which is how a rename left CI
 * red for three weeks reading like a flaky container test.
 *
 * The rename: the 2026-07-10 persona sweep (`SV-P0 S5`) moved the context name
 * `theseus-runquery` → `query-runquery` in Kotlin sources but did not rename the
 * resource **directory**, so the loader asked for a path that no longer existed.
 * It was invisible to `./gradlew build`, which does not run `componentTest`.
 *
 * So: every context directory that exists must hold at least one readable
 * `mappings.json`, and the exact resource the loader spec asks for must resolve.
 * A path typo now fails by name, before anything is started.
 */
@Tags("component")
class WireMockFixtureResourcesSpec :
    StringSpec({

        val loader = WireMockFixtureResourcesSpec::class.java.classLoader

        "the fixture the in-cluster loader spec imports resolves on the classpath" {
            // Kept literally in step with InClusterWireMockLoaderSpec's import call.
            // If that path changes, this fails first and names the file.
            loader.getResource("wiremock/$LOADER_CONTEXT/healthz/mappings.json").shouldNotBeNull()
        }

        "the loader spec's context name matches the fixture directory it loads from" {
            // The two drifted apart once already, in opposite halves of the same
            // sweep: the source said `query-runquery`, the directory still said
            // `theseus-runquery`.
            val contexts = fixtureRoot().listFiles { f: File -> f.isDirectory }?.map { it.name }.orEmpty()
            contexts.shouldNotBeEmpty()
            contexts.contains(LOADER_CONTEXT) shouldBe true
        }

        "every context fixture directory holds a readable mappings.json" {
            fixtureRoot()
                .listFiles { f: File -> f.isDirectory }
                .orEmpty()
                .forEach { context ->
                    val scenarios = context.listFiles { f: File -> f.isDirectory }.orEmpty()
                    withClue(context.name) { scenarios.shouldNotBeEmpty() }
                    scenarios.forEach { scenario ->
                        val path = "wiremock/${context.name}/${scenario.name}/mappings.json"
                        val text =
                            loader
                                .getResourceAsStream(path)
                                ?.bufferedReader()
                                ?.use { it.readText() }
                        withClue(path) {
                            // Shape only — no JSON parser on this module's test
                            // classpath, and the point here is that the file is
                            // REACHABLE and looks like what `/__admin/mappings/import`
                            // is given. WireMock rejects malformed bodies loudly.
                            text.shouldNotBeNull() shouldContain "\"mappings\""
                            text shouldContain "\"request\""
                        }
                    }
                }
        }
    })

/**
 * The context [InClusterWireMockLoaderSpec] drives; see the note above about the rename.
 *
 * **Still open, one layer up:** the context name is a *cross-repo* key — olymp's
 * `test-contexts/theseus-runquery/context.yaml` says so in as many words ("THE
 * cross-repo key (C1); matches kantheon @RequiresContext") — and olymp was never
 * renamed. Nothing is broken today: no integration spec carries
 * `@RequiresContext("query-runquery")`, so [ContextNameRegistrySpec] has nothing
 * to mismatch, and the component tier's context string is a pure label that never
 * reaches a cluster. The day a query integration suite is written, one of the two
 * repos has to move. Deciding which is deployment composition, not test hygiene.
 */
private const val LOADER_CONTEXT = "query-runquery"

/**
 * The fixture tree on disk. Read from the source tree rather than the classpath
 * because the assertion is about *which directories exist* — a classpath scan
 * cannot enumerate them portably, and enumerating is the whole point.
 */
private fun fixtureRoot(): File =
    File("src/componentTest/resources/wiremock").also {
        check(it.isDirectory) { "fixture root missing: ${it.absolutePath}" }
    }
