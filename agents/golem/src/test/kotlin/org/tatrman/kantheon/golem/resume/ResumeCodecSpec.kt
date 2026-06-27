package org.tatrman.kantheon.golem.resume

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

private fun codec(
    secret: String = "test-secret",
    ttl: Long = 300,
    nowEpoch: Long = 1_000_000,
) = ResumeCodec(secret, ttl) { Instant.ofEpochSecond(nowEpoch) }

class ResumeCodecSpec :
    StringSpec({

        "round-trips every clarification kind, preserving the payload fields" {
            val c = codec()
            listOf("entity_choice", "intent_choice", "missing_arg", "param_fill").forEach { kind ->
                val payload =
                    ResumePayload(
                        threadId = "s1",
                        turnId = "t1",
                        kind = kind,
                        userText = "kolik za DF?",
                        pickedPlanJson = """{"source":"PATTERN"}""",
                        options =
                            listOf(
                                ResumeOption(
                                    id = "opt1",
                                    display = "Kaufland",
                                    entityTypeRef = "customer",
                                    resolvedId = "C-42",
                                ),
                            ),
                        clarificationSpan = ClarificationSpan(0, 3, "DF"),
                        resolverResumeToken = "resv-abc",
                    )
                val decoded = c.decode(c.encode(payload))
                decoded.kind shouldBe kind
                decoded.userText shouldBe "kolik za DF?"
                decoded.pickedPlanJson shouldBe """{"source":"PATTERN"}"""
                decoded.options.single().resolvedId shouldBe "C-42"
                decoded.options.single().entityTypeRef shouldBe "customer"
                decoded.clarificationSpan?.coveredText shouldBe "DF"
                decoded.resolverResumeToken shouldBe "resv-abc"
            }
        }

        "a tampered payload is rejected (HMAC integrity)" {
            val c = codec()
            val token = c.encode(ResumePayload("s1", "t1", "param_fill", "q"))
            val (payloadB64, sig) = token.split(".")
            // Flip a character in the payload — the signature no longer matches.
            val tampered = payloadB64.dropLast(1) + (if (payloadB64.last() == 'A') 'B' else 'A') + "." + sig
            shouldThrow<ResumeTokenException> { c.decode(tampered) }
        }

        "a wrong-secret verifier rejects the token" {
            val token = codec(secret = "secret-A").encode(ResumePayload("s1", "t1", "param_fill", "q"))
            shouldThrow<ResumeTokenException> { codec(secret = "secret-B").decode(token) }
        }

        "an expired token is rejected" {
            // Minted at t=1_000_000; verified at t=1_000_400 with a 300s TTL → expired.
            val token = codec(nowEpoch = 1_000_000).encode(ResumePayload("s1", "t1", "param_fill", "q"))
            shouldThrow<ResumeTokenException> { codec(ttl = 300, nowEpoch = 1_000_400).decode(token) }
        }

        "a malformed token (not two parts) is rejected" {
            shouldThrow<ResumeTokenException> { codec().decode("not-a-token") }
        }
    })
