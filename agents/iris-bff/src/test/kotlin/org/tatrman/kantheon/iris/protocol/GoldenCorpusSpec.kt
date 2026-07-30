package org.tatrman.kantheon.iris.protocol

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.iris.protocol.model.DocumentBuilder
import org.tatrman.kantheon.iris.protocol.redact.RedactionChain
import org.tatrman.kantheon.iris.protocol.render.MarkdownRenderer

/**
 * The golden gate (PT-22): every case's model and markdown must match its
 * frozen expectation exactly.
 *
 * Expectations were **generated once by `FixtureRegenSpec`, then read and
 * reviewed line by line before being committed** — the standard golden-file
 * practice, and stated plainly here rather than implied. That review is the part
 * that carries the weight: the first generated pass was wrong in three ways (an
 * empty F2 capture reducing the hero case to degraded, a redundant `_no content_`
 * under every degraded heading, and an empty code fence for a service whose lines
 * were all filtered out), and all three were fixed in the source, not blessed
 * into the fixtures.
 */
class GoldenCorpusSpec :
    StringSpec({

        FixtureLoader.CASES.forEach { case ->
            "$case: assembled document equals expected-model.json" {
                val req = FixtureLoader.request(case)
                val profile = req.config.profile(req.profileName)
                val doc = RedactionChain.standard().redact(DocumentBuilder.build(req), profile)

                withClue(case) { doc shouldBe FixtureLoader.expectedModel(case) }
            }

            "$case: rendered markdown is byte-equal to expected.md" {
                val req = FixtureLoader.request(case)
                val profile = req.config.profile(req.profileName)
                val doc = RedactionChain.standard().redact(DocumentBuilder.build(req), profile)
                val md = MarkdownRenderer(req.config.sessionSplitThreshold).render(doc)

                withClue(case) { md shouldBe FixtureLoader.expectedMarkdown(case) }
            }
        }
    })
