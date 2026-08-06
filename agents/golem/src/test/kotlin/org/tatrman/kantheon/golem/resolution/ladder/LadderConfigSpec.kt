package org.tatrman.kantheon.golem.resolution.ladder

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.resolver.v1.GapKind
import java.security.MessageDigest

/**
 * RV-P5.2 T2 — `golem-ladder/v1`: kantheon's shipped default, the OPEN Golem's shipped
 * default read through the same loader, and the rejection catalogue.
 *
 * The rejection catalogue is golem-py's `test_ladder_config.py`, case for case. That is the
 * point of "one schema, two loaders": if the Kotlin loader accepted something the Python one
 * refuses, an estate could hand the same file to both Golems and get two behaviours.
 */
fun openLadderYaml(): String =
    requireNotNull(LadderConfigSpec::class.java.getResourceAsStream("/ladder/golem-ladder-open.yaml")) {
        "missing vendored fixture ladder/golem-ladder-open.yaml"
    }.bufferedReader().use { it.readText() }

private const val OPEN_SHA256 = "a4f049c32ef5eca2efb4090de84f446a05a9ee2607cae8f77a38d508c1bf360a"

private fun sha256(s: String) =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/** Kantheon's own default with one key edited — the rejection catalogue's fixture. */
private fun edited(vararg replacements: Pair<String, String>): String {
    var yaml =
        requireNotNull(LadderConfigSpec::class.java.getResourceAsStream("/golem-ladder.yaml"))
            .bufferedReader()
            .use { it.readText() }
    replacements.forEach { (from, to) ->
        require(yaml.contains(from)) { "fixture anchor not found: $from" }
        yaml = yaml.replace(from, to)
    }
    return yaml
}

class LadderConfigSpec :
    StringSpec({

        // ------------------------------------------------- kantheon's shipped default

        "kantheon ships the full contracts §3 table, NOT the open Golem's zero-rung floor" {
            val cfg = LadderConfig.loadDefault()

            cfg.schemaId shouldBe LADDER_SCHEMA_ID
            cfg.rungs.keys.sorted() shouldContainExactly listOf("capable", "emulated", "local", "lookup")
            // ⚑ The ruling: RV-27's zero-rung default is stated for *the open Golem*; this is
            // the internal-full one. Recorded on the task list.
            cfg.gapPolicy(GapKind.GAP_KIND_G1_UNBOUND).rungs shouldContainExactly
                listOf("lookup", "local", "capable")
            cfg.gapPolicy(GapKind.GAP_KIND_G5_NLP_DARK).rungs shouldContainExactly listOf("emulated")
        }

        "the Q-14 budgets are the ruled ones" {
            val cfg = LadderConfig.loadDefault()

            cfg.timeoutMs("lookup") shouldBe 250
            cfg.timeoutMs("local") shouldBe 3000
            cfg.timeoutMs("capable") shouldBe 10000
            cfg.timeoutMs("emulated") shouldBe 15000

            val quick = cfg.profile("CHAT_QUICK")
            Triple(quick.maxLlmInvocations, quick.ladderBudgetMs, quick.hitlRounds) shouldBe Triple(2, 5000, 1)
            val deep = cfg.profile("INVESTIGATION_DEEP")
            Triple(deep.maxLlmInvocations, deep.ladderBudgetMs, deep.hitlRounds) shouldBe Triple(6, 30000, 3)
            deep.allows("emulated") shouldBe true
            quick.allows("capable") shouldBe false
        }

        "both shipped profiles refuse rather than answer over a load-bearing gap" {
            // P4.1 T4's ruling adopted unchanged: a profile names its posture, default strict.
            val cfg = LadderConfig.loadDefault()
            cfg.terminalPosture("CHAT_QUICK") shouldBe TerminalPosture.REFUSAL_WITH_GAPS
            cfg.terminalPosture("INVESTIGATION_DEEP") shouldBe TerminalPosture.REFUSAL_WITH_GAPS
        }

        // ----------------------------------------- one schema, two loaders (the point)

        "the OPEN Golem's shipped file loads through this loader, zero-rung and all" {
            val open = LadderConfig.parse(openLadderYaml(), "golem-ladder-open.yaml")

            open.schemaId shouldBe LADDER_SCHEMA_ID
            open.rungs.keys.sorted() shouldContainExactly listOf("capable", "emulated", "local", "lookup")
            // RV-27, asserted rather than asserted-in-a-comment: every rung DEFINED, no policy
            // row admitting one. That combination is what makes enabling a rung an edit.
            open.policy.forEach { (kind, policy) ->
                withClue(kind.name) { policy.rungs shouldBe emptyList() }
            }
            open.eligibleRungs(listOf(GapKind.GAP_KIND_G1_UNBOUND), "CHAT_QUICK") shouldBe emptyList()
        }

        "zeroing the rungs does not zero the ASKS — an out-of-the-box estate still asks once" {
            val open = LadderConfig.parse(openLadderYaml())

            open.gapPolicy(GapKind.GAP_KIND_G1_UNBOUND).ask shouldBe AskPolicy.ESCALATE_THEN_ASK
            open.gapPolicy(GapKind.GAP_KIND_G5_NLP_DARK).ask shouldBe AskPolicy.DEGRADE_BANNER
            open.profile("CHAT_QUICK").hitlRounds shouldBe 1
        }

        "the vendored open config still hashes to what PROVENANCE.md records" {
            sha256(openLadderYaml()) shouldBe OPEN_SHA256
        }

        "the cross-repo drift check runs when a sibling checkout is available, and says so when not" {
            val root = System.getenv("TATRMAN_SERVER_DIR")
            if (root == null) {
                println(
                    "SKIPPED ladder drift check: set TATRMAN_SERVER_DIR to diff " +
                        "src/test/resources/ladder/golem-ladder-open.yaml against " +
                        "services/golem-py/config/golem-ladder.yaml. The sha256 test above proves " +
                        "the file has not changed HERE, not that it still matches THERE.",
                )
            } else {
                val upstream =
                    java.nio.file.Path
                        .of(root, "services/golem-py/config/golem-ladder.yaml")
                if (java.nio.file.Files
                        .exists(upstream)
                ) {
                    sha256(
                        java.nio.file.Files
                            .readString(upstream),
                    ) shouldBe OPEN_SHA256
                } else {
                    println("SKIPPED: no such file at $upstream (upstream moved it?)")
                }
            }
        }

        // ------------------------------------------------------ the rejection catalogue

        "an unknown rung is refused — the vocabulary is CLOSED at four (RV-33)" {
            val e =
                shouldThrow<LadderConfigException> {
                    LadderConfig.parse(
                        edited(
                            "  emulated: { last_resort: true }" to
                                "  emulated: { last_resort: true }\n  frontier: { timeout_ms: 1000 }",
                        ),
                    )
                }
            e.message shouldContain "unknown rung"
        }

        "a negative budget is refused" {
            shouldThrow<LadderConfigException> {
                LadderConfig.parse(edited("max_llm_invocations: 2" to "max_llm_invocations: -1"))
            }.message shouldContain "negative"
        }

        "a non-positive timeout is refused" {
            shouldThrow<LadderConfigException> {
                LadderConfig.parse(edited("lookup: 250" to "lookup: 0"))
            }.message shouldContain "must be > 0"
        }

        "an unknown gap kind is refused — dead config reads as coverage" {
            shouldThrow<LadderConfigException> {
                LadderConfig.parse(
                    edited(
                        "  G6_INCOHERENT:" to
                            "  G7_VIBES:      { rungs: [], ask: ask-if-load-bearing }\n  G6_INCOHERENT:",
                    ),
                )
            }.message shouldContain "unknown gap kind"
        }

        "a policy naming an undefined rung is refused" {
            shouldThrow<LadderConfigException> {
                LadderConfig.parse(
                    edited(
                        "  capable: { model_class: capable, timeout_ms: 10000, temperature: 0 }\n" to "",
                    ),
                )
            }.message shouldContain "not defined"
        }

        "a profile naming an undefined rung is refused" {
            shouldThrow<LadderConfigException> {
                LadderConfig.parse(edited("rungs_allowed: [lookup, local]" to "rungs_allowed: [lookup, frontier]"))
            }.message shouldContain "not defined"
        }

        "a profile naming an undefined terminal posture is refused" {
            shouldThrow<LadderConfigException> {
                LadderConfig.parse(
                    edited(
                        "    terminal: strict\n  INVESTIGATION_DEEP:" to "    terminal: yolo\n  INVESTIGATION_DEEP:",
                    ),
                )
            }.message shouldContain "terminal posture"
        }

        "an unknown ask policy is refused" {
            shouldThrow<LadderConfigException> {
                LadderConfig.parse(edited("ask: escalate-then-ask }\n  G2_AMBIGUOUS" to "ask: guess }\n  G2_AMBIGUOUS"))
            }.message shouldContain "unknown ask policy"
        }

        "a wrong schema id is refused" {
            shouldThrow<LadderConfigException> {
                LadderConfig.parse(edited("schema: golem-ladder/v1" to "schema: golem-ladder/v2"))
            }.message shouldContain "unknown schema id"
        }

        "an unknown top-level key is refused — a misspelled `profiles:` would be silent" {
            shouldThrow<LadderConfigException> {
                LadderConfig.parse(edited("profiles:" to "profles:"))
            }.message shouldContain "unknown top-level key"
        }

        "an unknown key INSIDE a profile is refused too" {
            shouldThrow<LadderConfigException> {
                LadderConfig.parse(edited("    hitl_rounds: 1" to "    hitl_rounds: 1\n    max_asks: 4"))
            }.message shouldContain "unknown key"
        }
    })
