package org.tatrman.kantheon.iris.inbox

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

private fun inv(
    id: String,
    status: String,
) = InvestigationSummary(id, "q", status, "t0", "t1")

class LifecycleHubSpec :
    StringSpec({

        "fans an event out only to sinks for that user" {
            runBlocking {
                val hub = LifecycleHub()
                val a = mutableListOf<String>()
                val b = mutableListOf<String>()
                hub.subscribe("ua", "tok") { a += it.newStatus }
                hub.subscribe("ub", "tok") { b += it.newStatus }

                hub.publish(LifecycleEvent("i1", "ua", "EXECUTING", "DONE", "now"))

                a shouldContainExactly listOf("DONE")
                b shouldContainExactly emptyList()
            }
        }

        "a closed subscription stops receiving" {
            runBlocking {
                val hub = LifecycleHub()
                val got = mutableListOf<String>()
                val sub = hub.subscribe("ua", "tok") { got += it.newStatus }
                sub.close()
                hub.publish(LifecycleEvent("i1", "ua", "X", "Y", "now"))
                got shouldContainExactly emptyList()
            }
        }

        "two sinks for the same user both receive an event" {
            runBlocking {
                val hub = LifecycleHub()
                val a = mutableListOf<String>()
                val b = mutableListOf<String>()
                hub.subscribe("ua", "tok") { a += it.newStatus }
                hub.subscribe("ua", "tok") { b += it.newStatus }
                hub.publish(LifecycleEvent("i1", "ua", "X", "DONE", "now"))
                a shouldContainExactly listOf("DONE")
                b shouldContainExactly listOf("DONE")
            }
        }

        "closing the last subscriber drops the user from activeUsers (no per-user leak)" {
            runBlocking {
                val hub = LifecycleHub()
                val sub = hub.subscribe("ua", "tok") { }
                hub.activeUsers().keys shouldContainExactly setOf("ua")
                sub.close()
                hub.activeUsers().keys shouldContainExactly emptySet()
            }
        }

        "a sink that throws does not break fan-out to the other sink" {
            runBlocking {
                val hub = LifecycleHub()
                val ok = mutableListOf<String>()
                hub.subscribe("ua", "tok") { error("boom") }
                hub.subscribe("ua", "tok") { ok += it.newStatus }
                hub.publish(LifecycleEvent("i1", "ua", "X", "DONE", "now"))
                ok shouldContainExactly listOf("DONE")
            }
        }

        "the polling driver stops tracking a terminal investigation" {
            runBlocking {
                val statuses = mutableMapOf("i1" to "EXECUTING")
                val pythia =
                    object : PythiaClient {
                        override suspend fun listInvestigations(
                            userId: String,
                            bearer: String,
                        ) = listOf(inv("i1", statuses["i1"]!!))

                        override suspend fun submit(
                            questionJson: String,
                            bearer: String,
                        ) = "i1"
                    }
                val hub = LifecycleHub()
                val events = mutableListOf<LifecycleEvent>()
                hub.subscribe("ua", "tok") { events += it }
                val driver = PollingLifecycleDriver(pythia, hub, intervalMs = 1) { "now" }

                driver.pollOnce() // first sighting
                statuses["i1"] = "DONE"
                driver.pollOnce() // EXECUTING → DONE event; terminal → stop tracking
                events.size shouldBe 1
                // A later non-terminal status for the same id is treated as first-seen
                // (no stale prev), so no spurious EXECUTING→… event fires.
                statuses["i1"] = "EXECUTING"
                driver.pollOnce()
                events.size shouldBe 1
            }
        }

        "the polling driver publishes a lifecycle event only on a status change" {
            runBlocking {
                val statuses = mutableMapOf("i1" to "EXECUTING")
                val pythia =
                    object : PythiaClient {
                        override suspend fun listInvestigations(
                            userId: String,
                            bearer: String,
                        ) = listOf(inv("i1", statuses["i1"]!!))

                        override suspend fun submit(
                            questionJson: String,
                            bearer: String,
                        ) = "i1"
                    }
                val hub = LifecycleHub()
                val events = mutableListOf<LifecycleEvent>()
                hub.subscribe("ua", "tok") { events += it }
                val driver = PollingLifecycleDriver(pythia, hub, intervalMs = 1) { "now" }

                driver.pollOnce() // first sighting — records, no event
                events.size shouldBe 0
                statuses["i1"] = "DONE"
                driver.pollOnce() // change → event
                events.single().let {
                    it.oldStatus shouldBe "EXECUTING"
                    it.newStatus shouldBe "DONE"
                }
                driver.pollOnce() // no further change → no new event
                events.size shouldBe 1
            }
        }
    })
