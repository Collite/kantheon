package org.tatrman.kantheon.iris.protocol.assemble

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.kantheon.iris.protocol.FixtureLoader
import org.tatrman.kantheon.iris.protocol.config.ProtocolCaps
import org.tatrman.kantheon.iris.protocol.config.ProtocolProfile
import org.tatrman.kantheon.iris.protocol.model.DocumentBuilder
import org.tatrman.kantheon.iris.protocol.redact.RedactionChain
import org.tatrman.kantheon.iris.protocol.render.MarkdownRenderer
import org.tatrman.kantheon.iris.protocol.sections.LlmCallsSectionBuilder
import org.tatrman.kantheon.iris.protocol.sections.SectionInput
import org.tatrman.kantheon.iris.protocol.sections.SqlSectionBuilder
import org.tatrman.kantheon.protocol.v1.Section
import org.tatrman.kantheon.protocol.v1.Verbosity

/**
 * Caps (PT-10) and the session split (PT-10 γ), end to end.
 *
 * The property under test throughout: **a document that dropped something says
 * so.** Silent elision is the failure mode that matters here — a protocol which
 * quietly shows 1 of 340 log lines, or half a prompt, is worse than one that
 * shows nothing, because the reader has no way to know they are looking at a
 * fragment.
 */
class CapsAndSplitSpec :
    StringSpec({

        fun render(case: String): String {
            val req = FixtureLoader.request(case)
            val profile = req.config.profile(req.profileName)
            val doc = RedactionChain.standard().redact(DocumentBuilder.build(req), profile)
            return MarkdownRenderer(req.config.sessionSplitThreshold).render(doc)
        }

        fun input(
            case: String = "H1-full",
            caps: ProtocolCaps = FixtureLoader.config(case).caps,
            profile: ProtocolProfile = FixtureLoader.config(case).profile(),
        ) = SectionInput(
            record = FixtureLoader.records(case).first(),
            sources = FixtureLoader.sources(case),
            turn = FixtureLoader.turns(case).first(),
            profile = profile,
            caps = caps,
        )

        "service-logs over the cap -> truncated=true, dropped_by_cap set, marker + receipt detail" {
            val req = FixtureLoader.request("truncation")
            val doc = DocumentBuilder.build(req)
            val section =
                doc.turnsList
                    .single()
                    .sectionsList
                    .single { it.key == "protocol.section.service-logs" }

            section.truncated shouldBe true
            // 40 already dropped upstream + 2 the cap of 1 cut here.
            section.serviceLogs.groupsList.sumOf { it.droppedByCap } shouldBe 42

            val md = render("truncation")
            md shouldContain "_…truncated — 42 more lines; source: service-logs_"
            md shouldContain "_42 more line(s) dropped by cap._"
        }

        "llm message over caps.llm-message-chars -> body truncated, flagged, marker emitted" {
            val cap = 100
            val long = "p".repeat(cap * 5)
            val sources =
                FixtureLoader.sources("H1-full").let { s ->
                    s.copy(gateway = s.gateway.copy(items = s.gateway.items.map { it.copy(promptText = long) }))
                }
            val i =
                input(caps = ProtocolCaps(llmMessageChars = cap))
                    .copy(
                        sources = sources,
                        profile =
                            ProtocolProfile(
                                sections =
                                    mapOf(
                                        LlmCallsSectionBuilder.KEY to Verbosity.VERBOSITY_FULL,
                                    ),
                            ),
                    )

            val section = LlmCallsSectionBuilder.build(i)
            val user =
                section.llmCalls.callsList
                    .first()
                    .messagesList
                    .first { it.role == "user" }

            user.content.length shouldBe cap
            // Flagged, so the reader is never shown a fragment as if it were whole.
            user.contentRedacted shouldBe true
            section.truncated shouldBe true
        }

        "sql over caps.sql-chars -> truncated with a marker naming the cap that bit" {
            val cap = 40
            val i = input(caps = ProtocolCaps(sqlChars = cap))
            val section = SqlSectionBuilder.build(i)

            section.truncated shouldBe true
            section.sql.sql.length shouldBe cap
        }

        "the marker never claims a line count it does not have (Amendment A-4)" {
            // Logs are truncated by LINES and report N. SQL and prompt bodies are
            // truncated by CHARACTERS, and the document does not retain the original
            // length — so printing "0 more lines" there would state something false
            // about the very thing the marker exists to be honest about.
            val doc =
                DocumentBuilder
                    .build(FixtureLoader.request("H1-full"))
                    .toBuilder()
                    .also { d ->
                        d.turnsBuilderList.first().sectionsBuilderList.forEach { s ->
                            if (s.payloadCase == Section.PayloadCase.SQL) s.truncated = true
                        }
                    }.build()

            val md = MarkdownRenderer().render(doc)
            md shouldContain "_…truncated by the sql-chars cap; source: sql_"
            md shouldNotContain "truncated — 0 more lines"
        }

        "session scope over the split threshold -> index + per-turn chapters, ONE document" {
            val md = render("session-split")

            md shouldContain "## Contents"
            md shouldContain "](#turn-1)"
            md shouldContain "<a id=\"turn-13\"></a>"
            // PT-10 γ: a genuine multi-document split is reserved, not implemented.
            Regex("^# ", RegexOption.MULTILINE).findAll(md).count() shouldBe 1
            // Every turn is a chapter.
            Regex("^## Turn ", RegexOption.MULTILINE).findAll(md).count() shouldBe 13
        }

        "under the threshold there is no index and no anchors" {
            val md = render("H1-full")
            md shouldNotContain "## Contents"
            md shouldNotContain "<a id=\"turn-"
        }

        "truncation fixture end-to-end: model equals expected-model.json, md equals expected.md" {
            val req = FixtureLoader.request("truncation")
            val profile = req.config.profile(req.profileName)
            val doc = RedactionChain.standard().redact(DocumentBuilder.build(req), profile)

            doc shouldBe FixtureLoader.expectedModel("truncation")
            MarkdownRenderer(req.config.sessionSplitThreshold).render(doc) shouldBe
                FixtureLoader.expectedMarkdown("truncation")
        }

        "session-split fixture end-to-end: model and md both match" {
            val req = FixtureLoader.request("session-split")
            val profile = req.config.profile(req.profileName)
            val doc = RedactionChain.standard().redact(DocumentBuilder.build(req), profile)

            doc shouldBe FixtureLoader.expectedModel("session-split")
            MarkdownRenderer(req.config.sessionSplitThreshold).render(doc) shouldBe
                FixtureLoader.expectedMarkdown("session-split")
        }
    })
