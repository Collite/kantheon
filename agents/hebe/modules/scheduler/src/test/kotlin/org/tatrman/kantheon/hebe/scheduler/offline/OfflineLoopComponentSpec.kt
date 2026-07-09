package org.tatrman.kantheon.hebe.scheduler.offline

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.hebe.config.LlmSource

/**
 * The full offline loop (P2 Stage 2.5 T6) wired from the four mechanisms with a
 * faked connectivity probe: a `personal` Hebe loses connectivity across a
 * scheduled fire, then reconciles on resume. Mocked at the component level (no
 * real intermittent host — that's the integration suite).
 *
 * Scenario: while offline (breaker open) two routines fire — one own-routine
 * (heartbeat) and one constellation turn (kantheon_question). Assert:
 *   • the own-routine is served by the byok fallback (Hebe stays useful),
 *   • the constellation turn defers via the outbox + emits the never-silent note,
 *   • on reconnect the outbox drains in order and the deferred turn runs,
 *   • missed cron ticks catch up per policy.
 */
class OfflineLoopComponentSpec :
    StringSpec({

        "personal Hebe survives a connectivity gap and reconciles on resume" {
            runTest {
                val source = LlmSource.GATEWAY_WITH_BYOK_FALLBACK
                val breaker = CircuitBreaker(failureThreshold = 1, cooldownMillis = 1000, now = { 0 })
                val outbox = Outbox()
                val channelNotes = mutableListOf<String>()
                val servedByFallback = mutableListOf<String>()

                // ── go offline ───────────────────────────────────────────────────
                breaker.forceOpen()
                val offline = breaker.state() == BreakerState.OPEN
                offline shouldBe true

                // own-routine (heartbeat) while offline → byok fallback
                val heartbeat = FallbackRouter.decide(source, breakerOpen = offline, TurnKind.OWN_ROUTINE)
                heartbeat shouldBe RoutingDecision.UseByokFallback
                if (heartbeat == RoutingDecision.UseByokFallback) servedByFallback.add("heartbeat")

                // constellation turn (kantheon_question) while offline → defer + queued note
                val question = FallbackRouter.decide(source, breakerOpen = offline, TurnKind.CONSTELLATION)
                question shouldBe RoutingDecision.Defer
                if (question == RoutingDecision.Defer) {
                    outbox.enqueue(OutboxItem("turn:weekly:100", "iris", "ask X"))
                    channelNotes.add(FallbackRouter.QUEUED_NOTE)
                }

                servedByFallback shouldContainExactly listOf("heartbeat")
                channelNotes shouldContainExactly listOf(FallbackRouter.QUEUED_NOTE)
                outbox.pendingCount() shouldBe 1

                // ── reconnect: an explicit recovery clears the forced-open breaker ─
                // (forceOpen is a sticky operator degrade — it does not self-heal on
                //  the cooldown timer, so recovery is explicit, not a straggler success.)
                breaker.reset()
                breaker.state() shouldBe BreakerState.CLOSED

                // missed cron ticks catch up (heartbeat every 60s, down 5 ticks, run_once_on_wake)
                val catchup =
                    CatchupPlanner.plan(
                        listOf(RoutineSchedule("heartbeat", nextRunAt = 100, intervalSeconds = 60, CatchupPolicy.RUN_ONCE_ON_WAKE)),
                        now = 400,
                        coalesce = true,
                    )
                catchup.fires.size shouldBe 1 // exactly one owed fire despite 5 missed

                // the deferred constellation turn drains in order and runs
                val ran = mutableListOf<String>()
                outbox
                    .drain {
                        ran.add(it.key)
                        true
                    }.sent shouldBe 1
                ran shouldContainExactly listOf("turn:weekly:100")
                outbox.pendingCount() shouldBe 0
            }
        }

        "always-on profile: the machinery is idle (gateway used directly, nothing queued)" {
            runTest {
                val outbox = Outbox()
                // server/k8s: gateway source, breaker closed → straight to the gateway.
                FallbackRouter.decide(LlmSource.GATEWAY, breakerOpen = false, TurnKind.CONSTELLATION) shouldBe RoutingDecision.UseGateway
                FallbackRouter.decide(LlmSource.GATEWAY, breakerOpen = false, TurnKind.OWN_ROUTINE) shouldBe RoutingDecision.UseGateway
                outbox.pendingCount() shouldBe 0
            }
        }
    })
