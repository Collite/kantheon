package org.tatrman.kantheon.iris.protocol.redact

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.kantheon.iris.protocol.config.ProtocolProfile
import org.tatrman.kantheon.protocol.v1.ErrorItem
import org.tatrman.kantheon.protocol.v1.ErrorsSection
import org.tatrman.kantheon.protocol.v1.LlmCall
import org.tatrman.kantheon.protocol.v1.LlmCallsSection
import org.tatrman.kantheon.protocol.v1.LlmMessage
import org.tatrman.kantheon.protocol.v1.LogLine
import org.tatrman.kantheon.protocol.v1.ProtocolDocument
import org.tatrman.kantheon.protocol.v1.ProtocolTurn
import org.tatrman.kantheon.protocol.v1.Section
import org.tatrman.kantheon.protocol.v1.SectionStatus
import org.tatrman.kantheon.protocol.v1.ServiceLogGroup
import org.tatrman.kantheon.protocol.v1.ServiceLogsSection
import org.tatrman.kantheon.protocol.v1.SqlSection
import org.tatrman.kantheon.protocol.v1.Verbosity

/**
 * Adversarial redaction tests (contracts §8). These are the cases where being
 * wrong is expensive rather than merely untidy: a protocol is designed to be
 * read, exported and pasted into a ticket, so anything that survives redaction
 * should be assumed to travel.
 *
 * The bias throughout is that a false positive costs one obscured string and a
 * false negative leaks a credential.
 */
class RedactorSpec :
    StringSpec({

        val turnId = "3f2b1a00-0000-4000-8000-000000000001"

        fun doc(vararg sections: Section): ProtocolDocument =
            ProtocolDocument
                .newBuilder()
                .addTurns(
                    ProtocolTurn
                        .newBuilder()
                        .setTurnId(turnId)
                        .setSeq(1)
                        .addAllSections(sections.toList()),
                ).build()

        fun logSection(vararg bodies: String): Section =
            Section
                .newBuilder()
                .setKey("protocol.section.service-logs")
                .setStatus(SectionStatus.SECTION_OK)
                .setAppliedVerbosity(Verbosity.VERBOSITY_FULL)
                .setServiceLogs(
                    ServiceLogsSection.newBuilder().addGroups(
                        ServiceLogGroup
                            .newBuilder()
                            .setServiceName("golem")
                            .addAllLines(
                                bodies.map {
                                    LogLine
                                        .newBuilder()
                                        .setLevel("INFO")
                                        .setBody(it)
                                        .build()
                                },
                            ),
                    ),
                ).build()

        fun logBodies(d: ProtocolDocument): List<String> =
            d.turnsList
                .flatMap { it.sectionsList }
                .filter { it.payloadCase == Section.PayloadCase.SERVICE_LOGS }
                .flatMap { it.serviceLogs.groupsList }
                .flatMap { g -> g.linesList.map { it.body } }

        "bearer token in a service-log line is scrubbed by FloorRedactor" {
            val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJtYXlhIn0.c2lnbmF0dXJlLWJ5dGVz"
            val d =
                FloorRedactor.redact(
                    doc(
                        logSection(
                            "calling themis with Authorization: Bearer $jwt",
                            "raw token $jwt in a message",
                        ),
                    ),
                    ProtocolProfile(),
                )

            logBodies(d).forEach {
                it shouldNotContain jwt
                it shouldContain FloorRedactor.MASK
            }
        }

        "postgres connection string in an error message is scrubbed by FloorRedactor" {
            val conn = "postgresql://iris:s3cr3t@pg-hartland.data.svc:5432/iris?sslmode=require"
            val section =
                Section
                    .newBuilder()
                    .setKey("protocol.section.errors")
                    .setStatus(SectionStatus.SECTION_OK)
                    .setErrors(
                        ErrorsSection.newBuilder().addItems(
                            ErrorItem
                                .newBuilder()
                                .setSource("iris-bff")
                                .setCode("DB")
                                .setMessage("cannot reach $conn"),
                        ),
                    ).build()

            val message =
                FloorRedactor
                    .redact(doc(section), ProtocolProfile())
                    .turnsList
                    .first()
                    .sectionsList
                    .first()
                    .errors.itemsList
                    .first()
                    .message

            message shouldNotContain "s3cr3t"
            message shouldNotContain "postgresql://"
            message shouldContain FloorRedactor.MASK
        }

        "gateway row whose turn_ref mismatches the requested turn is DROPPED entirely (cross-user row drop)" {
            val mine =
                LlmCall
                    .newBuilder()
                    .setCallRef("gw-100")
                    .setTurnRef(turnId)
                    .setServedModel("opus")
            val theirs =
                LlmCall
                    .newBuilder()
                    .setCallRef("gw-999")
                    .setTurnRef("another-users-turn")
                    .addMessages(LlmMessage.newBuilder().setRole("user").setContent("another tenant's question"))
            val unattributed = LlmCall.newBuilder().setCallRef("gw-777").setTurnRef("")

            val section =
                Section
                    .newBuilder()
                    .setKey("protocol.section.llm-calls")
                    .setStatus(SectionStatus.SECTION_OK)
                    .setLlmCalls(
                        LlmCallsSection
                            .newBuilder()
                            .addCalls(mine)
                            .addCalls(theirs)
                            .addCalls(unattributed),
                    ).build()

            val calls =
                FloorRedactor
                    .redact(doc(section), ProtocolProfile())
                    .turnsList
                    .first()
                    .sectionsList
                    .first()
                    .llmCalls

            calls.callsList.map { it.callRef } shouldBe listOf("gw-100", "gw-777")
            // Dropped, and COUNTED — the reader sees a shortfall rather than a
            // document that silently pretends the call never happened.
            calls.unattributableCount shouldBe 1
            calls.toString() shouldNotContain "another tenant's question"
        }

        "SQL literal masking: on -> literals masked + literals_masked=true; off -> verbatim" {
            val sql = "SELECT * FROM p_and_l WHERE tenant = 'hartland' AND year = 2026"

            fun sqlSection(v: Verbosity) =
                Section
                    .newBuilder()
                    .setKey("protocol.section.sql")
                    .setStatus(SectionStatus.SECTION_OK)
                    .setAppliedVerbosity(v)
                    .setSql(SqlSection.newBuilder().setSql(sql))
                    .build()

            val masked =
                ConfigRedactor
                    .redact(doc(sqlSection(Verbosity.VERBOSITY_SUMMARY)), ProtocolProfile())
                    .turnsList
                    .first()
                    .sectionsList
                    .first()
                    .sql

            masked.sql shouldNotContain "hartland"
            masked.sql shouldNotContain "2026"
            masked.sql shouldContain "FROM p_and_l" // identifiers survive; only values go
            masked.literalsMasked shouldBe true

            val verbatim =
                ConfigRedactor
                    .redact(doc(sqlSection(Verbosity.VERBOSITY_FULL)), ProtocolProfile())
                    .turnsList
                    .first()
                    .sectionsList
                    .first()
                    .sql

            verbatim.sql shouldBe sql
            verbatim.literalsMasked shouldBe false
        }

        "llm message body at verbosity summary -> digest, content_redacted=true (prompt-body digesting)" {
            val long = "x".repeat(ConfigRedactor.DIGEST_CHARS * 3)
            val section =
                Section
                    .newBuilder()
                    .setKey("protocol.section.llm-calls")
                    .setStatus(SectionStatus.SECTION_OK)
                    .setLlmCalls(
                        LlmCallsSection.newBuilder().addCalls(
                            LlmCall
                                .newBuilder()
                                .setCallRef("gw-1")
                                .setTurnRef(turnId)
                                .addMessages(LlmMessage.newBuilder().setRole("system").setContent(long))
                                .addMessages(LlmMessage.newBuilder().setRole("user").setContent(long)),
                        ),
                    ).build()

            // Default profile: system = summary (digest), user = full (untouched).
            val call =
                ConfigRedactor
                    .redact(doc(section), ProtocolProfile())
                    .turnsList
                    .first()
                    .sectionsList
                    .first()
                    .llmCalls.callsList
                    .first()

            val system = call.messagesList.first { it.role == "system" }
            system.content.length shouldBe ConfigRedactor.DIGEST_CHARS + 1 // + ellipsis
            system.contentRedacted shouldBe true

            val user = call.messagesList.first { it.role == "user" }
            user.content shouldBe long
            user.contentRedacted shouldBe false

            // A profile that switches bodies off empties them and says so — an empty
            // body with content_redacted=false would read as "the model said nothing".
            val off =
                ConfigRedactor
                    .redact(doc(section), ProtocolProfile(llmUserContent = Verbosity.VERBOSITY_OFF))
                    .turnsList
                    .first()
                    .sectionsList
                    .first()
                    .llmCalls.callsList
                    .first()
                    .messagesList
                    .first { it.role == "user" }
            off.content shouldBe ""
            off.contentRedacted shouldBe true
        }

        "FloorRedactor always runs even when profile disables all config redaction" {
            val secret = "api_key=AKIAIOSFODNN7EXAMPLE"
            // The most permissive profile expressible: everything full, nothing digested.
            val permissive =
                ProtocolProfile(
                    sections = ProtocolProfile.DEFAULT_SECTIONS.mapValues { Verbosity.VERBOSITY_FULL },
                    llmUserContent = Verbosity.VERBOSITY_FULL,
                    llmSystemContent = Verbosity.VERBOSITY_FULL,
                )

            val viaChain = RedactionChain.standard().redact(doc(logSection("boot with $secret")), permissive)

            logBodies(viaChain).single() shouldNotContain "AKIAIOSFODNN7EXAMPLE"
            logBodies(viaChain).single() shouldContain FloorRedactor.MASK

            // ...and a custom redactor cannot get in front of the floor: the chain is
            // built floor-first by construction, so a custom stage only ever sees
            // already-scrubbed content and cannot restore what was removed.
            val nosy =
                ProtocolRedactor { d, _ ->
                    d
                        .toBuilder()
                        .also { b ->
                            b.turnsBuilderList.forEach { t ->
                                t.sectionsBuilderList.forEach { s ->
                                    if (s.payloadCase == Section.PayloadCase.SERVICE_LOGS) {
                                        s.serviceLogsBuilder.groupsBuilderList.forEach { g ->
                                            g.linesBuilderList.forEach { l ->
                                                l.body =
                                                    l.body + " [seen: " + l.body + "]"
                                            }
                                        }
                                    }
                                }
                            }
                        }.build()
                }

            val withCustom =
                RedactionChain
                    .standard(
                        listOf(nosy),
                    ).redact(doc(logSection("boot with $secret")), permissive)
            logBodies(withCustom).single() shouldNotContain "AKIAIOSFODNN7EXAMPLE"
        }

        "PEM private key material is scrubbed whole, not line by line" {
            val pem =
                """
                -----BEGIN RSA PRIVATE KEY-----
                MIIEowIBAAKCAQEAxyz123
                abcDEF456
                -----END RSA PRIVATE KEY-----
                """.trimIndent()

            val out = FloorRedactor.scrub("signing with:\n$pem\ndone")

            out shouldNotContain "MIIEowIBAAKCAQEAxyz123"
            out shouldNotContain "BEGIN RSA PRIVATE KEY"
            out shouldContain "done"
        }

        "ordinary prose is left alone — the floor must not make documents unreadable" {
            val prose = "The token bucket refilled after 3 seconds and the password policy page loaded."
            // `token` and `password` appear as words, not as key=value secrets.
            FloorRedactor.scrub(prose) shouldBe prose

            val sql = "SELECT secret_sauce FROM recipes WHERE id = 7"
            FloorRedactor.scrub(sql) shouldBe sql
        }

        "redaction is total: an empty document round-trips unchanged" {
            val empty = ProtocolDocument.getDefaultInstance()
            RedactionChain.standard().redact(empty, ProtocolProfile()) shouldNotBe null
            RedactionChain.standard().redact(empty, ProtocolProfile()) shouldBe empty
        }
    })
