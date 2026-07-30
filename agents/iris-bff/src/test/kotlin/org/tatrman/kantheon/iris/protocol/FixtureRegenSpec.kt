package org.tatrman.kantheon.iris.protocol

import io.kotest.core.spec.style.StringSpec
import org.tatrman.kantheon.iris.protocol.model.DocumentBuilder
import org.tatrman.kantheon.iris.protocol.redact.RedactionChain
import org.tatrman.kantheon.iris.protocol.render.MarkdownRenderer

/**
 * Regenerates every case's `expected-model.json` + `expected.md`.
 *
 * NOT part of the suite — disabled unless `PROTOCOL_FIXTURES_REGEN=true` is in
 * the environment. (An env var, not a system property: Gradle's Test task
 * inherits the environment but not `-D` flags from the launching JVM, so a
 * property would have silently never fired.)
 *
 * Golden files are reviewed by a human at authoring time and then frozen; this
 * exists so a deliberate contract change can be re-baselined in one step instead
 * of by hand-editing six markdown documents, which is how golden corpora rot.
 */
class FixtureRegenSpec :
    StringSpec({
        "regenerate golden expectations".config(
            enabled = System.getenv("PROTOCOL_FIXTURES_REGEN") == "true",
        ) {
            FixtureLoader.CASES.forEach { case ->
                val req = FixtureLoader.request(case)
                val profile = req.config.profile(req.profileName)
                val doc = RedactionChain.standard().redact(DocumentBuilder.build(req), profile)
                FixtureLoader.writeExpectedModel(case, doc)
                FixtureLoader.writeExpectedMarkdown(
                    case,
                    MarkdownRenderer(req.config.sessionSplitThreshold).render(doc),
                )
            }
        }
    })
