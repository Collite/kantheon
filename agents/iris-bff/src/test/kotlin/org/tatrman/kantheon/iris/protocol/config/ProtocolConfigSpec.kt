package org.tatrman.kantheon.iris.protocol.config

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.iris.protocol.sections.SectionRegistry
import org.tatrman.kantheon.protocol.v1.Verbosity

/**
 * `iris.protocol` loading (contracts §7). Two properties matter beyond mere
 * parsing: **receipts cannot be configured off** (PT-13), and **bad config never
 * fails boot** — `/protocol` is a debug surface, so a typo in its block must not
 * stop iris-bff from serving turns.
 */
class ProtocolConfigSpec :
    StringSpec({

        fun cfg(hocon: String) = ProtocolConfig.from(ConfigFactory.parseString(hocon))

        "loads contracts §7 HOCON into typed ProtocolConfig" {
            // The shipped application.conf block must load as written — this is the
            // real file, not a paraphrase, so drift between code and config fails here.
            val c = ProtocolConfig.from(ConfigFactory.load("application.conf"))

            c.defaultProfile shouldBe "default"
            val p = c.profile()
            p.verbosityFor("protocol.section.header") shouldBe Verbosity.VERBOSITY_FULL
            p.verbosityFor("protocol.section.llm-calls") shouldBe Verbosity.VERBOSITY_SUMMARY
            p.verbosityFor("protocol.section.service-logs") shouldBe Verbosity.VERBOSITY_SUMMARY
            p.llmUserContent shouldBe Verbosity.VERBOSITY_FULL
            p.llmSystemContent shouldBe Verbosity.VERBOSITY_SUMMARY

            c.caps.serviceLogsLines shouldBe 200
            c.caps.llmMessageChars shouldBe 4_000
            c.caps.sqlChars shouldBe 20_000
            c.sessionSplitThreshold shouldBe 12
            c.sources.translateExplainEnabled shouldBe true
        }

        "missing keys fall back to defaults" {
            // No block at all.
            ProtocolConfig.from(ConfigFactory.empty()) shouldBe ProtocolConfig()

            // Block present but empty.
            val sparse = cfg("iris.protocol {}")
            sparse.sessionSplitThreshold shouldBe 12
            sparse.caps shouldBe ProtocolCaps()

            // A section the profile never mentions resolves to the default verbosity,
            // so adding a section to the registry does not silently switch it off in
            // every deployed profile.
            val partial = cfg("""iris.protocol { profiles { default { sections { sql = off } } } }""")
            partial.profile().verbosityFor("protocol.section.sql") shouldBe Verbosity.VERBOSITY_OFF
            partial.profile().verbosityFor("protocol.section.header") shouldBe ProtocolProfile.DEFAULT_VERBOSITY
        }

        "verbosity resolution: profile value per key, off|summary|full" {
            val c =
                cfg(
                    """
                    iris.protocol { profiles { operator { sections {
                      header = full, resolution = summary, llm-calls = off, sql = FULL
                    } } } }
                    """.trimIndent(),
                )
            val p = c.profile("operator")

            p.verbosityFor("protocol.section.header") shouldBe Verbosity.VERBOSITY_FULL
            p.verbosityFor("protocol.section.resolution") shouldBe Verbosity.VERBOSITY_SUMMARY
            p.verbosityFor("protocol.section.llm-calls") shouldBe Verbosity.VERBOSITY_OFF
            // Case-insensitive: an operator writing FULL meant full.
            p.verbosityFor("protocol.section.sql") shouldBe Verbosity.VERBOSITY_FULL

            // An unknown profile name falls back to the default profile rather than
            // erroring — the caller never chooses this, so a bad server-side name
            // must degrade, not 500 a user's request.
            c.profile("no-such-profile").name shouldBe c.profile(c.defaultProfile).name
        }

        "attempt to configure protocol.section.receipts is rejected/ignored (PT-13)" {
            val c = cfg("""iris.protocol { profiles { default { sections { receipts = off, header = off } } } }""")
            val p = c.profile()

            // The sibling key took effect, so the block WAS parsed...
            p.verbosityFor("protocol.section.header") shouldBe Verbosity.VERBOSITY_OFF
            // ...but receipts is immune, at the resolver rather than only at the parser:
            // there is no code path by which any profile can switch them off.
            p.verbosityFor(SectionRegistry.RECEIPTS) shouldBe Verbosity.VERBOSITY_FULL
            p.sections.containsKey(SectionRegistry.RECEIPTS) shouldBe false

            // Even a profile constructed in code cannot do it.
            ProtocolProfile(sections = mapOf(SectionRegistry.RECEIPTS to Verbosity.VERBOSITY_OFF))
                .verbosityFor(SectionRegistry.RECEIPTS) shouldBe Verbosity.VERBOSITY_FULL
        }

        "caps + session-split-threshold + sources block parsed" {
            val c =
                cfg(
                    """
                    iris.protocol {
                      caps { service-logs-lines = 50, llm-message-chars = 1000, sql-chars = 999 }
                      session-split-threshold = 3
                      sources {
                        gateway-base-url = "http://gw:8080"
                        loki-base-url = "http://loki:3100"
                        tempo-base-url = "http://tempo:3200"
                        translate-explain { enabled = false }
                      }
                    }
                    """.trimIndent(),
                )

            c.caps shouldBe ProtocolCaps(serviceLogsLines = 50, llmMessageChars = 1_000, sqlChars = 999)
            c.sessionSplitThreshold shouldBe 3
            c.sources shouldBe
                SourceConfig(
                    gatewayBaseUrl = "http://gw:8080",
                    lokiBaseUrl = "http://loki:3100",
                    tempoBaseUrl = "http://tempo:3200",
                    translateExplainEnabled = false,
                )
        }

        "malformed config degrades to defaults instead of failing boot" {
            // A verbosity that is not a verbosity, and a section key that does not
            // exist. Both are operator typos; neither may take iris-bff down, and
            // neither may silently become a value nobody wrote.
            val c =
                cfg(
                    """
                    iris.protocol { profiles { default { sections { header = loud, nonsense = full } } } }
                    """.trimIndent(),
                )

            c.profile().verbosityFor("protocol.section.header") shouldBe ProtocolProfile.DEFAULT_VERBOSITY
            c.profile().sections.containsKey("protocol.section.nonsense") shouldBe false

            // Wrong TYPE for a whole block (a string where an object belongs) —
            // parsing throws inside, and the loader answers with shipped defaults.
            cfg("""iris.protocol { caps = "nope" }""") shouldBe ProtocolConfig()
        }
    })
