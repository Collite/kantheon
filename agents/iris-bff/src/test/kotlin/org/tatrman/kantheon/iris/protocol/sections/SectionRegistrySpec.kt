package org.tatrman.kantheon.iris.protocol.sections

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

/**
 * The registry is a contract, not an implementation detail (contracts §2): its
 * keys are HOCON configuration keys, and its order is the render order. Both are
 * pinned here so a rename or a reorder shows up as a failing test rather than as
 * an operator's profile silently ceasing to apply.
 */
class SectionRegistrySpec :
    StringSpec({

        "registry lists exactly the contracts §2 keys in spine order" {
            SectionRegistry.turnSpine shouldContainExactly
                listOf(
                    "protocol.section.header",
                    "protocol.section.resolution",
                    "protocol.section.llm-calls",
                    "protocol.section.query",
                    "protocol.section.plan",
                    "protocol.section.sql",
                    "protocol.section.security",
                    "protocol.section.execution",
                    "protocol.section.service-logs",
                    "protocol.section.errors",
                )
        }

        "participants and receipts are both document-level — in NEITHER turn spine" {
            // A-5 / review-079 R5. Participants used to ride the session-scope spine,
            // which rendered the identical block under every turn heading (13 times in
            // the 13-turn fixture). It is one fact about the conversation, so the
            // document carries it once and the renderer places it.
            SectionRegistry.spineFor(sessionScope = false) shouldContainExactly SectionRegistry.turnSpine
            SectionRegistry.spineFor(sessionScope = true) shouldContainExactly SectionRegistry.turnSpine
            SectionRegistry.spineFor(sessionScope = true) shouldNotContain SectionRegistry.PARTICIPANTS

            // Receipts is in NEITHER spine: the renderer appends it, so no builder
            // and no profile can position it, drop it, or reorder it.
            SectionRegistry.spineFor(sessionScope = false) shouldNotContain SectionRegistry.RECEIPTS
            SectionRegistry.spineFor(sessionScope = true) shouldNotContain SectionRegistry.RECEIPTS

            // ...and it is not configurable (PT-13).
            SectionRegistry.configurableKeys shouldNotContain SectionRegistry.RECEIPTS
            SectionRegistry.configurableKeys shouldContainExactly
                SectionRegistry.turnSpine + SectionRegistry.PARTICIPANTS
        }

        "registry keys are stable strings (protocol.section.*)" {
            (SectionRegistry.turnSpine + SectionRegistry.PARTICIPANTS + SectionRegistry.RECEIPTS).forEach {
                it shouldStartWith "protocol.section."
            }

            SectionRegistry.shortName("protocol.section.llm-calls") shouldBe "llm-calls"
            SectionRegistry.keyForShortName("llm-calls") shouldBe "protocol.section.llm-calls"
            SectionRegistry.keyForShortName("receipts") shouldBe SectionRegistry.RECEIPTS

            // An unknown short name resolves to null so the config loader can warn
            // about a typo instead of inventing a key nothing will ever render.
            SectionRegistry.keyForShortName("llmCalls") shouldBe null
            SectionRegistry.keyForShortName("nonsense") shouldBe null
        }
    })
