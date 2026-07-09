package org.tatrman.kantheon.hebe.tools.dispatch

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Tool posture (P2 Stage 2.4 T1/T2). The gate is pure logic over the resolved
 * posture + enable/disable lists; the dispatcher-level refusal-receipt path is
 * covered separately (PostureDispatchReceiptTest).
 */
class PostureSpec :
    StringSpec({

        "restricted blocks the dangerous families" {
            val gate = PostureGate(ToolPosture.RESTRICTED)
            gate.decide("shell").shouldBeInstanceOf<PostureDecision.Deny>()
            gate.decide("kubectl_apply").shouldBeInstanceOf<PostureDecision.Deny>()
            gate.decide("git").shouldBeInstanceOf<PostureDecision.Deny>()
            gate.decide("file_read").shouldBeInstanceOf<PostureDecision.Deny>()
            gate.decide("file_write").shouldBeInstanceOf<PostureDecision.Deny>()
        }

        "restricted allows the safe families" {
            val gate = PostureGate(ToolPosture.RESTRICTED)
            gate.decide("memory_search") shouldBe PostureDecision.Allow
            gate.decide("http_get") shouldBe PostureDecision.Allow
            gate.decide("web_search") shouldBe PostureDecision.Allow
            gate.decide("schedule_create") shouldBe PostureDecision.Allow
            gate.decide("kantheon_ask") shouldBe PostureDecision.Allow
            // an unrecognised tool is OTHER → allowed (only the explicit four are off)
            gate.decide("summarise") shouldBe PostureDecision.Allow
        }

        "enable opt-in flips a dangerous family to allowed under restricted" {
            val gate = PostureGate(ToolPosture.RESTRICTED, enable = setOf("git"))
            gate.decide("git") shouldBe PostureDecision.Allow
            gate.decide("git_log") shouldBe PostureDecision.Allow
            // other dangerous families remain blocked
            gate.decide("shell").shouldBeInstanceOf<PostureDecision.Deny>()
        }

        "disable removes a family even under full posture" {
            val gate = PostureGate(ToolPosture.FULL, disable = setOf("shell"))
            gate.decide("shell").shouldBeInstanceOf<PostureDecision.Deny>()
            gate.decide("git") shouldBe PostureDecision.Allow
        }

        "full posture allows everything (today's behaviour)" {
            val gate = PostureGate(ToolPosture.FULL)
            gate.decide("shell") shouldBe PostureDecision.Allow
            gate.decide("git") shouldBe PostureDecision.Allow
            gate.decide("file_write") shouldBe PostureDecision.Allow
        }

        "the denied decision carries the family" {
            val d = PostureGate(ToolPosture.RESTRICTED).decide("kubectl_get")
            (d as PostureDecision.Deny).family shouldBe ToolFamily.KUBECTL
        }

        "family classification by name" {
            ToolFamily.of("shell") shouldBe ToolFamily.SHELL
            ToolFamily.of("git_status") shouldBe ToolFamily.GIT
            ToolFamily.of("kubectl_apply") shouldBe ToolFamily.KUBECTL
            ToolFamily.of("file_read") shouldBe ToolFamily.FILESYSTEM
            ToolFamily.of("memory_recall") shouldBe ToolFamily.MEMORY
            ToolFamily.of("web_search") shouldBe ToolFamily.WEB_SEARCH
            ToolFamily.of("schedule_add") shouldBe ToolFamily.SCHEDULING
            ToolFamily.of("kantheon_question") shouldBe ToolFamily.KANTHEON
            ToolFamily.of("mystery_tool") shouldBe ToolFamily.OTHER
        }
    })
