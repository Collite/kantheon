package org.tatrman.kantheon.iris.stream

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.envelope.v1.PlanSource
import org.tatrman.kantheon.iris.dispatch.golemv2.V2SseParser
import org.tatrman.kantheon.iris.dispatch.golemv2.V2StreamEvent
import org.tatrman.kantheon.iris.dispatch.golemv2.sseFixture
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.v1.IrisStreamEvent

class IrisStreamMuxSpec :
    StringSpec({

        suspend fun runFixture(name: String): Pair<List<IrisStreamEvent>, TurnOutcome> {
            val events = V2SseParser.parse(sseFixture(name))
            val out = mutableListOf<IrisStreamEvent>()
            val outcome = IrisStreamMux().run("turn-1", events.asFlow()) { out.add(it) }
            return out to outcome
        }

        "happy path: steps → envelope → synthesised done, monotone sequence" {
            runTest {
                val (emitted, outcome) = runFixture("happy-table.sse")

                // Monotone sequence 1..N.
                emitted.map { it.sequence } shouldContainExactly (1L..emitted.size.toLong()).toList()
                // turn_id propagated.
                emitted.all { it.turnId == "turn-1" } shouldBe true

                // Terminal envelope normalised to envelope/v1.
                val env = emitted.first { it.hasEnvelope() }.envelope
                env.planSource shouldBe PlanSource.PATTERN
                env.format.kind shouldBe FormatKind.TABLE
                env.agentId shouldBe "golem-v2"

                // Last event is a synthesised done(done).
                val last = emitted.last()
                last.hasDone() shouldBe true
                last.done.outcome shouldBe "done"

                outcome.status shouldBe TurnStatus.DONE
                outcome.pendingResumeToken shouldBe null
            }
        }

        "plan_pick and exec_done map to step events with detail" {
            runTest {
                val (emitted, _) = runFixture("happy-table.sse")
                val planStep =
                    emitted.first {
                        it.hasStep() &&
                            it.step.node == "pick_plan" &&
                            it.step.detailJson.contains("source")
                    }
                planStep.step.detailJson.contains("\"source\":\"pattern\"") shouldBe true
                val execStep =
                    emitted.first {
                        it.hasStep() &&
                            it.step.node == "execute" &&
                            it.step.detailJson.contains("rowCount")
                    }
                execStep.step.detailJson.contains("\"rowCount\":2") shouldBe true
            }
        }

        "clarification: outcome carries the resume token and done=clarification" {
            runTest {
                val (emitted, outcome) = runFixture("clarification.sse")
                outcome.status shouldBe TurnStatus.CLARIFICATION
                outcome.pendingResumeToken shouldBe "rt-abc"
                emitted.last().done.outcome shouldBe "clarification"
            }
        }

        "error terminal: error event + done=failed" {
            runTest {
                val (emitted, outcome) = runFixture("error.sse")
                emitted.any { it.hasError() && it.error.code == "STREAM_ERROR" } shouldBe true
                outcome.status shouldBe TurnStatus.FAILED
                emitted.last().done.outcome shouldBe "failed"
            }
        }

        "upstream flow throwing mid-stream still emits terminal error + done(failed), outcome FAILED" {
            runTest {
                val failing =
                    flow<V2StreamEvent> {
                        emit(V2StreamEvent.NodeStart("resolve"))
                        throw RuntimeException("upstream exploded")
                    }
                val out = mutableListOf<IrisStreamEvent>()
                val outcome = IrisStreamMux().run("turn-x", failing) { out.add(it) }

                out.any { it.hasError() && it.error.code == "STREAM_ERROR" } shouldBe true
                out.last().hasDone() shouldBe true
                out.last().done.outcome shouldBe "failed"
                outcome.status shouldBe TurnStatus.FAILED
                // sequence stays monotone across the synthesised error + done.
                out.map { it.sequence } shouldContainExactly (1L..out.size.toLong()).toList()
            }
        }

        "a terminal envelope carrying error_code finalises as FAILED, not DONE" {
            runTest {
                val raw =
                    Json
                        .parseToJsonElement(
                            """{"bubble_id":"b","turn_id":"t","format":{"kind":"plaintext"},""" +
                                """"plan_source":"pattern","error_code":"DOWNSTREAM_BOOM"}""",
                        ).jsonObject
                val out = mutableListOf<IrisStreamEvent>()
                val outcome =
                    IrisStreamMux().run("turn-e", listOf<V2StreamEvent>(V2StreamEvent.Envelope(raw)).asFlow()) {
                        out.add(it)
                    }
                outcome.status shouldBe TurnStatus.FAILED
                outcome.errorCode shouldBe "DOWNSTREAM_BOOM"
                out.last().done.outcome shouldBe "failed"
            }
        }
    })
