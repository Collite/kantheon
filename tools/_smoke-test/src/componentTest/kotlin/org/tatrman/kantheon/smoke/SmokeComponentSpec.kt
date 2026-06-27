package org.tatrman.kantheon.smoke

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * Stage 1.1 T3 — proves the `componentTest` plumbing end to end: a real
 * container boots under Testcontainers inside the JVM test process (no
 * Kubernetes). Green in CI ⇒ Docker-in-CI and the `componentTest` source set
 * both work, so the real-dep specs (Stage 1.2) have a foundation to land on.
 *
 * Marked with Kotest's `@Tags("component")` — Kotest uses its own tagging, not
 * JUnit-Platform `@Tag` (see the convention build in the root `build.gradle.kts`).
 * Isolation from the mocked `test` gate is enforced by the separate source set.
 */
@Tags("component")
class SmokeComponentSpec :
    StringSpec({
        "a Testcontainers GenericContainer boots and is reachable" {
            val container: GenericContainer<*> =
                GenericContainer(DockerImageName.parse("alpine:3.20"))
                    .withCommand("sleep", "300")
            container.start()
            try {
                container.isRunning shouldBe true
            } finally {
                container.stop()
            }
        }
    })
