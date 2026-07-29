package org.tatrman.kantheon.iris

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorEngine

/**
 * review-078 R3 — the `response-write-timeout-s` key iris-bff declares is actually *read*.
 *
 * ST-P2·S1·T3 built the resolution path in `KtorConfigFactory` and tested it there; S1's stage
 * note left declaring the key in each service to S2, and S2's task list never picked it up. So
 * `contracts.md` §3 published a tunable that no service declared: an operator who set
 * `KTOR_RESPONSE_WRITE_TIMEOUT_S` during an incident would have got nothing, silently. That is
 * the same invisible-config failure this whole effort exists to remove, which is why declaring
 * the key without pinning that it resolves would only move the problem.
 *
 * Asserting `180` against the shipped config would prove nothing — 180 is also the library
 * default, so a key that is never read gives the same answer. The discriminating case is the
 * second one: a *different* value, which can only appear if the factory really reads the path
 * iris-bff declares it under.
 *
 * The `${?KTOR_RESPONSE_WRITE_TIMEOUT_S}` half is plain HOCON substitution over that same path —
 * the identical mechanism that has always driven `IRIS_BFF_HTTP_PORT` two lines above it.
 */
class ResponseWriteTimeoutConfigSpec :
    StringSpec({

        "iris-bff's application.conf declares the write-timeout key" {
            withClue(
                "the key must exist under ktor.deployment — that is the section " +
                    "KtorConfigFactory.fromConfig falls back to when there is no `server` section, " +
                    "and iris-bff has none",
            ) {
                ConfigFactory.load().hasPath("ktor.deployment.response-write-timeout-s") shouldBe true
            }
        }

        "the declared key is the one KtorConfigFactory resolves from" {
            val overridden =
                ConfigFactory
                    .parseString("ktor.deployment.response-write-timeout-s = 42")
                    .withFallback(ConfigFactory.load())

            val resolved = KtorConfigFactory.fromConfig(overridden, "iris-bff", 7410, KtorEngine.NETTY)

            withClue(
                "resolved ${resolved.responseWriteTimeoutSeconds}s instead of 42s — the declaration " +
                    "in application.conf is dead config. Either the path moved or the service grew a " +
                    "`server` section, which takes precedence (contracts §3).",
            ) {
                resolved.responseWriteTimeoutSeconds shouldBe 42
            }
        }

        "with nothing declared, the library default stands" {
            val bare =
                ConfigFactory.parseString(
                    """
                    ktor.deployment.port = 7410
                    telemetry.enabled = false
                    """.trimIndent(),
                )

            KtorConfigFactory
                .fromConfig(bare, "iris-bff", 7410, KtorEngine.NETTY)
                .responseWriteTimeoutSeconds shouldBe 180
        }
    })
