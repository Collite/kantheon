package org.tatrman.kantheon.golem.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.tatrman.kantheon.golem.v1.ConversationalResponse
import org.tatrman.kantheon.golem.v1.MiniPlan
import org.tatrman.kantheon.golem.v1.PlanSource
import org.tatrman.kantheon.golem.v1.ResourceUsage
import org.tatrman.kantheon.golem.v1.StepRecord

class SseAnswerSpec :
    StringSpec({

        "the turn event stream uses the fixed event set in order, terminating with `envelope`" {
            val response =
                ConversationalResponse
                    .newBuilder()
                    .setId("r1")
                    .setRequestId("t1")
                    .setPlan(MiniPlan.newBuilder().setSource(PlanSource.PATTERN).setConfidence(0.95))
                    .addStepRecords(
                        StepRecord
                            .newBuilder()
                            .setNodeId("q1")
                            .setNodeKind("QUERY")
                            .setStatus("COMPLETED"),
                    ).addStepRecords(
                        StepRecord
                            .newBuilder()
                            .setNodeId("r1")
                            .setNodeKind("RENDER")
                            .setStatus("COMPLETED"),
                    ).setResourceUsage(ResourceUsage.newBuilder().setQueryCount(1).setTotalLatencyMs(42))
                    .build()

            val frames = turnEventFrames(response)
            val events = frames.map { it.substringAfter("event: ").substringBefore("\n") }

            events shouldBe
                listOf(
                    "node_start",
                    "node_done", // q1
                    "node_start",
                    "node_done", // r1
                    "plan_pick",
                    "exec_done",
                    "envelope",
                )
            // Each frame is a well-formed SSE record.
            frames.first() shouldStartWith "event: node_start\ndata: "
            frames.first { it.startsWith("event: plan_pick") } shouldContain "\"source\":\"PATTERN\""
            frames.last() shouldContain "\"id\":\"r1\"" // the terminal envelope carries the full response
        }

        "SseEvents.frame produces a `event:`/`data:` pair with a blank-line terminator" {
            SseEvents.frame("envelope", """{"x":1}""") shouldBe "event: envelope\ndata: {\"x\":1}\n\n"
        }
    })
