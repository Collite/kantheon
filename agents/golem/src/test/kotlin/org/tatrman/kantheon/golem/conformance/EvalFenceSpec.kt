package org.tatrman.kantheon.golem.conformance

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.kantheon.golem.graph.GolemGraphDeps
import org.tatrman.kantheon.golem.graph.GolemTurnState
import org.tatrman.kantheon.golem.graph.callResolutionCoreNode
import org.tatrman.kantheon.golem.graph.walkResolutionNodes
import org.tatrman.kantheon.golem.resolution.RecordedResolutionCore
import org.tatrman.kantheon.golem.resolution.TurnEnd
import org.tatrman.kantheon.golem.resolution.ladder.LadderConfig
import org.tatrman.kantheon.golem.resolution.ladder.LlmRung
import org.tatrman.kantheon.golem.resolution.ladder.LookupRung
import org.tatrman.kantheon.golem.resolution.ladder.RV_GAP_KINDS
import org.tatrman.kantheon.golem.resolution.ladder.RV_HYPOTHESES_OUT
import org.tatrman.kantheon.golem.resolution.ladder.RV_RUNG
import org.tatrman.kantheon.golem.resolution.ladder.RV_SPAN_RUNG
import org.tatrman.kantheon.golem.resolution.ladder.RungLlm
import org.tatrman.kantheon.golem.resolution.ladder.ScriptedGate
import org.tatrman.kantheon.golem.resolution.ladder.gateResult
import org.tatrman.kantheon.golem.resolution.testDeps
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * RV-P5.4 T4 — **the two fences, as eval jobs.**
 *
 * They join the named eval entry point (`just eval-golem-rv`) rather than sitting only inside
 * the general unit suite, so a phase gate can say *"the fences ran"* and point at something.
 *
 * ⚑ **T1 Ruling — "nightly, not just at PR time" is backwards for these**, and the recon says so
 * at length. Both are deterministic: PR + merge is strictly more coverage than a nightly, and
 * kantheon's only nightly is cluster-bound with its `schedule:` disabled (kantheon#29), so
 * "promoted to the nightly" would have meant promoted to *never runs*. What genuinely needs a
 * scheduled job is the one check that can fail with **no kantheon commit behind it** — the
 * vendored corpus against its `tatrman-server` originals — and that is what `eval-nightly.yml`
 * runs, with `TATRMAN_SERVER_DIR` set.
 */
private val MAIN = Path.of("src/main/kotlin/org/tatrman/kantheon/golem")

/**
 * The only Themis surface a Golem may name: the **proto contract**. `themis/v1` is a shared
 * wire type; anything under the service's own Kotlin roots is its internals.
 */
private val THEMIS_CONTRACT = "org.tatrman.kantheon.themis.v1"
private val THEMIS_ANY = Regex("""\borg\.tatrman\.kantheon\.themis\.[A-Za-z0-9_.]+""")

private fun longAttr(name: String) = AttributeKey.longKey(name)

private fun stringAttr(name: String) = AttributeKey.stringKey(name)

class EvalFenceSpec :
    StringSpec({

        // ---------------------------------------------------- (a) Themis internals untouched

        "the Golem names Themis only through its proto contract, never its internals" {
            // The behavioural half of "Themis untouched" is `IntentBaselineGoldenSpec` — with
            // zero operator annotations the seam moves no verdict, over the whole routing-seed
            // corpus. This is the STRUCTURAL half, and it is the one that survives a refactor:
            // a golden proves the seam behaves, a grep proves nobody wired around it.
            //
            // The P5.3 recon settled why this is even possible: `classifyIntentKind` runs over
            // NLP tokens INSIDE Themis, before the Golem exists, so the verdict arrives on the
            // wire as `GolemRequest.resolved_intent`. There is nothing to import.
            val offenders =
                Files
                    .walk(MAIN)
                    .filter { Files.isRegularFile(it) && it.extension == "kt" }
                    .filter { file ->
                        THEMIS_ANY.findAll(file.readText()).any { !it.value.startsWith("$THEMIS_CONTRACT.") }
                    }.map { it.toString() }
                    .toList()

            withClue(
                "these files reach past themis/v1 into the Themis service's own packages; RV-P6 " +
                    "retires that service and until then it runs UNTOUCHED (ruling (A)): $offenders",
            ) { offenders shouldBe emptyList() }
        }

        "the fence can actually fire — it matches a Themis internal reference" {
            // A fence nobody has seen fire is a fence nobody should trust. No file in `src/main`
            // legitimately carries one, so the pattern is exercised against a literal instead.
            val internal = "import org.tatrman.kantheon.themis.koog.nodes.ClassifyIntentKindNode"
            val hit = THEMIS_ANY.find(internal)?.value
            withClue("the regex must not have rotted into matching nothing") {
                (hit != null && !hit.startsWith("$THEMIS_CONTRACT.")) shouldBe true
            }
            // …and must not fire on the contract itself, or the fence above is unpassable.
            THEMIS_ANY
                .find("import org.tatrman.kantheon.themis.v1.Themis")!!
                .value
                .startsWith("$THEMIS_CONTRACT.") shouldBe true
        }

        // ------------------------------------------------- (b) per-rung OTEL, queryable

        "every rung invocation in the H2 run produced its span, with the health numbers" {
            runTest {
                // plan.md's P5 DONE says per-rung OTEL is **visible**. Emitted is not visible:
                // the claim is that an operator can query "which rung ran, over which gap kinds,
                // and how many hypotheses came out" — so the spans are captured and read.
                //
                // ⚑ These spans have existed since P5.2 T5 and had never been asserted. They are
                // also the ladder's ONLY health signal, which makes an unasserted emitter the
                // worst kind: it looks like observability right up until you query it.
                val exporter = InMemorySpanExporter.create()
                val sdk =
                    OpenTelemetrySdk
                        .builder()
                        .setTracerProvider(
                            SdkTracerProvider
                                .builder()
                                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                                .build(),
                        ).build()

                val core = RecordedResolutionCore.client("h2-cs")
                val resolution =
                    testDeps(
                        ladder = LadderConfig.loadDefault(),
                        rungs =
                            mapOf(
                                "lookup" to LookupRung(),
                                "local" to LlmRung("local", RungLlm { _, _, _ -> "NONE" }),
                            ),
                        gate = ScriptedGate(gateResult()),
                    ).copy(otel = sdk)
                val deps =
                    GolemGraphDeps(
                        composer = mockk(relaxed = true),
                        validator = mockk(relaxed = true),
                        miniPlanExecutor = mockk(relaxed = true),
                        promptExecutor = mockk(relaxed = true),
                        resolutionCore = core,
                        resolution = resolution,
                        otel = sdk,
                        referenceDatetime = { "2026-08-06T12:00:00Z" },
                    )
                val state =
                    GolemTurnState(
                        request =
                            requestFor(
                                buildJsonObject {
                                    put("text", "Zobraz prvních 10 čerpacích stanic v Praze podle tržby za 12 měsíců.")
                                    put("conversation_id", "c-h2-otel")
                                    put("turn_id", "t-1")
                                    put("locale", "cs")
                                    put("caller_subject", "user-a")
                                },
                            ),
                        userId = "user-a",
                        tenantId = "t1",
                    )

                val out = walkResolutionNodes(callResolutionCoreNode(state, deps), deps)
                out.turnEnd!!::class shouldBe TurnEnd.Paused::class

                val rungSpans = exporter.finishedSpanItems.filter { it.name == RV_SPAN_RUNG }
                withClue("one span per rung INVOCATION — a rung that no-ops still ran and still cost time") {
                    rungSpans.map { it.attributes.get(stringAttr(RV_RUNG)) } shouldContainExactly
                        listOf("lookup", "local")
                }
                rungSpans.forEach { span ->
                    span.kind shouldBe SpanKind.INTERNAL
                    withClue("${span.attributes.get(stringAttr(RV_RUNG))}: gap kinds are the rung's INPUT") {
                        span.attributes.get(stringAttr(RV_GAP_KINDS)) shouldBe "G1_UNBOUND,G3_UNATTRIBUTED"
                    }
                    withClue("${span.attributes.get(stringAttr(RV_RUNG))}: hypotheses out is the health number") {
                        span.attributes.get(longAttr(RV_HYPOTHESES_OUT)) shouldBe 0L
                    }
                }
                withClue("the core call is on the same trace, so a turn reads as one story") {
                    exporter.finishedSpanItems.size.toLong() shouldBeGreaterThanOrEqual 3L
                }
            }
        }
    })
