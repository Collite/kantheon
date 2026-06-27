package org.tatrman.kantheon.capabilities.registry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.capabilities.asCapability
import org.tatrman.kantheon.capabilities.toolCapability
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class TtlPrunerSpec :
    StringSpec({

        "runtime entries past the TTL are excluded from list()" {
            val t0 = Instant.parse("2026-05-28T12:00:00Z")
            var current = t0
            val clock =
                object : Clock() {
                    override fun getZone() = ZoneOffset.UTC

                    override fun withZone(z: java.time.ZoneId?) = this

                    override fun instant() = current
                }
            val reg = InMemoryRegistry(clock = clock)
            reg.register(toolCapability("theseus.query:v1").asCapability())
            val pruner = TtlPruner(reg, ttl = Duration.ofSeconds(300), clock = clock)
            current = t0.plusSeconds(301)
            pruner.prune() shouldBe 1
            reg.list() shouldHaveSize 0
        }

        "pruned entries remain fetchable via get()" {
            val t0 = Instant.parse("2026-05-28T12:00:00Z")
            var current = t0
            val clock =
                object : Clock() {
                    override fun getZone() = ZoneOffset.UTC

                    override fun withZone(z: java.time.ZoneId?) = this

                    override fun instant() = current
                }
            val reg = InMemoryRegistry(clock = clock)
            reg.register(toolCapability("theseus.query:v1").asCapability())
            val pruner = TtlPruner(reg, ttl = Duration.ofSeconds(60), clock = clock)
            current = t0.plusSeconds(61)
            pruner.prune() shouldBe 1
            reg.list() shouldHaveSize 0
            reg.get("theseus.query:v1").shouldNotBeNull().pruned shouldBe true
        }

        "fixtures (last_heartbeat_at == null) are never pruned" {
            val t0 = Instant.parse("2026-05-28T12:00:00Z")
            var current = t0
            val clock =
                object : Clock() {
                    override fun getZone() = ZoneOffset.UTC

                    override fun withZone(z: java.time.ZoneId?) = this

                    override fun instant() = current
                }
            val reg = InMemoryRegistry(clock = clock)
            reg.register(toolCapability("theseus.query:v1").asCapability(), fromFixture = true)
            val pruner = TtlPruner(reg, ttl = Duration.ofSeconds(1), clock = clock)
            current = t0.plusSeconds(3600)
            pruner.prune() shouldBe 0
            reg.list().map { it.capability.tool.capabilityId } shouldContain "theseus.query:v1"
        }

        "a re-register or heartbeat un-prunes a previously stale entry" {
            val t0 = Instant.parse("2026-05-28T12:00:00Z")
            var current = t0
            val clock =
                object : Clock() {
                    override fun getZone() = ZoneOffset.UTC

                    override fun withZone(z: java.time.ZoneId?) = this

                    override fun instant() = current
                }
            val reg = InMemoryRegistry(clock = clock)
            reg.register(toolCapability("theseus.query:v1").asCapability())
            val pruner = TtlPruner(reg, ttl = Duration.ofSeconds(60), clock = clock)
            current = t0.plusSeconds(61)
            pruner.prune() shouldBe 1
            reg.list() shouldHaveSize 0
            // Re-register at later wall-clock; should re-appear and un-prune.
            reg.register(toolCapability("theseus.query:v1").asCapability())
            reg.list() shouldHaveSize 1
            reg.get("theseus.query:v1")?.pruned shouldBe false
        }
    })
